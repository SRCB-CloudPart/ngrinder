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
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Sets.newHashSet;
import static com.google.common.collect.Maps.newHashMap;
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_AMI_QUERY;
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_CC_AMI_QUERY;
import static org.jclouds.compute.config.ComputeServiceProperties.TIMEOUT_SCRIPT_COMPLETE;
import static org.jclouds.compute.options.TemplateOptions.Builder.overrideLoginCredentials;
import static org.jclouds.compute.predicates.NodePredicates.*;
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
 *     <li>Add node to group</li>
 *     <li>Turn off all the nodes in group</li>
 *     <li>Turn on all the nodes in group</li>
 *     <li>Destroy all the nodes in group</li>
 * </ul>
 *
 * @author shihuc
 * @since 3.4
 */
@Component("dyanmicAgent")
public class DynamicAgentHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicAgentHandler.class);

    @Autowired
    private Config config;

    /*
     * Define two sets, one is to record the running nodes, the other is to record
     * the stopped node. These two list will be convenient for operation in the next stage.
     */
    private Set<String> runningNodeSet = newHashSet();
    private Set<String> stoppedNodeSet = newHashSet();
    private Map<String, String> runningNodeIdIPMap = newHashMap();

    public int getStoppedNodeCount(){
        return stoppedNodeSet.size();
    }

    public Map<String, String> getRunningNodeIdIPMap(){
        return runningNodeIdIPMap;
    }

    /*
     * Record the total count of EC2 nodes added to the specified group
     */
    private int added_node_count = 0;

    public int getAddedNodeCount(){
        return this.added_node_count;
    }

    /*
     * Flag to check whether there is EC2 instance is in status of adding. Because create EC2 VM will cost several minutes time.
     * Before to add one VM into the group, should check whether there is adding operation is under going. If there is, do not to
     * Add until the previous adding is finished.
     */
    private boolean isInAddingStatus = false;

    public boolean isInAddingStatus() {
        return isInAddingStatus;
    }

    public void setIsInAddingStatus(boolean isInAddingStatus) {
        this.isInAddingStatus = isInAddingStatus;
    }

    public enum Action {
        ADD, RUN, ON, OFF, DESTROY, LIST
    }

    private String provider = "aws-ec2";
    private String identity = null;
    private String credential = null;
    private String scriptTemplatePath = null;
    private String scriptName = null;

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
    public String generateUniqueGroupName(){
        String groupName = "agt";
        String ctrl_ip = config.getAgentDynamicControllerIP();
        ctrl_ip = ctrl_ip.replaceAll("\\.", "d");
        return groupName + ctrl_ip;
    }

    @PostConstruct
    public void init(){
        initEnvironment();
    }

    private void initEnvironment(){
        String scriptTemplateFile = "/agent_dynamic_provision_script/jclouds_op_ec2_template.sh";
        ClassPathResource cpr = new ClassPathResource(scriptTemplateFile);
        try {
            this.scriptTemplatePath = cpr.getFile().getParent();
        } catch (IOException e) {
            LOG.info(e.getMessage());
        }

        getEnvToGenerateScript();

        registerShutdownHook();
    }

    private void setProviderIdCredentialForEc2(){
        String identity = config.getAgentDynamicEc2Identity();
        String credential = config.getAgentDynamicEc2Credential();
        setProvider("aws-ec2");
        setIdentity(identity);
        setCredential(credential);
    }

    public void initFirstOneEc2Instance(){
        if(config.isAgentDynamicEc2Enabled()) {
            setIsInAddingStatus(true);
            setProviderIdCredentialForEc2();
            dynamicAgentCommand("list", getAddedNodeCount());
            setScriptName("run.sh");
            if (runningNodeSet.size() == 0 && stoppedNodeSet.size() == 0) {
                dynamicAgentCommand("run", 1);
            }else if(runningNodeSet.size() == 0 && stoppedNodeSet.size() > 0){
                dynamicAgentCommand("on", 1);
            }
            setIsInAddingStatus(false);
        }
    }

    public void addDynamicEc2Instance(int requiredNum){
        if(config.isAgentDynamicEc2Enabled()) {
            setIsInAddingStatus(true);
            setProviderIdCredentialForEc2();
            setScriptName("run.sh");
            dynamicAgentCommand("run", requiredNum);
            setIsInAddingStatus(false);
        }
    }

    public void turnOnEc2Instance(int requiredNum){
        if(config.isAgentDynamicEc2Enabled()) {
            if (stoppedNodeSet.size() >= requiredNum) {
                setIsInAddingStatus(true);
                setProviderIdCredentialForEc2();
                setScriptName("on.sh");
                dynamicAgentCommand("on", requiredNum);
                setIsInAddingStatus(false);
            }
        }
    }

    public void turnOffEc2Instance(){
        if(config.isAgentDynamicEc2Enabled()) {
            setIsInAddingStatus(true);
            setProviderIdCredentialForEc2();
            setScriptName("off.sh");
            dynamicAgentCommand("off", getAddedNodeCount());
            setIsInAddingStatus(false);
        }
    }

    private void registerShutdownHook(){
        if(config.isAgentDynamicEc2Enabled()) {
            setProviderIdCredentialForEc2();
            Thread thread = new Thread(){
                @Override
                public void run() {
                    dynamicAgentCommand("destroy", getAddedNodeCount());
                }
            };
            LOG.info("Register shutdown hook to destroy the created EC2 instance when controller daemon shut down...");
            Runtime.getRuntime().addShutdownHook(thread);
        }
    }

    protected void getEnvToGenerateScript(){
        String dockerImageRepo = config.getAgentDynamicDockerRepo();
        String dockerImageTag = config.getAgentDynamicDockerTag();
        String controllerIP = config.getAgentDynamicControllerIP();
        String controllerPort = config.getAgentDynamicControllerPort();

        generateScriptBasedOnTemplate(controllerIP, controllerPort, dockerImageRepo, dockerImageTag, "run");
        generateScriptBasedOnTemplate(controllerIP, controllerPort, dockerImageRepo, dockerImageTag, "off");
        generateScriptBasedOnTemplate(controllerIP, controllerPort, dockerImageRepo, dockerImageTag, "on");

        LOG.info("Container IP: " + controllerIP + ", Container Port: " + controllerPort +
                ", Repo: " + dockerImageRepo + ", Tag: " + dockerImageTag);
    }

    private Predicate<ComputeMetadata> nodeNameStartsWith(final String nodeNamePrefix) {
        checkNotNull(nodeNamePrefix, "reasonable node name prefix must be provided");
        return new Predicate<ComputeMetadata>(){
            @Override
            public boolean apply(ComputeMetadata computeMetadata) {
                String nodeName = computeMetadata.getName();
                return nodeName != null && nodeName.startsWith(nodeNamePrefix) ;
            }
            @Override
            public String toString() {
                return "nodeNameStartsWith(" + nodeNamePrefix + ")";
            }
        };
    }

    private Predicate<NodeMetadata> inGivenList(final List<String> givenList) {
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

    private ComputeService initComputeService(String provider, String identity, String credential) {

        // specific properties, in this case optimizing image list to only amazon supplied
        Properties properties = new Properties();
        properties.setProperty(PROPERTY_EC2_AMI_QUERY, "owner-id=137112412989;state=available;image-type=machine");
        properties.setProperty(PROPERTY_EC2_CC_AMI_QUERY, "");
        long scriptTimeout = TimeUnit.MILLISECONDS.convert(20, TimeUnit.MINUTES);
        properties.setProperty(TIMEOUT_SCRIPT_COMPLETE, scriptTimeout + "");

        // inject a ssh implementation
        Iterable<Module> modules = ImmutableSet.<Module> of(
                new SshjSshClientModule(),
                new SLF4JLoggingModule(),
                new EnterpriseConfigurationModule());

        LOG.info("provider: " + provider + ", identity: " + identity + ", credential: " + credential);
        ContextBuilder builder = ContextBuilder.newBuilder(provider)
                .credentials(identity, credential)
                .modules(modules)
                .overrides(properties);

        LOG.info(">> initializing " + builder.getApiMetadata());

        return builder.buildView(ComputeServiceContext.class).getComputeService();
    }

    /**
     * Major operation, according to the action which is enum value.
     *
     * @param actionCmd action command name
     * @param count the number of EC2 instances will be operated by the action command
     *
     */
    public void dynamicAgentCommand(String actionCmd, int count) {

        LOG.info("action command: " + actionCmd + ", touched ec2 instance count: " + count);

        checkNotNull(identity, "identity can not be null or empty");
        checkNotNull(credential, "credential can not be null or empty");

        Action action = Action.valueOf(actionCmd.toUpperCase());

        File file = null;
        if (action == Action.RUN || action == Action.ON || action == Action.OFF) {
            checkNotNull(scriptName, "please pass the local file to run as the last parameter");
            file = new File(scriptName);
        }

        LoginCredentials login =  (action != Action.DESTROY && action != Action.LIST) ? getLoginCredential() : null;

        ComputeService compute = initComputeService(provider, identity, credential);

        String groupName = generateUniqueGroupName();

        try {
            switch (action) {
                case RUN:
                    LOG.info(">> add " + count + " node to group " + groupName);

                    TemplateBuilder templateBuilder = compute.templateBuilder()
                            .locationId("ap-southeast-1").hardwareId(InstanceType.M1_MEDIUM);

                    Statement bootInstructions = createAdminAccess();
                    templateBuilder.options(runScript(bootInstructions));
                    Template template = templateBuilder.build();

                    List<String> addList = newArrayList();
                    Set<? extends NodeMetadata> nodes = compute.createNodesInGroup(groupName, count, template);
                    for (NodeMetadata node: nodes) {
                        String id = node.getId();
                        LOG.info("<< added node: " + id + " [" + concat(node.getPrivateAddresses(), node.getPublicAddresses()) + "]");
                        addList.add(id);
                        added_node_count++;
                        runningNodeSet.add(id);
                        runningNodeIdIPMap.put(id, node.getPrivateAddresses().toString());
                    }

                    LOG.info(">> run [" + scriptName + "] on group " + groupName + " as " + login.identity);
                    Map<? extends NodeMetadata, ExecResponse> responseRun = compute.runScriptOnNodesMatching(
                            inGivenList(addList), Files.toString(file, Charsets.UTF_8),
                            overrideLoginCredentials(login).runAsRoot(false)
                                    .nameTask("_" + file.getName().replaceAll("\\..*", "")));

                    for (Entry<? extends NodeMetadata, ExecResponse> response : responseRun.entrySet()) {
                        LOG.info("<< " + response.getKey().getId() + " status: " + response.getValue());
                    }
                    break;

                case OFF:
        	 	    //1. before to do turn off the VMs, do stop and remove docker container
                    //2. turn off operation will suspend all the nodes in the given group
                    LOG.info(">> turn off [" + scriptName + "] on group " + groupName + " as " + login.identity);
                    Map<? extends NodeMetadata, ExecResponse> stopAndRemove = compute.runScriptOnNodesMatching(
                            inGroup(groupName),
                            Files.toString(file, Charsets.UTF_8),   // passing in a string with the contents of the file
                            overrideLoginCredentials(login).runAsRoot(false)
                                    .nameTask("_" + file.getName().replaceAll("\\..*", "")));

                    for (Entry<? extends NodeMetadata, ExecResponse> response : stopAndRemove.entrySet()) {
                        String id = response.getKey().getId();
                        LOG.info("<< node " + id + ": " +
                                "[" + concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()) + "]");
                        LOG.info("<< stop and remove status: " + response.getValue());
                    }
                    LOG.info(">> turn off nodes in group " + groupName);

                    // you can use predicates to select which nodes you wish to turn off.
                    Set<? extends NodeMetadata> turnOff = compute.suspendNodesMatching(Predicates.and(RUNNING, inGroup(groupName)));
                    for(NodeMetadata node: turnOff){
                        String id = node.getId();
                        LOG.info("<< turn off node " + node);
                        stoppedNodeSet.add(id);
                        runningNodeSet.remove(id);
                        runningNodeIdIPMap.remove(id);
                    }
                    break;

                case ON:
                    LOG.info(">> turn on [" + scriptName + "] " + count + " node(s) on group " + groupName + " as " + login.identity);
                    List<String> turnOnList = newArrayList();
                    for(String id: stoppedNodeSet){
                        turnOnList.add(id);
                        if(turnOnList.size() >= count){
                            break;
                        }
                    }
                    Set<? extends NodeMetadata> turnOn = compute.resumeNodesMatching(Predicates.and(SUSPENDED, inGivenList(turnOnList)));
                    for (NodeMetadata node: turnOn) {
                        String id = node.getId();
                        LOG.info("<< pre-turned on node: " + id);
                        runningNodeSet.add(id);
                        stoppedNodeSet.remove(id);
                        runningNodeIdIPMap.put(id, node.getPrivateAddresses().toString());
                    }

             		//after nodes are turned on, to start new docker container
                    Map<? extends NodeMetadata, ExecResponse> turnOnRun = compute.runScriptOnNodesMatching(
                            inGroup(groupName),  Files.toString(file, Charsets.UTF_8), // passing in a string with the contents of the file
                            overrideLoginCredentials(login).runAsRoot(false)
                                    .nameTask("_" + file.getName().replaceAll("\\..*", "")));

                    for (Entry<? extends NodeMetadata, ExecResponse> response : turnOnRun.entrySet()) {
                        LOG.info("<< turned on node " + response.getKey().getId() + ": " +
                                "[" + concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()) + "]");
                        LOG.info("<< " + response.getValue());
                    }
                    break;

                case DESTROY:
                    LOG.info(">> destroy nodes in group " + groupName);
                    // you can use predicates to select which nodes you wish to destroy.
                    Set<? extends NodeMetadata> destroyed = compute.destroyNodesMatching(Predicates.and(not(TERMINATED), inGroup(groupName)));
                    LOG.info("<< destroyed nodes: " + destroyed);
                    runningNodeSet.clear();
                    stoppedNodeSet.clear();
                    runningNodeIdIPMap.clear();
                    break;

                case LIST:
                    Set<? extends NodeMetadata> gnodes = compute.listNodesDetailsMatching(nodeNameStartsWith(groupName));
                    LOG.info(">> total number nodes/instances " + gnodes.size() + " group " + groupName);
                    for (NodeMetadata nodeData : gnodes) {
                        LOG.info("    >> " + nodeData);
                        Status status = nodeData.getStatus();
                        if(status == Status.RUNNING){
                            runningNodeSet.add(nodeData.getId());
                            runningNodeIdIPMap.put(nodeData.getId(), nodeData.getPrivateAddresses().toString());
                        }else if(status == Status.SUSPENDED){
                            stoppedNodeSet.add(nodeData.getId());
                        }
                    }
                    added_node_count = runningNodeSet.size() + stoppedNodeSet.size();
                    LOG.info(">> total number available nodes " + added_node_count + " on group " + groupName);

                    break;

                default:
                    break;
            }
        } catch (RunNodesException e) {
            LOG.debug("error adding node to group " + groupName + ": " + e.getMessage());
        } catch (RunScriptOnNodesException e) {
            LOG.debug("error executing command" + " on group " + groupName + ": " + e.getMessage());
        } catch (Exception e) {
            LOG.debug("error: " + e.getMessage());
        } finally {
            compute.getContext().close();
        }
    }


    private LoginCredentials getAgentLoginForCommandExecution() {
        try {
            String user = "agent";
            File priFile = new File("/home/agent/.ssh/id_rsa");
            checkNotNull(priFile, "private ssh key file id_rsa of user 'agent' is not existing.");
            String privateKey = Files.toString(priFile, UTF_8);
            return LoginCredentials.builder().user(user).privateKey(privateKey).build();
        } catch (Exception e) {
            LOG.debug("error reading ssh key " + e.getMessage());
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
            LOG.debug("error reading ssh key " + e.getMessage());
            return null;
        }
    }

    /**
     * According to the current user to do different login operation. Because AdminAccess in jclouds does not allow
     * 'root' user to login the EC2 VM if the AMI is default from Amazon provider.
     *
     * @return login credential
     */
    private LoginCredentials getLoginCredential(){
        String user = System.getProperty("user.name");
        LoginCredentials tempLogin = null;
        if(user.equalsIgnoreCase("root")){
            tempLogin = getAgentLoginForCommandExecution();
        }else{
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
    private Statement createAdminAccess(){
        String user = System.getProperty("user.name");
        Statement bootInstruction = null;
        if(user.equalsIgnoreCase("root")) {
            File pubFile = new File("/home/agent/.ssh/id_rsa.pub");
            File priFile = new File("/home/agent/.ssh/id_rsa");
            checkNotNull(pubFile, "public ssh key file id_rsa.pub of user 'agent' not exist");
            checkNotNull(priFile, "private ssh key file id_rsa of user 'agent' not exist");
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
        }else{
            String home = System.getProperty("user.home");
            File pubFile = new File(home, "/.ssh/id_rsa.pub");
            File priFile = new File(home, "/.ssh/id_rsa");
            checkNotNull(pubFile, "public ssh key file id_rsa.pub of user '" + user + "' not exist");
            checkNotNull(priFile, "private ssh key file id_rsa of user '" + user + "' not exist");
            bootInstruction = AdminAccess.standard();
        }
        return bootInstruction;
    }

    /**
     * Script file generator based on the script template
     *
     * @param ctrl_IP, ngrinder controller IP
     * @param ctrl_port, ngrinder controller PORT
     * @param agent_docker_repo, the docker image repository name
     * @param agent_docker_tag, the docker image tag
     * @param cmd, the operation command, such as ADD, RUN, TURN ON, TURN OFF
     *
     */
    protected void generateScriptBasedOnTemplate(String ctrl_IP, String ctrl_port, String agent_docker_repo,
                                                 String agent_docker_tag, String cmd)  {
        /*
         * the must parameters in the target script
         */
        String AGENT_CTRL_IP="AGENT_CTRL_IP=";
        String AGENT_CTRL_PORT="AGENT_CTRL_PORT=";
        String AGENT_IMG_REPO="AGENT_IMG_REPO=";
        String AGENT_IMG_TAG="AGENT_IMG_TAG=";
        String AGENT_WORK_MODE="AGENT_WORK_MODE=";

        LOG.info("Ctrl IP: " + ctrl_IP + ", Ctrl Port: " + ctrl_port + ", docker repo: " + agent_docker_repo
                    + ", docker tag: " + agent_docker_tag + ", operation: " + cmd);

        String newFileName;
        if(cmd.equalsIgnoreCase("run")) {
            newFileName = "run.sh";
        }else if(cmd.equalsIgnoreCase("on")){
            newFileName = "on.sh";
        }else if(cmd.equalsIgnoreCase("off")){
            newFileName = "off.sh";
        }else{
            return;
        }

        StringBuffer sb = new StringBuffer();
        sb.append("#!/bin/bash\n");
        sb.append("\n");
        sb.append(AGENT_CTRL_IP  + ctrl_IP   + "\n");
        sb.append(AGENT_CTRL_PORT + ctrl_port + "\n");
        sb.append(AGENT_IMG_REPO + agent_docker_repo  + "\n");
        sb.append(AGENT_IMG_TAG + agent_docker_tag + "\n");
        sb.append(AGENT_WORK_MODE + cmd.toUpperCase() + "\n");
        sb.append("\n");

        FileReader fr = null;
        String templateFile =  this.scriptTemplatePath + "/jclouds_op_ec2_template.sh";
        try {
            fr = new FileReader(templateFile);
        } catch (IOException e) {
            LOG.debug(e.getMessage());
        }

        File newFile = null;
        BufferedReader br = new BufferedReader(fr);
        String line = null;
        try {
            newFile = new File(this.scriptTemplatePath + "/" + newFileName);
            while((line = br.readLine()) != null){
                sb.append(line + "\n");
            }
        } catch (IOException e) {
            LOG.debug(e.getMessage());
        }

        try {
            FileWriter fw = new FileWriter(newFile);
            BufferedWriter bw = new BufferedWriter(fw);
            bw.write(sb.toString());
            bw.close();
            fw.close();
        } catch (IOException e) {
            LOG.debug(e.getMessage());
        }
    }
}
