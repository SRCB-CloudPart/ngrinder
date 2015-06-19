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
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.google.inject.Module;
import org.jclouds.ContextBuilder;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.RunScriptOnNodesException;
import org.jclouds.compute.domain.ExecResponse;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.ec2.domain.InstanceType;
import org.jclouds.enterprise.config.EnterpriseConfigurationModule;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.scriptbuilder.domain.Statement;
import org.jclouds.scriptbuilder.statements.login.AdminAccess;
import org.jclouds.sshj.config.SshjSshClientModule;
import org.ngrinder.infra.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.management.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.Iterables.concat;
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_AMI_QUERY;
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_CC_AMI_QUERY;
import static org.jclouds.compute.config.ComputeServiceProperties.TIMEOUT_SCRIPT_COMPLETE;
import static org.jclouds.compute.options.TemplateOptions.Builder.overrideLoginCredentials;
import static org.jclouds.compute.predicates.NodePredicates.*;
import static org.jclouds.ec2.compute.options.EC2TemplateOptions.Builder.runScript;
import static org.jclouds.scriptbuilder.domain.Statements.exec;

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
     * Record the total count of EC2 nodes added to the specified group
     */
    private int added_node_count = 0;

    public synchronized int getAddedNodeCount(){
        return this.added_node_count;
    }

    /*
     * number of EC2 node to add to the group for each add operation
     */
    private int adding_nodes = 1;

    public synchronized void setAddingNodes(int adding_nodes) {
        this.adding_nodes = adding_nodes;
        this.added_node_count += adding_nodes;
    }

    /*
     * Flag to check whether there is EC2 instance is in status of adding. Because create EC2 VM will cost several minutes time.
     * Before to add one VM into the group, should check whether there is adding operation is under going. If there is, do not to
     * Add until the previous adding is finished.
     */
    private boolean isInAddingStatus = false;

    public synchronized boolean isInAddingStatus() {
        return isInAddingStatus;
    }

    public synchronized  void setIsInAddingStatus(boolean isInAddingStatus) {
        this.isInAddingStatus = isInAddingStatus;
    }

    public enum Action {
        ADD, RUN, ON, OFF, DESTROY
    }

    //public final Map<String, ApiMetadata> allApis = Maps.uniqueIndex(Apis.viewableAs(ComputeServiceContext.class), Apis.idFunction());

    //public final Map<String, ProviderMetadata> appProviders = Maps.uniqueIndex(Providers.viewableAs(ComputeServiceContext.class), Providers.idFunction());

    //public final Set<String> allKeys = ImmutableSet.copyOf(Iterables.concat(appProviders.keySet(), allApis.keySet()));

    private String containerIP = "";
    private String containerPort = "";

    public String getContainerIP() {
        return containerIP;
    }

    public void setContainerIP(String containerIP) {
        this.containerIP = containerIP;
    }

    private String getContainerPort() {
        return containerPort;
    }

    public void setContainerPort(String containerPort) {
        this.containerPort = containerPort;
    }

    private String provider = "aws-ec2";
    private String identity = null;
    private String credential = null;
    private String groupName = "agents";
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

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public void setScriptName(String scriptName) {
        this.scriptName = this.scriptTemplatePath + "/" + scriptName;
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
        achieveWebContainerAddress();
        registerShutdownHook();
    }

    public void initFirstOneEc2Instance(){
        String dynamicType = config.getAgentDynamicType();
        if(dynamicType.equalsIgnoreCase("EC2"))
        {
            getEnvToGenerateScript("run");
            String identity = config.getEc2Identity();
            String credential = config.getEc2Credential();
            setProvider("aws-ec2");
            setIdentity(identity);
            setCredential(credential);
            setAddingNodes(1);
            setIsInAddingStatus(true);
            setScriptName("run.sh");
            dynamicAgentCommand("run");
            setIsInAddingStatus(false);
        }
    }

    private void registerShutdownHook(){
        String dynamicType = config.getAgentDynamicType();
        if(dynamicType.equalsIgnoreCase("EC2")) {
            String identity = config.getEc2Identity();
            String credential = config.getEc2Credential();
            setProvider("aws-ec2");
            setIdentity(identity);
            setCredential(credential);

            Thread thread = new Thread(){
                @Override
                public void run() {

                    dynamicAgentCommand("destroy");
                }
            };
            LOG.info("Begin to destroy the created EC2 instance when controller daemon shut down...");
            Runtime.getRuntime().addShutdownHook(thread);
        }
    }

    protected void getEnvToGenerateScript(String cmd){
        String dockerImageRepo = config.getDockerRepo();
        String dockerImageTag = config.getDockerTag();
        generateScriptBasedOnTemplate(containerIP, containerPort, dockerImageRepo, dockerImageTag, cmd);
        LOG.info("Container IP: " + containerIP + ", Container Port: " + containerPort +
                ", Repo: " + dockerImageRepo + ", Tag: " + dockerImageTag);
    }

    public void achieveWebContainerAddress(){
        MBeanServer mbServer = ManagementFactory.getPlatformMBeanServer();
        HashSet<ObjectName> objs = null;
        try {
            objs = (HashSet<ObjectName>) mbServer.queryNames(new ObjectName("*:type=Connector,*"),
                    Query.match(Query.attr("protocol"), Query.value("HTTP/1.1")));
        } catch (MalformedObjectNameException e) {
            LOG.info("MBeanServer query failed: " + e.getMessage());
        }
        String hostname = "";
        try {
            hostname = InetAddress.getLocalHost().getHostName();
            LOG.info("hostname: " + hostname);
        } catch (UnknownHostException e) {
            LOG.info("InetAddress get host name failed: " + e.getMessage());
        }
        InetAddress[] addresses = null;
        try {
            addresses = InetAddress.getAllByName(hostname);
            for(InetAddress add: addresses){
                LOG.info("IP: " + add.getHostAddress());
                containerIP = add.getHostAddress();
            }
        } catch (UnknownHostException e) {
            LOG.info("InetAddress get host address by name failed: " + e.getMessage());
        }

        boolean isJetty = true;
        for (Iterator<ObjectName> i = objs.iterator(); i.hasNext();) {
            isJetty = false;
            ObjectName obj = i.next();
            String scheme = null;
            try {
                scheme = mbServer.getAttribute(obj, "scheme").toString();
            } catch (AttributeNotFoundException e) {
                LOG.info("MBeanServer get host scheme AttributeNotFoundException: " + e.getMessage());
            } catch (InstanceNotFoundException e) {
                LOG.info("MBeanServer get host scheme InstanceNotFoundException: " + e.getMessage());
            } catch (MBeanException e) {
                LOG.info("MBeanServer get host scheme MBeanException: " + e.getMessage());
            } catch (ReflectionException e) {
                LOG.info("MBeanServer get host scheme ReflectionException: " + e.getMessage());
            }
            String port = obj.getKeyProperty("port");
            setContainerPort(port);
            for (InetAddress addr : addresses) {
                setContainerIP(addr.getHostAddress());
                LOG.info("Container IP: " + containerIP + ", Container PORT: " + containerPort);
                System.out.println("Container IP: " + containerIP + ", Container PORT: " + containerPort);
            }
        }
        if(isJetty){
            containerPort = System.getProperty("controller-server-port");
        }
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

        System.out.printf(">> initializing %s%n", builder.getApiMetadata());
        LOG.info(">> initializing " + builder.getApiMetadata());

        return builder.buildView(ComputeServiceContext.class).getComputeService();
    }

//    private LoginCredentials getLoginViaKeyForCommandExecution() {
//        try {
//            String user = "ec2-user";
//            String privateKey = Files.toString(new File("/home/ec2-user/.ssh/id_rsa"), UTF_8);
//            return LoginCredentials.builder().user(user).privateKey(privateKey).build();
//        } catch (Exception e) {
//            System.err.println("error reading ssh key " + e.getMessage());
//            LOG.debug("error reading ssh key " + e.getMessage());
//            return null;
//        }
//    }

    private LoginCredentials getLoginViaKeyForCommandExecution() {
        try {
            String user = System.getProperty("user.name");
            String privateKey = Files.toString(
                    new File(System.getProperty("user.home"), "/.ssh/id_rsa"), UTF_8);
            return LoginCredentials.builder().
                    user(user).privateKey(privateKey).build();
        } catch (Exception e) {
            System.err.println("error reading ssh key " + e.getMessage());
            LOG.debug("error reading ssh key " + e.getMessage());
            return null;
        }
    }

    /**
     * Major operation, according to the action which is enum value.
     *
     * @param actionCmd
     */
    public void dynamicAgentCommand(String actionCmd) {

        LOG.info(actionCmd);

        checkNotNull(identity, "identity can not be null or empty");
        checkNotNull(credential, "credential can not be null or empty");

        Action action = Action.valueOf(actionCmd.toUpperCase());

        if ((action == Action.RUN || action == Action.ON || action == Action.OFF) && scriptName == null) {
            LOG.debug("please pass the local file to run as the last parameter");
            throw new IllegalArgumentException("please pass the local file to run as the last parameter");
        }

        File file = new File(scriptName);

        // note that you can check if a provider is present ahead of time
        //checkArgument(contains(allKeys, provider), "provider %s not in supported list: %s", provider, allKeys);

        LoginCredentials login =  (action != Action.DESTROY) ? getLoginViaKeyForCommandExecution() : null;

        ComputeService compute = initComputeService(provider, identity, credential);

        try {
            switch (action) {
                case RUN:
                    LOG.info(">> adding node to group " + groupName);
                    System.out.printf(">> adding node to group %s%n", groupName);

//                    Template template = compute.templateBuilder()
//                            .locationId("ap-southeast-1")
//                            .hardwareId(InstanceType.M1_MEDIUM)
//                            .build();
//
//                    template.getOptions().as(AWSEC2TemplateOptions.class)
//                            .authorizePublicKey(Files.toString(new File("/home/ec2-user/.ssh/id_rsa.pub"), Charsets.UTF_8));

                    TemplateBuilder templateBuilder = compute.templateBuilder()
                            .locationId("ap-southeast-1").hardwareId(InstanceType.M1_MEDIUM);

                    Statement bootInstructions = AdminAccess.standard();

                    templateBuilder.options(runScript(bootInstructions));
                    Template template = templateBuilder.build();

                    Set<? extends NodeMetadata> nodes = compute.createNodesInGroup(groupName, adding_nodes, template);
                    for (NodeMetadata node: nodes) {
                        LOG.info("<< node " + node.getId() + "[" + concat(node.getPrivateAddresses(), node.getPublicAddresses()) + "]");
                        System.out.printf("<< node %s: %s%n", node.getId(), concat(node.getPrivateAddresses(), node.getPublicAddresses()));
                    }

                    // init to run docker daemon installation
                    LOG.info(">> run command: 'sudo wget -qO- https://get.docker.com/ | sh' with account: "  + login.identity);
                    System.out.println(">> run command: 'sudo wget -qO- https://get.docker.com/ | sh' with account: "  + login.identity);
                    Map<? extends NodeMetadata, ExecResponse> responses = compute.runScriptOnNodesMatching(
                            inGroup(groupName),                                     // predicate used to select nodes
                            exec("sudo wget -qO- https://get.docker.com/ | sh"),    // what you actually intend to run
                            overrideLoginCredentials(login)                         // use my local user & ssh key
                                    .runAsRoot(false)                               // don't attempt to run as root (sudo)
                                    .wrapInInitScript(false));                      // run command directly

                    for (Entry<? extends NodeMetadata, ExecResponse> response : responses.entrySet()) {
                        LOG.info("<<     " + response.getValue());
                        System.out.printf("<<     %s%n", response.getValue());
                    }

                    String info = ">> running [" + scriptName + "] on group " + groupName + " as " + login.identity;
                    LOG.info(info);
                    System.out.printf(">> running [%s] on group %s as %s%n", file, groupName, checkNotNull(login).identity);
                    // when running a sequence of commands, you probably want to have jclouds use the default behavior,
                    // which is to fork a background process.
                    Map<? extends NodeMetadata, ExecResponse> responserun = compute.runScriptOnNodesMatching(//
                            inGroup(groupName),
                            Files.toString(file, Charsets.UTF_8),
                            overrideLoginCredentials(login)
                                    .runAsRoot(false)
                                    .nameTask("_" + file.getName().replaceAll("\\..*", "")));   // ensuring task name isn't
                                                                                                // the same as the file so status checking works properly

                    for (Entry<? extends NodeMetadata, ExecResponse> response : responserun.entrySet()) {
                        LOG.info("<<     " + response.getValue());
                        System.out.printf("<<     %s%n", response.getValue());
                    }
                    break;

                case OFF:
        	 	    //before to do turn off the VMs, do stop and remove docker container
                    String infof = ">> turn off [" + scriptName + "] on group " + groupName + " as " + login.identity;
                    LOG.info(infof);
                    System.out.printf(">> turn off [%s] on group %s as %s%n", scriptName, groupName, checkNotNull(login).identity);
                    Map<? extends NodeMetadata, ExecResponse> stopandremove = compute.runScriptOnNodesMatching(
                            inGroup(groupName),
                            Files.toString(file, Charsets.UTF_8),   // passing in a string with the contents of the file
                            overrideLoginCredentials(login)
                                    .runAsRoot(false)
                                    .nameTask("_" + file.getName().replaceAll("\\..*", ""))); // ensuring task name isn't
                    // the same as the file so status checking works properly
                    for (Entry<? extends NodeMetadata, ExecResponse> response : stopandremove.entrySet()) {
                        LOG.info("<< node " + response.getKey().getId() + ": " +
                                "[" + concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()) + "]");
                        System.out.printf("<< node %s: %s%n", response.getKey().getId(),
                                concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()));
                        LOG.info("<<     " + response.getValue());
                        System.out.printf("<<     %s%n", response.getValue());
                    }
                    LOG.info(">> turn off nodes in group " + groupName);
                    System.out.printf(">> turn off nodes in group %s%n", groupName);

                    // you can use predicates to select which nodes you wish to turn off.
                    Set<? extends NodeMetadata> turnoffs = compute.suspendNodesMatching(Predicates.and(RUNNING, inGroup(groupName)));
                    LOG.info("<< turn off nodes " + turnoffs);
                    System.out.printf("<< turn off nodes %s%n", turnoffs);
                    break;

                case ON:
                    String infoo = ">> turnon [" + scriptName + "] on group " + groupName + " as " + login.identity;
                    LOG.info(infoo);
                    System.out.printf(">> turn on [%s] on group %s as %s%n", scriptName, groupName, checkNotNull(login).identity);

                    // you can use predicates to select which nodes you wish to turn on.
                    Set<? extends NodeMetadata> turnons = compute.resumeNodesMatching(Predicates.and(SUSPENDED, inGroup(groupName)));
                    LOG.info("<< turn on nodes " + turnons);
                    System.out.printf("<< turn on nodes %s%n", turnons);

             		//after nodes are turned on, to start new docker container
                    Map<? extends NodeMetadata, ExecResponse> turnonrun = compute.runScriptOnNodesMatching(
                            inGroup(groupName),
                            Files.toString(file, Charsets.UTF_8), // passing in a string with the contents of the file
                            overrideLoginCredentials(login)
                                    .runAsRoot(false)
                                    .nameTask("_" + file.getName().replaceAll("\\..*", ""))); // ensuring task name isn't

                    // the same as the file so status checking works properly
                    for (Entry<? extends NodeMetadata, ExecResponse> response : turnonrun.entrySet()) {
                        LOG.info("<< node " + response.getKey().getId() + ": " +
                                "[" + concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()) + "]");
                        System.out.printf("<< node %s: %s%n", response.getKey().getId(),
                                concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()));
                        LOG.info("<<     " + response.getValue());
                        System.out.printf("<<     %s%n", response.getValue());
                    }
                    break;

                case DESTROY:
                    LOG.info(">> destroying nodes in group " + groupName);
                    System.out.printf(">> destroying nodes in group %s%n", groupName);
                    // you can use predicates to select which nodes you wish to destroy.
                    Set<? extends NodeMetadata> destroyed = compute.destroyNodesMatching(Predicates.and(not(TERMINATED), inGroup(groupName)));
                    LOG.info("<< destroyed nodes %s%n" + destroyed);
                    System.out.printf("<< destroyed nodes %s%n", destroyed);
                    break;

                default:
                    break;
            }
        } catch (RunNodesException e) {
            LOG.debug("error adding node to group " + groupName + ": " + e.getMessage());
            System.err.println("error adding node to group " + groupName + ": " + e.getMessage());
        } catch (RunScriptOnNodesException e) {
            LOG.debug("error executing command" + " on group " + groupName + ": " + e.getMessage());
            System.err.println("error executing command" + " on group " + groupName + ": " + e.getMessage());
        } catch (Exception e) {
            LOG.debug("error: " + e.getMessage());
            System.err.println("error: " + e.getMessage());
        } finally {
            compute.getContext().close();
        }
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
     * @return void
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

        String newFileName = "";
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
        FileWriter fw = null;
        try {
            fw = new FileWriter(newFile);
            BufferedWriter bw = new BufferedWriter(fw);
            bw.write(sb.toString());
            bw.close();
            fw.close();
        } catch (IOException e) {
            LOG.debug(e.getMessage());
        }
    }
}
