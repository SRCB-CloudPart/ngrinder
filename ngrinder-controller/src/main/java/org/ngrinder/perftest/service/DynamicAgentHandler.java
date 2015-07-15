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
import org.jclouds.ContextBuilder;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.RunScriptOnNodesException;
import org.jclouds.compute.domain.*;
import org.jclouds.compute.domain.NodeMetadata.Status;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.ec2.domain.InstanceType;
import org.jclouds.enterprise.config.EnterpriseConfigurationModule;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.scriptbuilder.domain.Statement;
import org.jclouds.scriptbuilder.statements.login.AdminAccess;
import org.jclouds.scriptbuilder.statements.login.AdminAccessBuilderSpec;
import org.jclouds.sshj.config.SshjSshClientModule;
import org.ngrinder.infra.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
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

    /*
     * This is a flag which is used to sync the operation in this bean and perfTestRunnable. Because this
     * dynamic agent feature has two cases: one is enabled when ngrinder controller to start up, the other
     * case is to enable this feature after ngrinder controller start up. Both cases, controller have to
     * know the existing nodes under the specified group name used by JClouds. To avoid data mismatch, in
     * case one, then permit to do list nodes again. For case two, should do list nodes before to run test
     * case.
     */
    private static boolean initStartedFlag = false;

    public boolean getInitStartedFlag() {
        return initStartedFlag;
    }

    public void setInitStartedFlag(boolean started) {
        initStartedFlag = started;
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
        ADD, ON, OFF, DESTROY, LIST
    }

    private String provider = "aws-ec2";
    private String identity = null;
    private String credential = null;
    private String scriptTemplatePath = null;
    private String scriptName = "";
    private LoginCredentials addOnOffLogin = null;

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public void setIdentity(String identity) {
        this.identity = identity;
    }

    public void setCredential(String credential) {
        this.credential = credential;
    }

    public void setScriptName(String scriptName) {
        this.scriptName = this.scriptTemplatePath + "/" + scriptName;
    }

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
        initEnvironment();
    }

    private void initEnvironment() {
        String scriptTemplateFile = "/agent_dynamic_provision_script/jclouds_op_ec2_template.sh";
        ClassPathResource cpr = new ClassPathResource(scriptTemplateFile);
        try {
            this.scriptTemplatePath = cpr.getFile().getParent();
        } catch (IOException e) {
            LOG.info(e.getMessage());
        }

        registerShutdownHook();
    }

    private void setProviderIdCredentialForEc2() {
        String identity = config.getAgentDynamicEc2Identity();
        String credential = config.getAgentDynamicEc2Credential();
        setProvider("aws-ec2");
        setIdentity(identity);
        setCredential(credential);
    }

    /**
     * When ngrinder controller startup, to initialize the EC2 instance, it maybe turn on the stopped node,
     * or to add one EC2 node. if there is node is running, do nothing.
     */
    public void initFirstOneEc2Instance() {
        doListEc2NodeInfo();
        if (runningNodeMap.size() == 0 && stoppedNodeMap.size() == 0) {
            setScriptName("add.sh");
            List<String> nodeIdList = newArrayList();
            nodeIdList.add("" + 1);
            dynamicAgentCommand("add", KEY_FOR_STARTUP, nodeIdList);
        } else if (runningNodeMap.size() == 0 && stoppedNodeMap.size() > 0) {
            setScriptName("on.sh");
            List<String> nodeIdList = newArrayList();
            prepareNodeIdList(1, nodeIdList, stoppedNodeMap);
            syncNodeIdFromStoppedToTurningOn(nodeIdList);
            dynamicAgentCommand("on", KEY_FOR_STARTUP, nodeIdList);
        }
    }

    private void doListEc2NodeInfo() {
        setProviderIdCredentialForEc2();
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
        setProviderIdCredentialForEc2();
        setScriptName("add.sh");
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
            setProviderIdCredentialForEc2();
            setScriptName("on.sh");
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
        setProviderIdCredentialForEc2();
        setScriptName("off.sh");
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
                    List<String> termList = newArrayList();
                    for (String id : runningNodeMap.keySet()) {
                        termList.add(id);
                    }
                    for (String id : stoppedNodeMap.keySet()) {
                        termList.add(id);
                    }
                    terminateEc2Instance(termList);
                }
            }
        };
        LOG.info("Register shutdown hook to destroy the created EC2 instance when controller daemon shut down...");
        Runtime.getRuntime().addShutdownHook(thread);
    }

    private void getEnvToGenerateScript() {
        String dockerImageRepo = config.getAgentDynamicDockerRepo();
        String dockerImageTag = config.getAgentDynamicDockerTag();
        String controllerIP = config.getAgentDynamicControllerIP();
        String controllerPort = config.getAgentDynamicControllerPort();

        generateScriptBasedOnTemplate(controllerIP, controllerPort, dockerImageRepo, dockerImageTag, "add");
        generateScriptBasedOnTemplate(controllerIP, controllerPort, dockerImageRepo, dockerImageTag, "off");
        generateScriptBasedOnTemplate(controllerIP, controllerPort, dockerImageRepo, dockerImageTag, "on");
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

        String cnt;
        if (nodeIdList == null) {
            cnt = " all nodes";
        } else {
            cnt = String.valueOf(nodeIdList.size());
        }
        LOG.info("action: " + actionCmd + ", test id: " + testIdentifier + ", count: " + cnt);

        ComputeService compute = getComputeService();

        Action action = Action.valueOf(actionCmd.toUpperCase());

        File file = null;
        if (action == Action.ADD || action == Action.ON || action == Action.OFF) {
            checkNotNull(scriptName, "please pass the local file to run as the last parameter");
            file = new File(scriptName);
        }

        Map<String, Long> newAddedNodeMap = testIdEc2NodeStatusMap.get(testIdentifier);
        if (action == Action.ADD || action == Action.ON || action == Action.LIST) {
            checkNotNull(newAddedNodeMap, "test identifier mapped node status map must be initialized");
        }

        String groupName = generateUniqueGroupName();
        LOG.info("current system has {} EC2 nodes in group {}", addedNodeCount.get(), groupName);

        getEnvToGenerateScript();

        try {
            switch (action) {
                case ADD:
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
                    config.setAddedNodeCount(addedNodeCount.get());
                    List<String> addList = newArrayList();
                    Set<? extends NodeMetadata> nodes = compute.createNodesInGroup(groupName, nodeIdList.size(), template);
                    long addTimeStamp = System.currentTimeMillis();
                    for (NodeMetadata node : nodes) {
                        String id = node.getId();
                        String ip = getPrueIpString(node.getPrivateAddresses().toString());
                        LOG.info("<< added node: {} {}", id, concat(node.getPrivateAddresses(), node.getPublicAddresses()));
                        addList.add(id);
                        runningNodeMap.put(id, ip);
                        newAddedNodeMap.put(ip, addTimeStamp);
                    }
                    SetCurrentNodeCount();

                    LOG.info(">> exec {} to initialize nodes as {}", scriptName, addOnOffLogin.identity);
                    Map<? extends NodeMetadata, ExecResponse> responseRun = compute.runScriptOnNodesMatching(
                            inGivenList(addList), Files.toString(file, Charsets.UTF_8),
                            overrideLoginCredentials(addOnOffLogin).runAsRoot(false)
                                    .nameTask("_" + file.getName().replaceAll("\\..*", "")));

                    for (Entry<? extends NodeMetadata, ExecResponse> response : responseRun.entrySet()) {
                        LOG.info("<< {} status {}", response.getKey().getId(), response.getValue());
                    }
                    break;

                case OFF:
                    checkNotNull(addOnOffLogin, "login is invalid, check user home and ssh path or ssh key whether they are existing");
                    //1. before to do turn off the VMs, do stop and remove docker container
                    //2. turn off operation will suspend all the nodes in the given group
                    LOG.info(">> exec {} as {} ", scriptName, addOnOffLogin.identity);
                    Map<? extends NodeMetadata, ExecResponse> stopAndRemove = compute.runScriptOnNodesMatching(
                            inGivenList(nodeIdList),
                            Files.toString(file, Charsets.UTF_8),   // passing in a string with the contents of the file
                            overrideLoginCredentials(addOnOffLogin).runAsRoot(false)
                                    .nameTask("_" + file.getName().replaceAll("\\..*", "")));

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
                    SetCurrentNodeCount();
                    break;

                case ON:
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
                    SetCurrentNodeCount();

                    LOG.info(">> exec {} to initialize nodes", scriptName);
                    //after nodes are turned on, to start new docker container
                    Map<? extends NodeMetadata, ExecResponse> turnOnRun = compute.runScriptOnNodesMatching(
                            inGivenList(nodeIdList), Files.toString(file, Charsets.UTF_8), // passing in a string with the contents of the file
                            overrideLoginCredentials(addOnOffLogin).runAsRoot(false)
                                    .nameTask("_" + file.getName().replaceAll("\\..*", "")));

                    for (Entry<? extends NodeMetadata, ExecResponse> response : turnOnRun.entrySet()) {
                        LOG.info("<< initialized node {}: {}", response.getKey().getId(),
                                concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()));
                        LOG.info("<< {}", response.getValue());
                    }
                    break;

                case DESTROY:
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
                    SetCurrentNodeCount();
                    config.setAddedNodeCount(addedNodeCount.get());
                    break;

                case LIST:
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
                    SetCurrentNodeCount();
                    addedNodeCount.getAndSet(runningNodeMap.size() + stoppedNodeMap.size());
                    config.setAddedNodeCount(addedNodeCount.get());
                    config.setIsListInfoDone(true);
                    LOG.info(">> total number available {} nodes in group {}", addedNodeCount.get(), groupName);
                    break;

                default:
                    break;
            }
        } catch (RunNodesException e) {
            LOG.debug("error adding node to group {}: {}", groupName, e.getMessage());
        } catch (RunScriptOnNodesException e) {
            LOG.debug("error executing command {} in group {}", groupName, e.getMessage());
        } catch (Exception e) {
            LOG.debug("error: {}", e.getMessage());
        } finally {
            compute.getContext().close();
        }
    }

    private ComputeService getComputeService() {
        checkNotNull(identity, "identity can not be null or empty");
        checkNotNull(credential, "credential can not be null or empty");
        return initComputeService(provider, identity, credential);
    }

    private void SetCurrentNodeCount() {
        config.setRunningNodeCount(runningNodeMap.size());
        config.setStoppedNodeCount(stoppedNodeMap.size());
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
     * Script file generator based on the script template
     *
     * @param ctrl_IP,           ngrinder controller IP
     * @param ctrl_port,         ngrinder controller PORT
     * @param agent_docker_repo, the docker image repository name
     * @param agent_docker_tag,  the docker image tag
     * @param cmd,               the operation command, such as ADD, ON, OFF
     */
    private void generateScriptBasedOnTemplate(String ctrl_IP, String ctrl_port, String agent_docker_repo,
                                               String agent_docker_tag, String cmd) {
        /*
         * the must parameters in the target script
         */
        String AGENT_CTRL_IP = "AGENT_CTRL_IP=";
        String AGENT_CTRL_PORT = "AGENT_CTRL_PORT=";
        String AGENT_IMG_REPO = "AGENT_IMG_REPO=";
        String AGENT_IMG_TAG = "AGENT_IMG_TAG=";
        String AGENT_WORK_MODE = "AGENT_WORK_MODE=";

        LOG.info("Ctrl IP: " + ctrl_IP + ", Ctrl Port: " + ctrl_port + ", docker repo: " + agent_docker_repo
                + ", docker tag: " + agent_docker_tag + ", operation: " + cmd);

        String newFileName;
        if (cmd.equalsIgnoreCase("add")) {
            newFileName = "add.sh";
        } else if (cmd.equalsIgnoreCase("on")) {
            newFileName = "on.sh";
        } else if (cmd.equalsIgnoreCase("off")) {
            newFileName = "off.sh";
        } else {
            return;
        }

        StringBuilder sb = new StringBuilder();
        String prefixContent = "#!/bin/bash\n" +
                AGENT_CTRL_IP + ctrl_IP + "\n" +
                AGENT_CTRL_PORT + ctrl_port + "\n" +
                AGENT_IMG_REPO + agent_docker_repo + "\n" +
                AGENT_IMG_TAG + agent_docker_tag + "\n" +
                AGENT_WORK_MODE + cmd.toUpperCase() + "\n" +
                "\n";
        sb.append(prefixContent);

        String templateFile = this.scriptTemplatePath + "/jclouds_op_ec2_template.sh";
        File file = new File(templateFile);
        if (!file.exists()) {
            LOG.warn("{} is not existing....", templateFile);
            return;
        }

        try {
            FileInputStream fis = new FileInputStream(file);
            int len = (int) file.length();
            byte content[] = new byte[len];
            //noinspection ResultOfMethodCallIgnored
            fis.read(content);
            String suffixContent = new String(content);
            sb.append(suffixContent);
            fis.close();

            File newFile = new File(this.scriptTemplatePath + "/" + newFileName);
            FileWriter fw = new FileWriter(newFile);
            BufferedWriter bw = new BufferedWriter(fw);
            bw.write(sb.toString());
            bw.close();
            fw.close();
        } catch (FileNotFoundException e) {
            LOG.debug(e.getMessage());
        } catch (IOException e) {
            LOG.debug(e.getMessage());
        }
    }
}
