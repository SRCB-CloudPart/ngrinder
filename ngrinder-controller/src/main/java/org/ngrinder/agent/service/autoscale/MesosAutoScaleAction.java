/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ngrinder.agent.service.autoscale;

import com.google.common.cache.Cache;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.protobuf.ByteString;
import org.apache.commons.lang.StringUtils;
import org.apache.mesos.*;
import org.ngrinder.agent.model.AutoScaleNode;
import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.ngrinder.agent.service.AgentAutoScaleService;
import org.ngrinder.common.util.PropertiesWrapper;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.schedule.ScheduledTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;

import static com.google.common.cache.CacheBuilder.newBuilder;
import static com.google.common.collect.Lists.newArrayList;
import static org.ngrinder.common.constant.AgentAutoScaleConstants.*;
import static org.ngrinder.common.util.ExceptionUtils.processException;
import static org.ngrinder.common.util.Preconditions.checkNotNull;

/**
 * Mesos AgentAutoScaleAction
 *
 * @since 3.3.2
 */
@Qualifier("mesos")
public class MesosAutoScaleAction extends AgentAutoScaleAction implements Scheduler {
	private static final Logger LOG = LoggerFactory.getLogger(MesosAutoScaleAction.class);

	private final String MESOS_TYPE = "mesos";

	Config config;

	ScheduledTaskService scheduledTaskService;

	/**
	 * Cache b/w task_ID and last touched timestamp, attention, the task ID is consisted of slave ID and "-NA"
	 */
	private Cache<String, Long> touchCache;
	private Runnable cacheCleanUp;

	/**
	 * record latest task information
	 */
	private ConcurrentSkipListSet<AutoScaleNode> tasksInfo = new ConcurrentSkipListSet<AutoScaleNode>();

	private int maxNodeCount;

	private boolean isMesosFrameworkStart = false;
	private boolean nativeLibraryLoaded = false;

	private MesosSchedulerDriver driver = null;
	private Map<String, String> slaveAttributes = null;


	/**
	 * under mesos environment, the nodes can be used is not static to ngrinder controller, it is impossible to reserve
	 * some nodes before test. so, the activatable nodes are the rest ones of the resource offers. It is different in
	 * different resource offer, maybe.
	 */
	private transient int activatableNodes = 0;

	/**
	 * define one object list to store the offer from master, this is the sync target between two threads resourceOffer
	 * and activateNodes ...
	 */
	private List<Protos.Offer> offeredNodes = newArrayList();


	/**
	 * check if the ngrinder framework has been registered to mesos master
	 *
	 * @return true or false
	 */
	public boolean isMesosFrameworkStarted(){
		return isMesosFrameworkStart;
	}

	private boolean isAgentAutoScaleMesosEnabled(){
		PropertiesWrapper pw = config.getAgentAutoScaleProperties();
		String type = pw.getProperty(PROP_AGENT_AUTO_SCALE_TYPE);
		String controllerIP = config.getControllerAdvertisedHost();
		int controllerUrlIP = pw.getPropertyInt(PROP_AGENT_AUTO_SCALE_CONTROLLER_URL_PORT);
		String dockerRepo = pw.getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_REPO);
		String dockerTag = pw.getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_TAG);
		String frameworkName = pw.getProperty(PROP_AGENT_AUTO_SCALE_MESOS_FRAMEWORK_NAME);
		String master = pw.getProperty(PROP_AGENT_AUTO_SCALE_MESOS_MASTER);
		String libPath = pw.getProperty(PROP_AGENT_AUTO_SCALE_MESOS_LIB_PATH);

		return ((StringUtils.isNotEmpty(type) && type.equals(MESOS_TYPE))
				&& StringUtils.isNotEmpty(controllerIP)
				&& StringUtils.isNotEmpty(dockerRepo)
				&& StringUtils.isNotEmpty(dockerTag)
				&& StringUtils.isNotEmpty(frameworkName)
				&& StringUtils.isNotEmpty(master)
				&& StringUtils.isNotEmpty(libPath));
	}

	/**
	 * If auto_scale_mesos_slave_attributes is configured, this function is used to parse the attributes. The Framework
	 * will select the mesos slave according to these attributes.
	 *
	 */
	private void parseSlaveAttributes() {
		String salveAttrs = config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_MESOS_SLAVE_ATTRIBUTES);
		if (salveAttrs == null) {
			return;
		}

		String [] attrs = salveAttrs.split(";");
		for(String attr:attrs) {
			String [] values = attr.split(":");
			if (values.length == 2) {
				slaveAttributes.put(values[0], values[1]);
			}
		}
	}

	/**
	 * load the mesos Native mesos library. The mesos library is generated by making the mesos source code.
	 */
	private void loadNativeLibrary() {
		if(!nativeLibraryLoaded) {
			// we attempt to load the library from the given path.
			try {
				MesosNativeLibrary.load(config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_MESOS_LIB_PATH));
			} catch (UnsatisfiedLinkError error) {
				LOG.error("Failed to load native Mesos library from '{}', {}", config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_MESOS_LIB_PATH)
						,error.getMessage());

				throw processException("Failed to load native Mesos library", error);
			}
			nativeLibraryLoaded = true;
		}
	}

	/**
	 * Start framework: parse the configured slave attributes, load the mesos native library
	 * and register the framework info to mesos master.
	 *
	 */
	public void startFramework(){

		parseSlaveAttributes();
		loadNativeLibrary();

		if (driver == null) {
			String user = config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_MESOS_USER);
			String frameworkName = config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_MESOS_FRAMEWORK_NAME);
			String principal = config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_MESOS_PRINCIPAL);
			String controllerIP = config.getControllerAdvertisedHost();
			int controllerUrlPort = config.getAgentAutoScaleProperties().getPropertyInt(PROP_AGENT_AUTO_SCALE_CONTROLLER_URL_PORT);
			String secret = config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_MESOS_SECRET);
			String master = config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_MESOS_MASTER);

			Protos.FrameworkInfo frameworkInfo = Protos.FrameworkInfo.newBuilder()
					.setUser(user == null ? "" : user)
					.setName(frameworkName)
					.setPrincipal(principal == null ? "" : principal)
					.setWebuiUrl("http://" + controllerIP + ":" + controllerUrlPort)
					.build();

			LOG.info("Start register ngrinder framework to mesos master!");

			isMesosFrameworkStart = true;
			if (principal != null && secret != null) {
				Protos.Credential credential = Protos.Credential.newBuilder()
						.setPrincipal(principal)
						.setSecret(ByteString.copyFromUtf8(secret))
						.build();

				driver = new MesosSchedulerDriver(this, frameworkInfo, master, credential);
			}
			else {
				driver = new MesosSchedulerDriver(this, frameworkInfo, master);
			}

			Protos.Status runStatus = driver.run();

			if (runStatus != Protos.Status.DRIVER_STOPPED) {
				LOG.info("The Mesos driver was aborted! Status code: " + runStatus.getNumber());
			}

			LOG.info("nGrinder framework is stopped");

			driver = null;
			isMesosFrameworkStart = false;
		}
	}

	/**
	 * Match the offer's attributes and configured slaveAttributes in system config.
	 *
	 * @param offer offer
	 * @return true or false
	 */
	private boolean matchAttributes(Protos.Offer offer) {
		boolean slaveTypeMatch = true;

		if (slaveAttributes.size() == 0) {
			return slaveTypeMatch;
		}

		//get the offer's attribute
		Map<String, String> attributesMap = new ConcurrentHashMap<String, String>();
		for (Protos.Attribute attribute:offer.getAttributesList()) {
			attributesMap.put(attribute.getName(), attribute.getText().getValue());
		}

		Iterator iterator = slaveAttributes.keySet().iterator();
		while (iterator.hasNext()) {
			String key = iterator.next().toString();

			//If there is a single absent attribute then we should reject this offer.
			if (!(attributesMap.containsKey(key)
					&& attributesMap.get(key).equals(slaveAttributes.get(key)))) {
				slaveTypeMatch = false;
				break;
			}
		}

		return slaveTypeMatch;
	}

	/**
	 * If the framework is not started and mesos is enabled, initialize and register framework to mesos master.
	 * If framework is started and mesos is not enabled, stop the framework.
	 * If framework is started and mesos is enabled, parse the attributes info.
	 *
	 */
	public void registerNgrinderFramework() {

		if (!isMesosFrameworkStarted() && isAgentAutoScaleMesosEnabled()) {
			slaveAttributes = new ConcurrentHashMap<String, String>();

			Runnable registerRunnable = new Runnable() {
				@Override
				public void run() {
					startFramework();
				}
			};

			scheduledTaskService.runAsync(registerRunnable);
		}
		else if (isMesosFrameworkStarted() && driver != null && !isAgentAutoScaleMesosEnabled()){
			driver.stop();
			driver = null;
			isMesosFrameworkStart = false;
		}
		else if (isMesosFrameworkStarted()) {
			parseSlaveAttributes();
		}
	}

	/**
	 * generate the task Id. It is composited by slave Id and "NA" which means "ngrinder agent"
	 *
	 * @param offer the resource
	 * @return task Id
	 */
	private Protos.TaskID getTaskId(Protos.Offer offer){

		String slaveId = offer.getSlaveId().getValue();

		return Protos.TaskID.newBuilder().setValue(slaveId + "-NA" ).build();
	}

	/**
	 * Create and launch the mesos task. The task includes the docker info.
	 * Currently, one offer only run one task, all resources of offer is used by the task.
	 *
	 * @param offer offer
	 * @return Protos.TaskID taskId
	 */
	private Protos.TaskID createMesosTask(Protos.Offer offer) {

		Protos.TaskID taskId = getTaskId(offer);

		LOG.info("Create a new task, task id = {}", taskId.getValue());

		Protos.CommandInfo.Builder commandBuilder = Protos.CommandInfo.newBuilder();
		commandBuilder.setShell(false);

		Protos.ContainerInfo.Builder containerInfoBuilder = Protos.ContainerInfo.newBuilder();
		containerInfoBuilder.setType(Protos.ContainerInfo.Type.DOCKER);
		Protos.ContainerInfo.DockerInfo.Builder dockerInfoBuider = Protos.ContainerInfo.DockerInfo.newBuilder();
		dockerInfoBuider.setImage( config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_REPO)
				+ ":" + config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_TAG));
		dockerInfoBuider.setNetwork(Protos.ContainerInfo.DockerInfo.Network.HOST);

//		Protos.Parameter.Builder paramControllerHost = Protos.Parameter.newBuilder();
//		paramControllerHost.setKey("-ch");
//		paramControllerHost.setValue(config.getControllerAdvertisedHost());
//		dockerInfoBuider.addParameters(paramControllerHost);
//
//		Protos.Parameter.Builder paramControllerPort = Protos.Parameter.newBuilder();
//		paramControllerPort.setKey("-cp");
//		paramControllerPort.setValue(String.valueOf(config.getControllerPort()));
//		dockerInfoBuider.addParameters(paramControllerPort);
//
//		Protos.Parameter.Builder paramAgentRegion = Protos.Parameter.newBuilder();
//		paramControllerPort.setKey("-r");
//		paramControllerPort.setValue(config.getRegion());
//		dockerInfoBuider.addParameters(paramAgentRegion);
//
//		Protos.Parameter.Builder paramHostId = Protos.Parameter.newBuilder();
//		paramControllerPort.setKey("-hi");
//		paramControllerPort.setValue(taskId.getValue());
//		dockerInfoBuider.addParameters(paramHostId);

		containerInfoBuilder.setDocker(dockerInfoBuider.build());

		Protos.TaskInfo.Builder taskBuilder = Protos.TaskInfo.newBuilder()
				.setName("Task"+taskId.getValue())
				.setTaskId(taskId)
				.setSlaveId(offer.getSlaveId());
		for (int i = 0; i < offer.getResourcesCount(); i++) {
			taskBuilder.addResources(offer.getResources(i));
		}
		taskBuilder.setCommand(commandBuilder.build());
		taskBuilder.setContainer(containerInfoBuilder.build());


		List<Protos.TaskInfo> tasks = new ArrayList<Protos.TaskInfo>();
		Protos.TaskInfo task = taskBuilder.build();
		tasks.add(task);

		tasksInfo.add(createAutoScaleNode(task));

		driver.launchTasks(offer.getId(), tasks);

		return taskId;
	}

	private AutoScaleNode createAutoScaleNode(Protos.TaskInfo task){
		AutoScaleNode node = new AutoScaleNode();

		node.setId(task.getTaskId().getValue());
		List<String> slaveId = newArrayList();
		slaveId.add(task.getSlaveId().getValue());
		node.setIps(slaveId);
		node.setName(task.getName());

		return node;
	}

	private void initCache(){
		RemovalListener<String, Long> removalListener = new RemovalListener<String, Long>() {
			@Override
			public void onRemoval(RemovalNotification<String, Long> removal) {
				final String key = checkNotNull(removal).getKey();
				RemovalCause removalCause = removal.getCause();
				if (removalCause.equals(RemovalCause.EXPIRED)) {
					synchronized (this) {
						scheduledTaskService.runAsync(new Runnable() {
														  @Override
														  public void run() {
															  try {
																  stopNode(key);
															  } catch (Exception e) {
																  LOG.error("Error while stopping task {}", key, e);
															  }
														  }
													  }
						);
					}
				}
			}
		};

		touchCache = newBuilder().expireAfterWrite(60*60, TimeUnit.SECONDS).removalListener(removalListener).build();
		this.cacheCleanUp = new Runnable() {
			@Override
			public void run() {
				touchCache.cleanUp();
			}
		};
		scheduledTaskService.addFixedDelayedScheduledTask(cacheCleanUp, 1000);
	}

	@Override
	public void init(Config config, ScheduledTaskService scheduledTaskService) {
		this.config = config;
		this.scheduledTaskService = scheduledTaskService;
		this.maxNodeCount = config.getAgentAutoScaleProperties().getPropertyInt(PROP_AGENT_AUTO_SCALE_MAX_NODES);
		/*
		 * attention, this initCache() must be called before registerNgrinderFramework.
		 */
		initCache();
		registerNgrinderFramework();
	}

	@Override
	public void activateNodes(int count) throws AgentAutoScaleService.NotSufficientAvailableNodeException {
		synchronized (offeredNodes){

			try {
				if(offeredNodes.size() < count) {
					offeredNodes.wait(1 * 60 * 1000);
				}
				if(offeredNodes.size() < count){
					throw new AgentAutoScaleService.NotSufficientAvailableNodeException(
							String.format("%d node activation is requested. But only %d nodes are available in mesos this moment. The activation is canceled.",
							count, offeredNodes.size()));
				}else{
					int cnt=0;
					for(Protos.Offer offer: offeredNodes){
						Protos.TaskID taskId = createMesosTask(offer);
						cnt++;
						if(cnt >= count){
							//release the offers which not used
							driver.declineOffer(offer.getId());
						}
					}
					activatableNodes = offeredNodes.size() - count;
				}
			} catch (InterruptedException e) {
				throw processException(e);
			}finally {
				offeredNodes.notifyAll();
			}
		}
	}

	@Override
	public int getMaxNodeCount() {
		return Math.min(tasksInfo.size(), maxNodeCount);
	}

	@Override
	public int getActivatableNodeCount() {
		return activatableNodes;
	}

	@Override
	public void touch(String taskId) {
		synchronized (this) {
			try {
				Long time = touchCache.getIfPresent(taskId);
				if (time == null) {
					time = System.currentTimeMillis();
				}
				touchCache.put(taskId, time);
			} catch (Exception e) {
				LOG.error("Error while touch node {}", taskId, e);
			}
		}
	}


	@Override
	public String getDiagnosticInfo() {
		return MESOS_TYPE;
	}

	@Override
	public void destroy() {
		scheduledTaskService.removeScheduledJob(this.cacheCleanUp);
		if(driver != null){
			driver.stop(true);
		}
	}

	@Override
	public List<AutoScaleNode> getNodes() {
		List<AutoScaleNode> result = newArrayList();
		for (AutoScaleNode node: tasksInfo) {

			result.add(node);
		}
		return result;
	}

	@Override
	public void refresh() {
		//do nothing
	}

	/**
	 * this function is to stop the specified task to release resource.
	 *
	 * @param nodeId here it means the taskId
	 */
	@Override
	public void stopNode(String nodeId) {
		Protos.TaskID taskId = Protos.TaskID.newBuilder().setValue(nodeId).build();
		LOG.info("Task {} is stopped...", nodeId);
		driver.killTask(taskId);

		/*
		 *make sure when do stop node, the activatedNodes count should be decremented.
		 */
		for(AutoScaleNode task : tasksInfo){
			if(task.getId().equalsIgnoreCase(nodeId)){
				tasksInfo.remove(task);
				break;
			}
		}
	}

	/**
	 * The main process in this function includes:
	 * 1. match the attributes. If offers' attributes include the configuring slaveAttributes, the offer can be used.
	 * 2. read the first request from the requests. create the task. store the task ID into results.
	 * 3. calculate mesosMaxAgentCount. It means all matched offers except being used.
	 *
	 * @param driver MesosSchedulerDriver
	 * @param offers offer list
	 */
	@Override
	public void resourceOffers(SchedulerDriver driver, List<Protos.Offer> offers) {

		synchronized (offeredNodes) {
			/*
			 * each activateNodes call means one new test requirement is coming, the offeredNodes should
			 * be cleared.
			 */
			offeredNodes.clear();
			for (Protos.Offer offer : offers) {

				if (matchAttributes(offer)) {
					offeredNodes.add(offer);
				} else {
					//This offer is not matched. Don't send us again in 10 mins.
					Protos.Filters filters = Protos.Filters.newBuilder().setRefuseSeconds(10 * 60).build();
					driver.declineOffer(offer.getId(), filters);
				}
			}
			/*
			 * activatable node count is dependent on the resource offer, it is changed...
			 */
			activatableNodes = offeredNodes.size();
			offeredNodes.notifyAll();
		}
		LOG.info("Received offers: {}, matched offers: {}", offers.size(), offeredNodes.size());
	}

	@Override
	public void statusUpdate(SchedulerDriver driver, Protos.TaskStatus status) {
		Protos.TaskID taskId = status.getTaskId();
		Protos.TaskState taskState = status.getState();

		for(AutoScaleNode task: tasksInfo){
			if(task.getId().equalsIgnoreCase(taskId.getValue())){
				task.setState(taskState.name());
			}
		}

		LOG.info("Received state update, task id = {}, task state = {}", taskId.getValue(), taskState.toString());
	}

	@Override
	public void registered(SchedulerDriver driver, Protos.FrameworkID frameworkId, Protos.MasterInfo masterInfo) {
		LOG.info("Framework registered! ID = {}", frameworkId.getValue());
		LOG.info("isMesosFrameworkStart = {}", isMesosFrameworkStart);
	}

	@Override
	public void reregistered(SchedulerDriver driver, Protos.MasterInfo masterInfo) {
		LOG.info("Framework re-registered");
	}

	@Override
	public void offerRescinded(SchedulerDriver driver, Protos.OfferID offerId) {
		LOG.info("Rescinded offer {}", offerId.getValue());
	}

	@Override
	public void frameworkMessage(SchedulerDriver driver, Protos.ExecutorID executorId,
								 Protos.SlaveID slaveId, byte[] data) {
		LOG.info("Received framework message from executor {} of slave {}" , executorId.getValue(), slaveId.getValue());
	}

	@Override
	public void disconnected(SchedulerDriver driver) {
		LOG.info("Framework disconnected!");
	}

	@Override
	public void slaveLost(SchedulerDriver driver, Protos.SlaveID slaveId) {
		LOG.info("Slave {} lost!",  slaveId.getValue());
	}

	@Override
	public void executorLost(SchedulerDriver driver, Protos.ExecutorID executorId,
							 Protos.SlaveID slaveId, int status) {
		LOG.info("Executor {} of slave {} lost!", executorId.getValue(), slaveId.getValue());
	}

	@Override
	public void error(SchedulerDriver driver, String message) {
		LOG.error(message);
	}
}
