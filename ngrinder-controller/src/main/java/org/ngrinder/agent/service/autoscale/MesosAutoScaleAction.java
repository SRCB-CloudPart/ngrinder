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
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.mesos.MesosSchedulerDriver;
import org.apache.mesos.Protos;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;
import org.ngrinder.agent.model.AutoScaleNode;
import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.ngrinder.agent.service.AgentAutoScaleService;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.schedule.ScheduledTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.google.common.cache.CacheBuilder.newBuilder;
import static org.ngrinder.common.constant.AgentAutoScaleConstants.*;
import static org.ngrinder.common.util.Preconditions.checkNotEmpty;
import static org.ngrinder.common.util.Preconditions.checkNotNull;

/**
 * Mesos AgentAutoScaleAction, initialized on July 2015
 *
 * @author binju
 * @author shihuc
 * @author JunHo Yoon
 * @since 3.3.2
 */
@Qualifier("mesos")
public class MesosAutoScaleAction extends AgentAutoScaleAction implements Scheduler {
	private static final Logger LOG = LoggerFactory.getLogger(MesosAutoScaleAction.class);

	private Config config;
	private ScheduledTaskService scheduledTaskService;

	private Cache<String, AutoScaleNode> nodeCache;
	private Runnable cacheCleanUp;

	private int maxNodeCount;
	private String dockerImg;
	private String master;
	private String frameworkName;

	private MesosSchedulerDriver driver = null;
	private Map<String, String> slaveAttributes = null;

	private CountDownLatch latch = null;

	@Override
	public void init(Config config, ScheduledTaskService scheduledTaskService) {
		this.config = config;
		this.scheduledTaskService = scheduledTaskService;
		this.maxNodeCount = config.getAgentAutoScaleProperties().getPropertyInt(PROP_AGENT_AUTO_SCALE_MAX_NODES);
		this.slaveAttributes = getSlaveAttributes(config);
		this.dockerImg = config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_REPO)
				+ ":" + config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_TAG);
		this.master = checkNotEmpty(config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_MESOS_MASTER),
				PROP_AGENT_AUTO_SCALE_MESOS_MASTER + " property should be set");
		this.frameworkName = getTagString(config);
		initCache();
		scheduledTaskService.runAsync(new Runnable() {
			@Override
			public void run() {
				try {
					startFramework();
				} catch (Exception e) {
					LOG.error("MESOS framework is stopped.", e);
				}
			}
		});
	}

	protected String getTagString(Config config) {
		if (config.isClustered()) {
			return config.getRegion();
		} else {
			return config.getControllerAdvertisedHost();
		}
	}


	private void initCache() {
		RemovalListener<String, AutoScaleNode> removalListener = new RemovalListener<String, AutoScaleNode>() {
			@Override
			public void onRemoval(RemovalNotification<String, AutoScaleNode> removal) {
				final String key = checkNotNull(removal).getValue().getId();
				RemovalCause removalCause = removal.getCause();
				if (removalCause.equals(RemovalCause.EXPIRED)) {
					synchronized (this) {
						scheduledTaskService.runAsync(new Runnable() {
							                              @Override
							                              public void run() {
								                              try {
									                              LOG.info("Guard timer expired for {}, release resource...", key);
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
		this.nodeCache = newBuilder().expireAfterWrite(10 * 60, TimeUnit.SECONDS).removalListener(removalListener).build();
		this.cacheCleanUp = new Runnable() {
			@Override
			public void run() {
				nodeCache.cleanUp();
			}
		};
		this.scheduledTaskService.addFixedDelayedScheduledTask(cacheCleanUp, 1000);
	}


	/**
	 * If auto_scale_mesos_slave_attributes is configured, this function is used to parse the attributes. The Framework
	 * will select the mesos slave according to these attributes
	 *
	 * @param config Config.
	 */
	protected Map<String, String> getSlaveAttributes(Config config) {
		Map<String, String> slaveAttributes = new ConcurrentHashMap<String, String>();
		String salveAttrs = config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_MESOS_SLAVE_ATTRIBUTES);
		String[] attrs = salveAttrs.split(";");
		for (String attr : attrs) {
			String[] values = attr.split(":");
			if (values.length == 2) {
				slaveAttributes.put(values[0], values[1]);
			}
		}
		return slaveAttributes;
	}

	/**
	 * Start framework: parse the configured slave attributes, load the mesos native library
	 * and register the framework info to mesos master.
	 */
	public void startFramework() {
		String principal = config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_IDENTITY);
		String secret = config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_CREDENTIAL);

		Protos.FrameworkInfo frameworkInfo = Protos.FrameworkInfo.newBuilder()
				.setName(frameworkName)
				.setUser("ngrinder")
				.build();

		if (principal != null && secret != null) {
			LOG.info("principle {} and secret {} is provided, connect to MESOS master {} with credential", new Object[]{principal, secret, master});
			Protos.Credential credential = Protos.Credential.newBuilder()
					.setPrincipal(principal)
					.setSecret(ByteString.copyFromUtf8(secret))
					.build();

			driver = new MesosSchedulerDriver(this, frameworkInfo, master, credential);
		} else {
			LOG.info("principle and secret are not provided, connect to MESOS master {} without credential", master);
			driver = new MesosSchedulerDriver(this, frameworkInfo, master);
		}
		Protos.Status runStatus = driver.run();

		if (runStatus != Protos.Status.DRIVER_STOPPED) {
			LOG.info("The Mesos driver was aborted! Status code: " + runStatus.getNumber());
		}

		LOG.info("nGrinder framework is stopped");
	}

	/**
	 * Match the offer's attributes and configured slaveAttributes in system config.
	 *
	 * @param offer offer
	 * @return true or false
	 */
	private boolean isMatching(Protos.Offer offer) {
		boolean slaveTypeMatch = true;
		if (slaveAttributes.size() == 0) {
			return true;
		}
		// Get the offer's attribute
		Map<String, String> attributesMap = new ConcurrentHashMap<String, String>();
		for (Protos.Attribute attribute : offer.getAttributesList()) {
			attributesMap.put(attribute.getName(), attribute.getText().getValue());
		}

		for (Map.Entry<String, String> each : slaveAttributes.entrySet()) {
			String key = each.getKey();

			//If there is a single absent attribute then we should reject this offer.
			if (!(attributesMap.containsKey(key)
					&& attributesMap.get(key).equals(each.getValue()))) {
				slaveTypeMatch = false;
				break;
			}
		}
		return slaveTypeMatch;
	}


	/**
	 * Generate the task Id with the given prefix,
	 *
	 * @param prefix prefix
	 * @param offer  the resource
	 * @return task Id
	 */
	private Protos.TaskID getTaskId(String prefix, Protos.Offer offer) {
		String slaveId = offer.getSlaveId().getValue();
		return Protos.TaskID.newBuilder().setValue(prefix + "-" + slaveId).build();
	}

	/**
	 * Create and launch the mesos task. The task includes the docker info.
	 * Currently, one offer only run one task, all resources of offer is used by the task.
	 *
	 * @param offer offer
	 * @return Protos.TaskID taskId
	 */
	private List<Protos.TaskInfo> createTask(Protos.Offer offer) {
		List<Protos.OfferID> offerIDs = new ArrayList<Protos.OfferID>();
		List<Protos.TaskInfo> tasks = new ArrayList<Protos.TaskInfo>();
		offerIDs.add(offer.getId());
		Protos.TaskID taskId = getTaskId(frameworkName, offer);
		LOG.info("Create a new task, task id '{}'", taskId.getValue());

		Protos.CommandInfo.Builder commandBuilder = Protos.CommandInfo.newBuilder();
		commandBuilder.addArguments("-ch").addArguments(config.getControllerAdvertisedHost());
		commandBuilder.addArguments("-cp").addArguments(String.valueOf(config.getControllerPort()));
		commandBuilder.addArguments("-r").addArguments(config.getRegion());
		commandBuilder.addArguments("-hi").addArguments(taskId.getValue());
		commandBuilder.setShell(false);

		Protos.ContainerInfo.Builder containerInfoBuilder = Protos.ContainerInfo.newBuilder();
		containerInfoBuilder.setType(Protos.ContainerInfo.Type.DOCKER);

		Protos.ContainerInfo.DockerInfo.Builder dockerInfoBuider = Protos.ContainerInfo.DockerInfo.newBuilder();
		dockerInfoBuider.setImage(dockerImg);
		dockerInfoBuider.setNetwork(Protos.ContainerInfo.DockerInfo.Network.HOST);

		containerInfoBuilder.setDocker(dockerInfoBuider.build());

		Protos.TaskInfo.Builder taskBuilder =
				Protos.TaskInfo.newBuilder()
						.setName("ngrinder-agent-" + taskId.getValue())
						.setTaskId(taskId)
						.setSlaveId(offer.getSlaveId());

		for (int i = 0; i < offer.getResourcesCount(); i++) {
			taskBuilder.addResources(offer.getResources(i));
		}

		taskBuilder.setCommand(commandBuilder.build());
		taskBuilder.setContainer(containerInfoBuilder.build());

		Protos.TaskInfo task = taskBuilder.build();
		tasks.add(task);
		driver.launchTasks(offerIDs, tasks);
		return tasks;
	}


	private AutoScaleNode createAutoScaleNode(Protos.TaskStatus task) {
		AutoScaleNode node = new AutoScaleNode();
		node.setId(task.getTaskId().getValue());
		node.setName(task.getSlaveId().getValue());
		node.setState(task.getState().name());
		return node;
	}

	@Override
	public void activateNodes(int count) throws AgentAutoScaleService.NotSufficientAvailableNodeException {
		LOG.info("Activate node function called: {}", count);
		if (getActivatableNodeCount() < count) {
			// FIXME
			throw new AgentAutoScaleService.NotSufficientAvailableNodeException("");
		}
		// reactivate offer listening.
		driver.reviveOffers();
		latch = new CountDownLatch(count);
		try {
			latch.await(1, TimeUnit.MINUTES);
		} catch (InterruptedException e) {
			// FIXME : we need to make the nodes to be terminated.. for this request.
			// To prevent the lack.
			throw new AgentAutoScaleService.NotSufficientAvailableNodeException("");
		}
		latch = null;
	}

	@Override
	public int getMaxNodeCount() {
		return maxNodeCount;
	}

	@Override
	public int getActivatableNodeCount() {
		return maxNodeCount - ((int) nodeCache.size());
	}

	@Override
	public void touch(String slaveId) {
		synchronized (this) {
			try {
				AutoScaleNode node = nodeCache.getIfPresent(slaveId);
				nodeCache.put(slaveId, node);
			} catch (Exception e) {
				LOG.error("Error while touch node {}", slaveId, e);
			}
		}
	}


	@Override
	public String getDiagnosticInfo() {
		return "";
	}

	@Override
	public void destroy() {
		scheduledTaskService.removeScheduledJob(this.cacheCleanUp);
		if (driver != null) {
			driver.stop(true);
		}
	}

	@Override
	public List<AutoScaleNode> getNodes() {
		return new ArrayList<AutoScaleNode>(nodeCache.asMap().values());
	}

	@Override
	public void refresh() {
		//do nothing
	}

	/**
	 * Stop the specified task to release resource.
	 *
	 * @param nodeId here it means the taskId
	 */
	@Override
	public void stopNode(String nodeId) {
		Protos.TaskID taskId = Protos.TaskID.newBuilder().setValue(nodeId).build();
		LOG.info("Task {} is stopped...", nodeId);
		driver.killTask(taskId);
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
		LOG.info("{}", ToStringBuilder.reflectionToString(offers));
		if (latch == null) {
			for (Protos.Offer each : offers) {
				Protos.Filters filters = Protos.Filters.newBuilder().setRefuseSeconds(10 * 60).build();
				driver.declineOffer(each.getId(), filters);
			}
			return;
		}
		for (Protos.Offer each : offers) {
			final AutoScaleNode node = nodeCache.getIfPresent(each.getSlaveId().getValue());
			if (node == null && isMatching(each)) {
				latch.countDown();
				createTask(each);
			} else {
				//This offer is not matched. Don't send us again in 10 mins.
				Protos.Filters filters = Protos.Filters.newBuilder().setRefuseSeconds(10 * 60).build();
				driver.declineOffer(each.getId(), filters);
			}
		}
	}


	@Override
	public void statusUpdate(SchedulerDriver driver, final Protos.TaskStatus status) {
		final Protos.TaskState taskState = status.getState();
		final Protos.SlaveID slaveId = status.getSlaveId();
		switch (taskState) {
			case TASK_LOST:
			case TASK_ERROR:
			case TASK_FAILED:
			case TASK_FINISHED:
				nodeCache.invalidate(slaveId);
				break;
			default:
				nodeCache.put(status.getSlaveId().getValue(), createAutoScaleNode(status));
		}
		LOG.info("Received state update, task id = {}, task state = {}", status.getTaskId().getValue(), status.getState().name());
	}

	@Override
	public void registered(SchedulerDriver driver, Protos.FrameworkID frameworkId, Protos.MasterInfo masterInfo) {
		LOG.info("Framework registered! ID = {}", frameworkId.getValue());
	}

	@Override
	public void reregistered(SchedulerDriver driver, Protos.MasterInfo masterInfo) {
		LOG.debug("Framework re-registered");
	}

	@Override
	public void offerRescinded(SchedulerDriver driver, Protos.OfferID offerId) {
		LOG.debug("Rescinded offer {}", offerId.getValue());
	}

	@Override
	public void frameworkMessage(SchedulerDriver driver, Protos.ExecutorID executorId,
	                             Protos.SlaveID slaveId, byte[] data) {
		LOG.info("Received framework message {} from executor {} of slave {}", new Object[]{new String(data), executorId.getValue(), slaveId.getValue()});
	}

	@Override
	public void disconnected(SchedulerDriver driver) {
		LOG.info("Framework disconnected!");
	}

	@Override
	public void slaveLost(SchedulerDriver driver, Protos.SlaveID slaveId) {
		LOG.info("Slave {} lost!", slaveId.getValue());
		nodeCache.invalidate(slaveId.getValue());
	}

	@Override
	public void executorLost(SchedulerDriver driver, Protos.ExecutorID executorId,
	                         Protos.SlaveID slaveId, int status) {
		LOG.info("Executor {} of slave {} lost!", executorId.getValue(), slaveId.getValue());
		nodeCache.invalidate(slaveId.getValue());
	}

	@Override
	public void error(SchedulerDriver driver, String message) {
		LOG.error(message);
	}
}
