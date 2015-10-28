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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.cache.CacheBuilder.newBuilder;
import static com.google.common.collect.Lists.newArrayList;
import static org.apache.mesos.Protos.*;
import static org.ngrinder.common.constant.AgentAutoScaleConstants.*;
import static org.ngrinder.common.util.Preconditions.checkNotEmpty;
import static org.ngrinder.common.util.Preconditions.checkNotNull;

/**
 * Mesos AgentAutoScaleAction, initialized on July 2015
 * <p/>
 * Note: Mesos framework can work normally, the native lib is a necessary. developer can load it explicitly, and also,
 * it can be loaded from system environment. Here, take it loaded from system environment.
 * MESOS_NATIVE_JAVA_LIBRARY or MESOS_NATIVE_LIBRARY should be configured before launch ngrinder controller if
 * this autoscale feature is enabled.
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

    /*
     * map like, key is the slave ID, value is the node information which used in web UI
     */
    private Cache<String, AutoScaleNode> nodeCache;
    private Runnable cacheCleanUp;

    private int maxNodeCount;
    private String dockerImg;
    private String master;
    private String frameworkName;

    private MesosSchedulerDriver driver = null;

    /*
     * which is used as resource filter, input provided to user to specify the required resource, e.g. CPU count,
     * memory quantity. This feature focuses on CPU and MEM these two kinds of resources.
     */
    private Map<String, String> resourceAttributes = null;

    private CountDownLatch latch = null;

    @Override
    public void init(Config config, ScheduledTaskService scheduledTaskService) {
        this.config = config;
        this.scheduledTaskService = scheduledTaskService;
        this.maxNodeCount = config.getAgentAutoScaleProperties().getPropertyInt(PROP_AGENT_AUTO_SCALE_MAX_NODES);
        this.resourceAttributes = getResourceAttributes(config);
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
                //Attention, first to get the cached data key, but not data value. It is easy to release resource cached
                final String key = checkNotNull(removal).getKey();
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
        this.nodeCache = newBuilder().expireAfterWrite(getTouchCacheDuration(), TimeUnit.SECONDS).removalListener(removalListener).build();
        this.cacheCleanUp = new Runnable() {
            @Override
            public void run() {
                nodeCache.cleanUp();
            }
        };
        this.scheduledTaskService.addFixedDelayedScheduledTask(cacheCleanUp, 1000);
    }

    /**
     * Set the default timer for cache management
     *
     * @return the timer for cache expiration
     */
    protected int getTouchCacheDuration() {
        return 60 * 60;
    }


    /**
     * If auto_scale.mesos_resource_attributes is configured, this function is used to parse the attributes. The Framework
     * will select the mesos slave according to these attributes
     * <p/>
     * And, this function should do some filter or input parse. In this feature, just focus on CPU and memory. the format
     * required is cpus:xx;mem:xxxx, if user provides like CPU:xx;MEM:xxxxMB, we should parse it.
     *
     * @param config Config.
     */
    protected Map<String, String> getResourceAttributes(Config config) {
        Map<String, String> resourceAttributes = new ConcurrentHashMap<String, String>();
        String resourceAttrs = config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_MESOS_RESOURCE_ATTRIBUTES);
        String[] attrs = resourceAttrs.split(";");
        for (String attr : attrs) {
            String[] values = attr.split(":");
            if (values.length == 2) {
                String key = values[0].toLowerCase().trim();
                String val = values[1].toLowerCase().trim();
                if (key.startsWith("cpu")) {
                    key = "cpus";
                } else if (key.startsWith("mem")) {
                    key = "mem";
                }
                Pattern pattern = Pattern.compile("(^\\d+)");
                Matcher matcher = pattern.matcher(val);
                if (matcher.find()) {
                    val = matcher.group(1);
                    resourceAttributes.put(key, val);
                }
            }
        }
        return resourceAttributes;
    }

    /**
     * Start framework: parse the configured slave attributes, load the mesos native library
     * and register the framework info to mesos master.
     */
    public void startFramework() {
        String principal = config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_IDENTITY);
        String secret = config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_CREDENTIAL);

        // Attention: the user should be existed in the slave system, if not, the executor in slave will throw exception. If it
        // is not set (empty/blank), the default executor will use the current system user as the user.
        FrameworkInfo frameworkInfo = FrameworkInfo.newBuilder()
                .setName(frameworkName)
                .setUser("")
                .build();


        if (principal != null && secret != null) {
            LOG.info("principle {} and secret {} is provided, connect to MESOS master {} with credential", new Object[]{principal, secret, master});
            Credential credential = Credential.newBuilder()
                    .setPrincipal(principal)
                    .setSecret(ByteString.copyFromUtf8(secret))
                    .build();

            driver = new MesosSchedulerDriver(this, frameworkInfo, master, credential);
        } else {
            LOG.info("principle and secret are not provided, connect to MESOS master {} without credential", master);
            driver = new MesosSchedulerDriver(this, frameworkInfo, master);
        }

        Status runStatus = driver.run();

        if (runStatus != Status.DRIVER_STOPPED) {
            LOG.info("The Mesos driver was aborted! Status code: " + runStatus.getNumber());
        }

        LOG.info("nGrinder framework is stopped");
    }

    /**
     * Match the offer's attributes and configured resourceAttributes in system config.
     *
     * @param offer offer
     * @return true or false
     */
    private boolean isMatching(Offer offer) {
        boolean slaveTypeMatch = true;
        if (resourceAttributes.size() == 0) {
            return true;
        }
        // Get the offer's attribute
        Map<String, String> offerResAttrMap = new ConcurrentHashMap<String, String>();
        for (Resource resource : offer.getResourcesList()) {
            String offerResAttrName = resource.getName();
            if (offerResAttrName.equals("cpus")) {
                if (resource.getType().equals(Value.Type.SCALAR)) {
                    String cpus = String.valueOf(resource.getScalar().getValue());
                    offerResAttrMap.put("cpus", cpus);
                    LOG.info("CPUS: {}", cpus);
                }
            } else if (offerResAttrName.equals("mem")) {
                if (resource.getType().equals(Value.Type.SCALAR)) {
                    String mem = String.valueOf(resource.getScalar().getValue());
                    offerResAttrMap.put("mem", mem);
                    LOG.info("MEM: {}", mem);
                }
            }
        }

        for (Map.Entry<String, String> each : resourceAttributes.entrySet()) {
            String key = each.getKey();
            /*
             * If there is a single absent attribute then we should reject this offer.
			 * Attention: here, the matching is not equal. it focus on the resource quantity. for example,
			 * CPU, it focus's on core count.
			 */
            if (!offerResAttrMap.containsKey(key)) {
                slaveTypeMatch = false;
                break;
            } else {
                String offerValue = offerResAttrMap.get(key);
                String wantedValue = resourceAttributes.get(key);
                if (!(Double.valueOf(offerValue) >= Double.valueOf(wantedValue))) {
                    slaveTypeMatch = false;
                    break;
                }
            }
        }
        return slaveTypeMatch;
    }


    /**
     * Generate the task Id with the given prefix,
     *
     * @param prefix  prefix
     * @param slaveId the Id of the mesos slave
     * @return task Id
     */
    private TaskID getTaskId(String prefix, String slaveId) {
        return TaskID.newBuilder().setValue(prefix + "-" + slaveId).build();
    }

    /**
     * Create and launch the mesos task. The task includes the docker info.
     * Currently, one offer only run one task, all resources of offer is used by the task.
     *
     * @param offer offer
     * @return Protos.TaskID taskId
     */
    private List<Protos.TaskInfo> createTask(Offer offer) {
        List<Protos.OfferID> offerIDs = new ArrayList<Protos.OfferID>();
        List<Protos.TaskInfo> tasks = new ArrayList<Protos.TaskInfo>();
        offerIDs.add(offer.getId());
        TaskID taskId = getTaskId(frameworkName, offer.getSlaveId().getValue());
        LOG.info("Create new task, ID '{}'", taskId.getValue());

        CommandInfo.Builder commandBuilder = CommandInfo.newBuilder();
        commandBuilder.addArguments("-ch").addArguments(config.getControllerAdvertisedHost());
        commandBuilder.addArguments("-cp").addArguments(String.valueOf(config.getControllerPort()));
        commandBuilder.addArguments("-r").addArguments(config.getRegion());
        commandBuilder.addArguments("-hi").addArguments(offer.getSlaveId().getValue());
        commandBuilder.setShell(false);

        ContainerInfo.Builder containerInfoBuilder = ContainerInfo.newBuilder();
        containerInfoBuilder.setType(ContainerInfo.Type.DOCKER);

        ContainerInfo.DockerInfo.Builder dockerInfoBuider = ContainerInfo.DockerInfo.newBuilder();
        dockerInfoBuider.setImage(dockerImg);
        dockerInfoBuider.setNetwork(ContainerInfo.DockerInfo.Network.HOST);

        containerInfoBuilder.setDocker(dockerInfoBuider.build());

        TaskInfo.Builder taskBuilder =
                TaskInfo.newBuilder()
                        .setName("ngrinder-agent-" + taskId.getValue())
                        .setTaskId(taskId)
                        .setSlaveId(offer.getSlaveId());

		/*
         * Set resource configuration according slave attributes, disk and port resources are not cared here
		 */
        if (resourceAttributes.size() == 0) {
            for (Resource resource : offer.getResourcesList()) {
                taskBuilder.addResources(resource);
            }
        } else {
            for (String key : resourceAttributes.keySet()) {
                Double value = Double.valueOf(resourceAttributes.get(key));
                if (key.equals("cpus")) {
                    taskBuilder.addResources(Resource.newBuilder()
                            .setName("cpus")
                            .setType(Value.Type.SCALAR)
                            .setScalar(Value.Scalar.newBuilder().setValue(value)));
                }
                //Attention, memory unit is MB
                if (key.equals("mem")) {
                    taskBuilder.addResources(Resource.newBuilder()
                            .setName("mem")
                            .setType(Value.Type.SCALAR)
                            .setScalar(Value.Scalar.newBuilder().setValue(value)));
                }
            }
        }

        taskBuilder.setCommand(commandBuilder.build());
        taskBuilder.setContainer(containerInfoBuilder.build());

        TaskInfo task = taskBuilder.build();
        tasks.add(task);

        driver.launchTasks(offerIDs, tasks);
        return tasks;
    }

    /**
     * This function constructs the information used in web UI, each element should not be missed.
     * Here, the IPs is reused with aws solution, in this mesos solution, it means the slave ID.
     *
     * @param task status of the task
     * @return node information
     */
    private AutoScaleNode createAutoScaleNode(TaskStatus task) {
        AutoScaleNode node = new AutoScaleNode();
        node.setId(task.getTaskId().getValue());
        //Keep the content same with it on mesos master web UI
        node.setName("ngrinder-agent-" + task.getTaskId().getValue());
        node.setState(task.getState().name());
        List<String> ips = newArrayList();
        ips.add(task.getSlaveId().getValue());
        node.setIps(ips);
        return node;
    }

    /**
     * Set the default timer for synchronization between activateNodes and resourceOffer,unit is MINUTE
     *
     * @return timer for CountDownLatch to wait before the count becomes 0
     */
    protected int getLatchTimer() {
        return 5;
    }

    @Override
    public void activateNodes(int count) throws AgentAutoScaleService.NotSufficientAvailableNodeException {
        LOG.info("Activate node function called: {}", count);
        if (getActivatableNodeCount() < count) {
            LOG.warn("{} node activation is requested. But only {} free nodes are available now. The activation is canceled.",
                    count, getActivatableNodeCount());
            return;
        }
        // reactivate offer listening.
        driver.reviveOffers();
        latch = new CountDownLatch(count);
        try {
            //The timer can not be too shorter, else the latch will be null in resourceOffer thread..
            latch.await(getLatchTimer(), TimeUnit.MINUTES);
            //If during the given duration, there is no agent task created, treat this condition as there is no resource can be used.
            if (latch.getCount() == count) {
                throw new AgentAutoScaleService.NotSufficientAvailableNodeException(
                        String.format("%d node activation is requested. But no nodes are available.", count));
            }
        } catch (InterruptedException e) {
            LOG.warn("Activate node encounters with interruption... {}", e.getMessage());
        } finally {
            latch = null;
        }
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
                checkNotNull(node);
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
     * Stop the specified task to release resource. use the slave Id to get task ID, then kill task to release resource.
     *
     * @param nodeId here it is the slaveId expected, if it start with frameworkName, which means it is triggered from web
     */
    @Override
    public void stopNode(String nodeId) {
        TaskID taskId;
        if (nodeId.startsWith(frameworkName)) {
            taskId = TaskID.newBuilder().setValue(nodeId).build();
        } else {
            taskId = getTaskId(frameworkName, nodeId);
        }
        LOG.info("Task {} is stopped...", taskId.getValue());

        driver.killTask(taskId);
    }

    /**
     * The main process in this function includes:
     * 1. match the attributes. If offers' attributes include the configuring resourceAttributes, the offer can be used.
     * 2. read the first request from the requests. create the task. store the task ID into results.
     * 3. calculate mesosMaxAgentCount. It means all matched offers except being used.
     *
     * @param driver MesosSchedulerDriver
     * @param offers offer list
     */
    @Override
    public void resourceOffers(SchedulerDriver driver, List<Protos.Offer> offers) {
        for (Offer each : offers) {
            final AutoScaleNode node = nodeCache.getIfPresent(each.getSlaveId().getValue());
            /*
             *Attention: the required offer count maybe less than the received available offers, after create one task,
			 *the latch should execute count down, if it is null, which means the left offer is more than required.
			 */
            if (node == null && isMatching(each) && latch != null && latch.getCount() != 0) {
                createTask(each);
                latch.countDown();
            } else {
                //This slave machine is running one agent already, or this offer is not matched. Don't send us again in 10 mins.
                Filters filters = Filters.newBuilder().setRefuseSeconds(10 * 60).build();
                driver.declineOffer(each.getId(), filters);
                LOG.info("Decline the not matching (required) offer from slave: {}", each.getSlaveId().getValue());
            }
        }
    }

    @Override
    public void statusUpdate(SchedulerDriver driver, final TaskStatus status) {
        final TaskState taskState = status.getState();
        final SlaveID slaveId = status.getSlaveId();
        switch (taskState) {
            case TASK_LOST:
            case TASK_ERROR:
            case TASK_FAILED:
            case TASK_FINISHED:
            case TASK_KILLED:
                nodeCache.invalidate(slaveId.getValue());
                break;
            default:
                nodeCache.put(status.getSlaveId().getValue(), createAutoScaleNode(status));
        }
        LOG.info("Received state update, task id = {}, task state = {}", status.getTaskId().getValue(), status.getState().name());
    }

    @Override
    public void registered(SchedulerDriver driver, FrameworkID frameworkId, MasterInfo masterInfo) {
        LOG.info("Framework registered! ID = {}", frameworkId.getValue());
    }

    @Override
    public void reregistered(SchedulerDriver driver, MasterInfo masterInfo) {
        LOG.debug("Framework re-registered");
    }

    @Override
    public void offerRescinded(SchedulerDriver driver, OfferID offerId) {
        LOG.debug("Rescinded offer {}", offerId.getValue());
    }

    @Override
    public void frameworkMessage(SchedulerDriver driver, ExecutorID executorId,
                                 SlaveID slaveId, byte[] data) {
        LOG.info("Received framework message {} from executor {} of slave {}", new Object[]{new String(data), executorId.getValue(), slaveId.getValue()});
    }

    @Override
    public void disconnected(SchedulerDriver driver) {
        LOG.info("Framework disconnected!");
    }

    @Override
    public void slaveLost(SchedulerDriver driver, SlaveID slaveId) {
        LOG.info("Slave {} lost!", slaveId.getValue());
        nodeCache.invalidate(slaveId.getValue());
    }

    @Override
    public void executorLost(SchedulerDriver driver, ExecutorID executorId,
                             SlaveID slaveId, int status) {
        LOG.info("Executor {} of slave {} lost!", executorId.getValue(), slaveId.getValue());
        nodeCache.invalidate(slaveId.getValue());
    }

    @Override
    public void error(SchedulerDriver driver, String message) {
        LOG.error(message);
    }

    /*
     * Test purpose
     */
    protected void init(Config config, ScheduledTaskService scheduledTaskService, MesosSchedulerDriver dr) {
        init(config, scheduledTaskService);
        driver = dr;
    }
}
