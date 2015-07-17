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
package org.ngrinder.perftest.service;

import com.google.common.base.Charsets;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.google.inject.Module;
import freemarker.template.*;
import org.jclouds.ContextBuilder;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.RunScriptOnNodesException;
import org.jclouds.compute.domain.*;
import org.jclouds.compute.domain.NodeMetadata.Status;
import org.jclouds.compute.domain.Template;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.ec2.domain.InstanceType;
import org.jclouds.enterprise.config.EnterpriseConfigurationModule;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.scriptbuilder.domain.Statement;
import org.jclouds.scriptbuilder.statements.login.AdminAccess;
import org.jclouds.scriptbuilder.statements.login.AdminAccessBuilderSpec;
import org.jclouds.sshj.config.SshjSshClientModule;
import org.ngrinder.common.util.ExceptionUtils;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.schedule.ScheduledTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static com.google.common.collect.Sets.newHashSet;
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_AMI_QUERY;
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_CC_AMI_QUERY;
import static org.jclouds.compute.config.ComputeServiceProperties.TIMEOUT_SCRIPT_COMPLETE;
import static org.jclouds.compute.options.TemplateOptions.Builder.overrideLoginCredentials;
import static org.jclouds.compute.predicates.NodePredicates.RUNNING;
import static org.jclouds.compute.predicates.NodePredicates.SUSPENDED;
import static org.jclouds.ec2.compute.options.EC2TemplateOptions.Builder.runScript;
import static org.ngrinder.common.util.ExceptionUtils.processException;

/**
 * Dynamic Agent Provisioning Handler.
 * <p/>
 * This class involves the JClouds API to create node groups which may contain number of instances (e.g. EC2 VM).
 * And, use script to do some required operation about docker image installation and startup. The docker image is
 * from github by default (e.g. $ docker pull ngrinder/agent:3.3).
 * <p/>
 * The agent downloading and starting is done by the agent docker image when docker daemon to run the docker
 * image pulled from github.
 * <p/>
 * DO NOT use root to execute this ngrinder if want to use dynamic agent provisioning.
 * <p/>
 * The operation in the script is as below:
 * <ul>
 * <li>Add node to group</li>
 * <li>Turn off all the nodes in group</li>
 * <li>Turn on all the nodes in group</li>
 * <li>Destroy all the nodes in group</li>
 * </ul>
 *
 * @author shihuc
 * @since 3.4
 */
@Profile("production")
@Component("dynamicAgent")
public class DynamicAgentHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicAgentHandler.class);

    @Autowired
    private Config config;

    @Autowired
    private ScheduledTaskService scheduledTaskService;

    private ComputeService compute = null;
    /*
     * This is a flag which is used to sync the operation in this bean and perfTestRunnable. Because this
     * dynamic agent feature has two cases: one is enabled when ngrinder controller to start up, the other
     * case is to enable this feature after ngrinder controller start up. Both cases, controller have to
     * know the existing nodes under the specified group name used by JClouds. To avoid data mismatch, in
     * case one, then permit to do list nodes again. For case two, should do list nodes before to run test
     * case.
     */
    private AtomicBoolean initStartedFlag = new AtomicBoolean(false);

    /*
     * This flag is used to sync the web UI about the current existing node information, only list operation
     * is done, the running or stopped node count can be known.
     */
    private boolean isListInfoDone = false;
    public boolean getIsListInfoDone(){
        return this.isListInfoDone;
    }

    /*
     * <PerfTest identifier, <EC2 instance private IP, time stamp when instance is created>
     *
     * Define map to record the new added node list, which will assist to sync the status whether
     * the agent in docker image is ready or not. And, if during the given time, if the agent running
     * in the docker container with the private IP of the created EC2 instance does not appear in the
     * approved agent list, treat this status as that the wanted new agent meets problem.
     */
    private Map<String, Map<String, Long>> testIdEc2NodeStatusMap = newHashMap();
    public final static String KEY_FOR_STARTUP = "CONTROLLER_STARTUP";

    /**
     * Get the node status map with the specified test identifier, the map contains the timestamp when
     * EC2 node becomes running status, the node ID is the map key.
     *
     * @param testIdentifier the test identifier
     * @return node status map data
     */
    public Map<String, Long> getTestIdEc2NodeStatusMap(String testIdentifier) {
        return testIdEc2NodeStatusMap.get(testIdentifier);
    }

    /**
     * Initialize the node status map for the specified test identifier
     *
     * @param testIdentifier the test identifier
     */
    public void setTestIdEc2NodeStatusMap(String testIdentifier) {
        Map<String, Long> newAddedNodeIpUpTimeMap = newHashMap();
        testIdEc2NodeStatusMap.put(testIdentifier, newAddedNodeIpUpTimeMap);
    }

    public void removeItemInTestIdEc2NodeStatusMap(String testIdentifier) {
        testIdEc2NodeStatusMap.remove(testIdentifier);
    }

    /**
     * Check whether there is test case which is not finished, if there is, then even if the guard time is
     * expired, do not turn off the created EC2 instance.
     *
     * @return true, there is test case which is not finished; false, all case finished test
     */
    public boolean hasRunningTestInTestIdEc2NodeStatusMap() {
        int testCount = 0;
        for (String id : testIdEc2NodeStatusMap.keySet()) {
            if (!id.equalsIgnoreCase(KEY_FOR_STARTUP)) {
                testCount++;
            }
        }
        return testCount > 0;
    }

    /*
     * The time duration to monitor the agent in docker container to be up. (if it is up, it will appear
     * in the approved agent list, if the timer expires, and the agent with the private IP of the new
     * created EC2 node does not appear, to allow to add new EC2 if possible)
     *
     * This timer unit is in millisecond (30 minutes). the time gap depends on the network speed, because
     * it will cost time to download agent from ngrinder controller after the new created EC2 instance to
     * run docker container.
     * Note: this timer should be greater than the dynamic agent guard time defined in system.conf
     */
    public boolean isTimeoutOfAgentRunningUp(long timeStamp) {
        long monitoringAgentUpTimeThreshold = 30 * 60 * 1000;
        long current = System.currentTimeMillis();
        return (current - timeStamp) > monitoringAgentUpTimeThreshold;
    }

    /*
     * Record the total count of EC2 nodes added to the specified group
     */
    private AtomicInteger addedNodeCount = new AtomicInteger(0);

    public int getAddedNodeCount() {
        return this.addedNodeCount.get();
    }

    private Map<String, String> runningNodeMap = newHashMap();
    private Map<String, String> stoppedNodeMap = newHashMap();

    public int getStoppedNodeCount() {
        return stoppedNodeMap.size();
    }

    public int getRunningNodeCount() {
        return runningNodeMap.size();
    }

    private Set<String> turningOnSet = newHashSet();

    public int getTurningOnSetCount() {
        return turningOnSet.size();
    }

    public String getNodeIdByPrivateIp(String ip) {
        for (String id : runningNodeMap.keySet()) {
            if (ip.equalsIgnoreCase(runningNodeMap.get(id))) {
                return id;
            }
        }
        return null;
    }


    public enum Action {
        ADD , ON , OFF, DESTROY, LIST
    }

    private LoginCredentials addOnOffLogin = null;

    /**
     * In order to ensure the group name is unique, use the controller IP as seed to generate the group name.
     * group name format: "agt" as the prefix, and use IP removed dot as suffix.
     *
     * @return group name
     */
    private String generateUniqueGroupName() {
        String groupName = "agt";
        String ctrl_ip = config.getAgentDynamicControllerIP();
        ctrl_ip = ctrl_ip.replaceAll("\\.", "d");
        return groupName + ctrl_ip;
    }

    @PostConstruct
    public void init() {
        testIdEc2NodeStatusMap = Collections.synchronizedMap(testIdEc2NodeStatusMap);
        runningNodeMap = Collections.synchronizedMap(runningNodeMap);
        stoppedNodeMap = Collections.synchronizedMap(stoppedNodeMap);
        turningOnSet = Collections.synchronizedSet(turningOnSet);

        addOnOffLogin = getLoginCredential();

        config.addSystemConfListener(initDynamicAgentEnvListener());

        initStartAndEndEnvironment();
    }

    @PreDestroy
    public void destroy() {
        LOG.info("compute service context is closed when bean destroy...");
        if(compute != null){
            compute.getContext().close();
        }
    }

    private void initStartAndEndEnvironment() {
        initDynamicAgentNodeEnvironment();
        registerShutdownHook();
    }

    private PropertyChangeListener initDynamicAgentEnvListener(){
        return new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                initDynamicAgentNodeEnvironment();
            }
        };
    }

    private void initDynamicAgentNodeEnvironment() {
        if (config.isAgentDynamicEc2Enabled()) {

            //If the EC2 node initialization is not done, before the first case to test, should do list operation to
            //get the existing node information.
            if (!initStartedFlag.get()) {

                initStartedFlag.getAndSet(true);
                LOG.info("Begin to list the existing node information...");

                Runnable listRunnable = new Runnable() {
                    @Override
                    public void run() {
                        compute = getComputeService();
                        initFirstOneEc2Instance();
                    }
                };
                scheduledTaskService.runAsync(listRunnable);
            }
        }
    }

    /**
     * When ngrinder controller startup, to initialize the EC2 instance, it maybe turn on the stopped node,
     * or to add one EC2 node. if there is node is running, do nothing.
     */
    public void initFirstOneEc2Instance() {
        doListEc2NodeInfo();
        if (runningNodeMap.isEmpty() && stoppedNodeMap.isEmpty()) { //no running and stopped node
            List<String> nodeIdList = newArrayList();
            nodeIdList.add("" + 1);
            dynamicAgentCommand("add", KEY_FOR_STARTUP, nodeIdList);
        } else if (runningNodeMap.isEmpty()) {                      //no running node but there is stopped node
            List<String> nodeIdList = newArrayList();
            prepareNodeIdList(1, nodeIdList, stoppedNodeMap);
            syncNodeIdFromStoppedToTurningOn(nodeIdList);
            dynamicAgentCommand("on", KEY_FOR_STARTUP, nodeIdList);
        }
    }

    private void doListEc2NodeInfo() {
        setTestIdEc2NodeStatusMap(KEY_FOR_STARTUP);
        dynamicAgentCommand("list", KEY_FOR_STARTUP, null);
    }

    /**
     * If there is not enough agent to use, and no enough stopped node to turn on, then to do add
     * new EC2 node for this test
     *
     * @param testIdentifier the test identifier
     * @param requiredNum    the required agent count
     */
    public void addDynamicEc2Instance(String testIdentifier, int requiredNum) {
        setTestIdEc2NodeStatusMap(testIdentifier);
        List<String> nodeIdList = newArrayList();
        //Here, the content for nodeIdList has no meaning
        for (int i = 0; i < requiredNum; i++) {
            nodeIdList.add("" + i);
        }
        dynamicAgentCommand("add", testIdentifier, nodeIdList);
    }

    /**
     * Terminate the EC2 instances for timeout or when ngrinder controller exit
     *
     * @param nodeIdList the node ID list which will indicate which node should be terminated
     */
    public void terminateEc2Instance(List<String> nodeIdList) {
        //setProviderIdCredentialForEc2();
        dynamicAgentCommand("destroy", "", nodeIdList);
    }

    /**
     * If there is new test case is ready, then to turn on the stopped node if there is enough.
     *
     * @param testIdentifier the test identifier
     * @param requiredNum    the node count implies how many node should be turned on
     */
    public void turnOnEc2Instance(String testIdentifier, int requiredNum) {
        if (stoppedNodeMap.size() >= requiredNum) {
            setTestIdEc2NodeStatusMap(testIdentifier);
            List<String> nodeIdList = newArrayList();
            prepareNodeIdList(requiredNum, nodeIdList, stoppedNodeMap);
            syncNodeIdFromStoppedToTurningOn(nodeIdList);
            dynamicAgentCommand("on", testIdentifier, nodeIdList);
        }
    }

    private void syncNodeIdFromStoppedToTurningOn(List<String> nodeIdList) {
        for (String id : nodeIdList) {
            turningOnSet.add(id);
        }
    }

    private void prepareNodeIdList(int requiredNum, List<String> nodeIdList, Map<String, String> map) {
        int cnt = 0;
        LOG.info("need to prepare {} node id list", requiredNum);
        for (String id : map.keySet()) {
            if (turningOnSet.contains(id)) {
                continue;
            }
            nodeIdList.add(id);
            LOG.info("node ID: {}", id);
            cnt++;
            if (cnt == requiredNum) {
                break;
            }
        }
    }

    /**
     * Turn off the EC2 instance when the guard time expired, to save cost.
     */
    public void turnOffEc2Instance() {
        List<String> runningNodes = newArrayList();
        for (String id : runningNodeMap.keySet()) {
            runningNodes.add(id);
        }
        dynamicAgentCommand("off", "", runningNodes);
    }

    private void registerShutdownHook() {
        Thread thread = new Thread() {
            @Override
            public void run() {
                if (config.isAgentDynamicEc2Enabled()) {
                    LOG.info("dynamicAgent is destroyed via shutdown hook....");
                    List<String> termList = newArrayList();
                    for (String id : runningNodeMap.keySet()) {
                        termList.add(id);
                    }
                    for (String id : stoppedNodeMap.keySet()) {
                        termList.add(id);
                    }
                    terminateEc2Instance(termList);
                    compute.getContext().close();
                }
            }
        };
        LOG.info("Register shutdown hook to destroy the created EC2 instance when controller daemon shut down...");
        Runtime.getRuntime().addShutdownHook(thread);
    }

    private String getEnvToGenerateScript(String cmd) {
        String dockerImageRepo = config.getAgentDynamicDockerRepo();
        String dockerImageTag = config.getAgentDynamicDockerTag();
        String controllerIP = config.getAgentDynamicControllerIP();
        String controllerPort = config.getAgentDynamicControllerPort();

        return generateScriptBasedOnTemplate(controllerIP, controllerPort, dockerImageRepo, dockerImageTag, cmd);
    }

    protected Predicate<ComputeMetadata> nodeNameStartsWith(final String nodeNamePrefix) {
        checkNotNull(nodeNamePrefix, "reasonable node name prefix must be provided");
        return new Predicate<ComputeMetadata>() {
            @Override
            public boolean apply(ComputeMetadata computeMetadata) {
                String nodeName = computeMetadata.getName();
                return nodeName != null && nodeName.startsWith(nodeNamePrefix);
            }

            @Override
            public String toString() {
                return "nodeNameStartsWith(" + nodeNamePrefix + ")";
            }
        };
    }

    protected Predicate<NodeMetadata> inGivenList(final List<String> givenList) {
        checkNotNull(givenList, "reasonable given list must be provided");
        return new Predicate<NodeMetadata>() {
            @Override
            public boolean apply(NodeMetadata nodeMetadata) {
                return givenList.contains(nodeMetadata.getId());
            }

            @Override
            public String toString() {
                return "inGivenList(" + givenList + ")";
            }
        };
    }

    private String getPrueIpString(String tip) {
        String ip = tip.replace("[", "");
        ip = ip.replace("]", "");
        LOG.info("tip: {}, ip: {}", tip, ip);
        return ip;
    }

    protected ComputeService initComputeService(String provider, String identity, String credential) {

        // specific properties, in this case optimizing image list to only amazon supplied
        Properties properties = new Properties();
        properties.setProperty(PROPERTY_EC2_AMI_QUERY, "owner-id=137112412989;state=available;image-type=machine");
        properties.setProperty(PROPERTY_EC2_CC_AMI_QUERY, "");
        long scriptTimeout = TimeUnit.MILLISECONDS.convert(20, TimeUnit.MINUTES);
        properties.setProperty(TIMEOUT_SCRIPT_COMPLETE, scriptTimeout + "");

        // inject a ssh implementation
        Iterable<Module> modules = ImmutableSet.<Module>of(
                new SshjSshClientModule(),
                new SLF4JLoggingModule(),
                new EnterpriseConfigurationModule());

        ContextBuilder builder = ContextBuilder.newBuilder(provider)
                .credentials(identity, credential)
                .modules(modules)
                .overrides(properties);

        LOG.info(">> initializing {}", builder.getApiMetadata());

        return builder.buildView(ComputeServiceContext.class).getComputeService();
    }

    class CommandContext{
        private CommandHandler cmdHandler;

        CommandContext(CommandHandler cmdHandler){
            this.cmdHandler = cmdHandler;
        }

        void takeCommandAction(){
            this.cmdHandler.takeAction();
        }
    }

    abstract class CommandHandler {
        protected ComputeService compute = null;
        protected File scriptFile = null;
        protected List<String> nodeIdList = null;
        protected String groupName = generateUniqueGroupName();
        protected String scriptName = null;
        protected Map<String, Long> newAddedNodeMap = null;

        void takeAction() {}
    }

    class AddHandler extends CommandHandler {

        protected AddHandler(ComputeService compute, File scriptFile, Map<String, Long> newAddedNodeMap,  List<String> nodeList){
            this.compute = compute;
            this.scriptName = scriptFile.getName();
            this.scriptFile = scriptFile;
            this.newAddedNodeMap = newAddedNodeMap;
            this.nodeIdList = nodeList;
        }

        @Override
        void takeAction(){

            checkNotNull(addOnOffLogin, "login is invalid, check user home and ssh path or ssh key whether they are existing");
            checkNotNull(nodeIdList, "should provide node count to create via jclouds");
            LOG.info(">> prepare to add {} node to group {}", nodeIdList.size(), groupName);

            TemplateBuilder templateBuilder = compute.templateBuilder()
                    .locationId("ap-southeast-1").hardwareId(InstanceType.M1_MEDIUM);

            Statement bootInstructions = createAdminAccess();
            templateBuilder.options(runScript(bootInstructions));
            Template template = templateBuilder.build();

            LOG.info(">> begin to create {} in group {}", nodeIdList.size(), groupName);
            addedNodeCount.getAndAdd(nodeIdList.size());
            List<String> addList = newArrayList();
            Set<? extends NodeMetadata> nodes;
            try {
                nodes = compute.createNodesInGroup(groupName, nodeIdList.size(), template);
            } catch (RunNodesException e) {
                LOG.debug(e.getMessage());
                throw ExceptionUtils.processException("RunNodesException: Create ec2 node error");
            }

            long addTimeStamp = System.currentTimeMillis();
            for (NodeMetadata node : nodes) {
                String id = node.getId();
                String ip = getPrueIpString(node.getPrivateAddresses().toString());
                LOG.info("<< added node: {} {}", id, concat(node.getPrivateAddresses(), node.getPublicAddresses()));
                addList.add(id);
                runningNodeMap.put(id, ip);
                newAddedNodeMap.put(ip, addTimeStamp);
            }

            LOG.info(">> exec {} to initialize nodes as {}", scriptName, addOnOffLogin.identity);
            Map<? extends NodeMetadata, ExecResponse> responseRun;
            try {
                responseRun = compute.runScriptOnNodesMatching(
                        inGivenList(addList), Files.toString(scriptFile, Charsets.UTF_8),
                        overrideLoginCredentials(addOnOffLogin).runAsRoot(false)
                                .nameTask("_" + scriptFile.getName().replaceAll("\\..*", "")));
            } catch (RunScriptOnNodesException e) {
                LOG.debug(e.getMessage());
                throw ExceptionUtils.processException("RunScriptOnNodesException: Run script on nodes error");
            } catch (IOException e) {
                LOG.debug(e.getMessage());
                throw ExceptionUtils.processException("IOException: File IO error");
            }

            for (Entry<? extends NodeMetadata, ExecResponse> response : responseRun.entrySet()) {
                LOG.info("<< {} status {}", response.getKey().getId(), response.getValue());
            }
        }
    }

    class OnHandler extends CommandHandler {

        protected OnHandler(ComputeService compute, File scriptFile, Map<String, Long> newAddedNodeMap,  List<String> nodeList){
            this.compute = compute;
            this.scriptName = scriptFile.getName();
            this.scriptFile = scriptFile;
            this.newAddedNodeMap = newAddedNodeMap;
            this.nodeIdList = nodeList;
        }

        @Override
        void takeAction(){
            checkNotNull(addOnOffLogin, "login is invalid, check user home and ssh path or ssh key whether they are existing");
            LOG.info(">> begin to turn on node(s) in group {} as {}", groupName, addOnOffLogin.identity);
            LOG.info(">> nodeIdList content: {}", nodeIdList);
            Set<? extends NodeMetadata> turnOn = compute.resumeNodesMatching(Predicates.and(SUSPENDED, inGivenList(nodeIdList)));
            long onTimeStamp = System.currentTimeMillis();
            for (NodeMetadata node : turnOn) {
                String id = node.getId();
                String ip = getPrueIpString(node.getPrivateAddresses().toString());
                LOG.info("<< turned on node: {}", id);
                runningNodeMap.put(id, ip);
                stoppedNodeMap.remove(id);
                newAddedNodeMap.put(ip, onTimeStamp);
            }

            LOG.info(">> exec {} to initialize nodes", scriptName);
            //after nodes are turned on, to start new docker container
            Map<? extends NodeMetadata, ExecResponse> turnOnRun;
            try {
                turnOnRun = compute.runScriptOnNodesMatching(
                        inGivenList(nodeIdList), Files.toString(scriptFile, Charsets.UTF_8), // passing in a string with the contents of the file
                        overrideLoginCredentials(addOnOffLogin).runAsRoot(false)
                                .nameTask("_" + scriptFile.getName().replaceAll("\\..*", "")));
            } catch (RunScriptOnNodesException e) {
                LOG.debug(e.getMessage());
                throw ExceptionUtils.processException("RunScriptOnNodesException: Run script on nodes error");
            } catch (IOException e) {
                LOG.debug(e.getMessage());
                throw ExceptionUtils.processException("IOException: File IO error");
            }

            for (Entry<? extends NodeMetadata, ExecResponse> response : turnOnRun.entrySet()) {
                LOG.info("<< initialized node {}: {}", response.getKey().getId(),
                        concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()));
                LOG.info("<< {}", response.getValue());
            }
        }
    }

    class OffHandler extends CommandHandler {

        protected OffHandler(ComputeService compute, File scriptFile, List<String> nodeList){
            this.compute = compute;
            this.scriptName = scriptFile.getName();
            this.scriptFile = scriptFile;
            this.nodeIdList = nodeList;
        }

        @Override
        void takeAction(){
            checkNotNull(addOnOffLogin, "login is invalid, check user home and ssh path or ssh key whether they are existing");
            //1. before to do turn off the VMs, do stop and remove docker container
            //2. turn off operation will suspend all the nodes in the given group
            LOG.info(">> exec {} as {} ", scriptName, addOnOffLogin.identity);
            Map<? extends NodeMetadata, ExecResponse> stopAndRemove;
            try {
                stopAndRemove = compute.runScriptOnNodesMatching(
                        inGivenList(nodeIdList),
                        Files.toString(scriptFile, Charsets.UTF_8),   // passing in a string with the contents of the file
                        overrideLoginCredentials(addOnOffLogin).runAsRoot(false)
                                .nameTask("_" + scriptFile.getName().replaceAll("\\..*", "")));
            } catch (RunScriptOnNodesException e) {
                LOG.debug(e.getMessage());
                throw ExceptionUtils.processException("RunScriptOnNodesException: Run script on nodes error");
            } catch (IOException e) {
                LOG.debug(e.getMessage());
                throw ExceptionUtils.processException("IOException: File IO error");
            }

            for (Entry<? extends NodeMetadata, ExecResponse> response : stopAndRemove.entrySet()) {
                String id = response.getKey().getId();
                LOG.info("<< node: {} {}", id, concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()));
                LOG.info("<< stop and remove status: {}", response.getValue());
            }

            // you can use predicates to select which nodes you wish to turn off.
            LOG.info(">> begin to turn off nodes in group {}", groupName);
            Set<? extends NodeMetadata> turnOff = compute.suspendNodesMatching(Predicates.and(RUNNING, inGivenList(nodeIdList)));
            for (NodeMetadata node : turnOff) {
                String id = node.getId();
                String tip = node.getPrivateAddresses().toString();
                LOG.info("<< turn off node {}", node);
                stoppedNodeMap.put(id, getPrueIpString(tip));
                runningNodeMap.remove(id);
            }
            turningOnSet.clear();
        }
    }

    class ListHandler extends CommandHandler {
        protected ListHandler(ComputeService compute, Map<String, Long> newAddedNodeMap){
            this.compute = compute;
            this.newAddedNodeMap = newAddedNodeMap;
        }

        @Override
        void takeAction(){
            LOG.info(">> begin to list nodes status in group {}", groupName);
            Set<? extends NodeMetadata> gnodes = compute.listNodesDetailsMatching(nodeNameStartsWith(groupName));
            LOG.info(">> total number nodes/instances {} in group {}", gnodes.size(), groupName);
            long listTimeStamp = System.currentTimeMillis();
            for (NodeMetadata nodeData : gnodes) {
                LOG.info("    >> " + nodeData);
                Status status = nodeData.getStatus();
                String ip = getPrueIpString(nodeData.getPrivateAddresses().toString());
                if (status == Status.RUNNING) {
                    runningNodeMap.put(nodeData.getId(), ip);
                    newAddedNodeMap.put(ip, listTimeStamp);
                } else if (status == Status.SUSPENDED) {
                    stoppedNodeMap.put(nodeData.getId(), ip);
                }
            }
            addedNodeCount.getAndSet(runningNodeMap.size() + stoppedNodeMap.size());
            isListInfoDone = true;
            LOG.info(">> total number available {} nodes in group {}", addedNodeCount.get(), groupName);
        }
    }

    class DestroyHandler extends CommandHandler {
        protected DestroyHandler(ComputeService compute, List<String> nodeList){
            this.compute = compute;
            this.nodeIdList = nodeList;
        }

        @Override
        void takeAction(){
            checkNotNull(nodeIdList, "which node(s) will be terminated should be specified");
            LOG.info(">> destroy {} nodes in group {}", nodeIdList.size(), groupName);
            // you can use predicates to select which nodes you wish to destroy.
            Set<? extends NodeMetadata> destroyed = compute.destroyNodesMatching(inGivenList(nodeIdList));
            for (NodeMetadata node : destroyed) {
                String id = node.getId();
                runningNodeMap.remove(id);
                stoppedNodeMap.remove(id);
                turningOnSet.remove(id);
                addedNodeCount.getAndDecrement();
                LOG.info("<< destroyed node: {}", node);
            }
            LOG.info("<< nodes are destroyed... ");
        }
    }

    /**
     * Major operation, according to the action which is enum value.
     *
     * @param actionCmd      action command name
     * @param testIdentifier the test identifier     *
     * @param nodeIdList     the IDs which mapped to the node to be operated indicated by actionCmd
     *                       When actionCmd is to turn off or list, nodeIdList is null
     *                       When actionCmd is to destroy, it maybe null which means destroy all nodes,
     *                       it also can be not null, which will indicate which nodes to be destroyed
     */
    public void dynamicAgentCommand(String actionCmd, String testIdentifier, List<String> nodeIdList) {

        //If ComputeService compute is not initialized, then can not to operate EC2 via JClouds API.
        ComputeService compute = getComputeService();
        checkNotNull(compute, "compute service must be created before do operation");

        String cnt;
        if (nodeIdList == null) {
            cnt = " all nodes";
        } else {
            cnt = String.valueOf(nodeIdList.size());
        }
        LOG.info("action: " + actionCmd + ", test id: " + testIdentifier + ", count: " + cnt);

        Action action = Action.valueOf(actionCmd.toUpperCase());

        File scriptFile = null;
        if (action == Action.ADD || action == Action.ON || action == Action.OFF) {
            String scriptName =  getEnvToGenerateScript(actionCmd);
            scriptFile = new File(scriptName);
        }

        Map<String, Long> newAddedNodeMap = testIdEc2NodeStatusMap.get(testIdentifier);
        if (action == Action.ADD || action == Action.ON || action == Action.LIST) {
            checkNotNull(newAddedNodeMap, "test identifier mapped node status map must be initialized");
        }

        CommandContext cmdContext;

        switch (action) {
            case ADD:
                cmdContext = new CommandContext(new AddHandler(compute, scriptFile, newAddedNodeMap, nodeIdList));
                cmdContext.takeCommandAction();
                break;

            case OFF:
                cmdContext = new CommandContext(new OffHandler(compute, scriptFile, nodeIdList));
                cmdContext.takeCommandAction();
                break;

            case ON:
                cmdContext = new CommandContext(new OnHandler(compute, scriptFile, newAddedNodeMap, nodeIdList));
                cmdContext.takeCommandAction();
                break;

            case DESTROY:
                cmdContext = new CommandContext(new DestroyHandler(compute, nodeIdList));
                cmdContext.takeCommandAction();
                break;

            case LIST:
                cmdContext = new CommandContext(new ListHandler(compute, newAddedNodeMap));
                cmdContext.takeCommandAction();
                break;

            default:
                break;
        }

    }

    private ComputeService getComputeService() {
        if(compute == null) {
            String identity = config.getAgentDynamicEc2Identity();
            String credential = config.getAgentDynamicEc2Credential();
            String type = config.getAgentDynamicType();

            checkNotNull(type, "cloud provider can not be null or empty");
            checkNotNull(credential, "credential can not be null or empty");
            checkNotNull(identity, "identity can not be null or empty");

            if (type.equalsIgnoreCase("EC2")) {
                return initComputeService("aws-ec2", identity, credential);
            } else {
                //think about the other provider, maybe...
                return null;
            }
        }
        return compute;
    }

    private LoginCredentials getAgentLoginForCommandExecution() {
        try {
            String user = "agent";
            File priFile = new File("/home/agent/.ssh/id_rsa");
            if (!priFile.exists()) {
                LOG.warn("private ssh key file id_rsa of user 'agent' is not existing.");
                return null;
            }
            String privateKey = Files.toString(priFile, UTF_8);
            return LoginCredentials.builder().user(user).privateKey(privateKey).build();
        } catch (Exception e) {
            LOG.debug("error reading ssh key {}", e.getMessage());
            return null;
        }
    }

    private LoginCredentials getUserLoginForCommandExecution() {
        try {
            String user = System.getProperty("user.name");
            String privateKey = Files.toString(
                    new File(System.getProperty("user.home"), "/.ssh/id_rsa"), UTF_8);
            return LoginCredentials.builder().
                    user(user).privateKey(privateKey).build();
        } catch (Exception e) {
            LOG.debug("error reading ssh key {}", e.getMessage());
            return null;
        }
    }

    /**
     * According to the current user to do different login operation. Because AdminAccess in jclouds does not allow
     * 'root' user to login the EC2 VM if the AMI is default from Amazon provider.
     *
     * @return login credential
     */
    private LoginCredentials getLoginCredential() {
        String user = System.getProperty("user.name");
        LoginCredentials tempLogin;
        if (user.equalsIgnoreCase("root")) {
            tempLogin = getAgentLoginForCommandExecution();
        } else {
            tempLogin = getUserLoginForCommandExecution();
        }
        return tempLogin;
    }

    /**
     * According to current user whether it is 'root' to do different behavior to create AdminAccess.
     * If current user is 'root', ngrinder user should create 'agent' user and generate RSA type ssh key
     * without passphrase.
     *
     * @return statement
     */
    protected Statement createAdminAccess() {
        String user = System.getProperty("user.name");
        Statement bootInstruction;
        if (user.equalsIgnoreCase("root")) {
            File pubFile = new File("/home/agent/.ssh/id_rsa.pub");
            File priFile = new File("/home/agent/.ssh/id_rsa");
            if (!pubFile.exists()) {
                LOG.warn("public ssh key file id_rsa.pub of user 'agent' not exist");
                return null;
            }
            if (!priFile.exists()) {
                LOG.warn("private ssh key file id_rsa of user 'agent' not exist");
                return null;
            }
            /*
             * Attention: public and private keys both should be provided else AdminAccess will use the default ssh keys.
             *            please refer to the scenario of public AdminAccess init(Configuration configuration) {...}
             */
            AdminAccessBuilderSpec spec = AdminAccessBuilderSpec.parse(
                    "adminUsername=agent,"
                            + "adminHome=/home/agent,"
                            + "adminPublicKeyFile=/home/agent/.ssh/id_rsa.pub,"
                            + "adminPrivateKeyFile=/home/agent/.ssh/id_rsa");
            bootInstruction = AdminAccess.builder().from(spec).build();
        } else {
            String home = System.getProperty("user.home");
            File pubFile = new File(home, "/.ssh/id_rsa.pub");
            File priFile = new File(home, "/.ssh/id_rsa");
            if (!pubFile.exists()) {
                LOG.warn("public ssh key file id_rsa.pub of user '{}' not exist", user);
                return null;
            }
            if (!priFile.exists()) {
                LOG.warn("private ssh key file id_rsa of user '{}' not exist", user);
                return null;
            }
            bootInstruction = AdminAccess.standard();
        }
        return bootInstruction;
    }

    /**
     * Get the initial script with the given value map for operation EC2 node.
     *
     * @param values map of initial script referencing values.
     * @param cmd the operation name which maybe add, on and off
     * @return String the file name of the generated script.
     */
    public String getShellScriptViaTemplate(Map<String, Object> values, String cmd) {
        try {
            String newFileName;
            if (cmd.equalsIgnoreCase("add")) {
                newFileName = "add.sh";
            } else if (cmd.equalsIgnoreCase("on")) {
                newFileName = "on.sh";
            } else if (cmd.equalsIgnoreCase("off")) {
                newFileName = "off.sh";
            } else {
                throw processException("Error while fetching the script template since bad command.");
            }

            Configuration freemarkerConfig = new Configuration();
            ClassPathResource cpr = new ClassPathResource("agent_dynamic_provision_script");
            freemarkerConfig.setDirectoryForTemplateLoading(cpr.getFile());
            freemarkerConfig.setObjectWrapper(new DefaultObjectWrapper());
            freemarker.template.Template template = freemarkerConfig.getTemplate("jclouds_op_ec2_template.sh");
            StringWriter writer = new StringWriter();

            template.process(values, writer);

            String scriptName = cpr.getFile() + "/" + newFileName;
            FileWriter fw = new FileWriter(new File(scriptName));
            BufferedWriter bw = new BufferedWriter(fw);
            bw.write(writer.toString());
            bw.close();
            fw.close();
            return scriptName;
        } catch (Exception e) {
            throw processException("Error while fetching the script template.", e);
        }
    }

    /**
     * Script file generator based on the script template
     *
     * @param ctrl_IP,           ngrinder controller IP
     * @param ctrl_port,         ngrinder controller PORT
     * @param agent_docker_repo, the docker image repository name
     * @param agent_docker_tag,  the docker image tag
     * @param cmd,               the operation command, such as ADD, ON, OFF
     * @return String script file name
     */
    private String generateScriptBasedOnTemplate(String ctrl_IP, String ctrl_port, String agent_docker_repo,
                                               String agent_docker_tag, String cmd) {

        Map<String, Object> values = newHashMap();
        values.put("agent_controller_ip", ctrl_IP);
        values.put("agent_controller_port", ctrl_port);
        values.put("agent_image_repo", agent_docker_repo);
        values.put("agent_image_tag", agent_docker_tag);
        values.put("agent_work_mode", cmd.toUpperCase());
        return getShellScriptViaTemplate(values, cmd);
    }
}
