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
import org.jclouds.aws.ec2.compute.AWSEC2TemplateOptions;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.RunScriptOnNodesException;
import org.jclouds.compute.domain.*;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.ec2.domain.InstanceType;
import org.jclouds.enterprise.config.EnterpriseConfigurationModule;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.sshj.config.SshjSshClientModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Iterables.getOnlyElement;
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_AMI_QUERY;
import static org.jclouds.aws.ec2.reference.AWSEC2Constants.PROPERTY_EC2_CC_AMI_QUERY;
import static org.jclouds.compute.config.ComputeServiceProperties.TIMEOUT_SCRIPT_COMPLETE;
import static org.jclouds.compute.options.TemplateOptions.Builder.overrideLoginCredentials;
import static org.jclouds.compute.predicates.NodePredicates.*;
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
@Component
public class DynamicAgentHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicAgentHandler.class);

    /*
     * Record the count of EC2 nodes added to the specified group
     */
    private int added_node_count = 0;

    /*
     * Flag to check whether there is EC2 instance is in status of adding. Because create EC2 VM will cost several minutes time.
     * Before to add one VM into the group, should check whether there is adding operation is under going. If there is, do not to
     * Add until the previous adding is finished.
     */
    private boolean isInAddingStatus = false;

    public enum Action {
        ADD, RUN, TURNON, TURNOFF, DESTROY, LISTIMAGES, LISTNODES
    }

    //public final Map<String, ApiMetadata> allApis = Maps.uniqueIndex(Apis.viewableAs(ComputeServiceContext.class),Apis.idFunction());

    //public final Map<String, ProviderMetadata> appProviders = Maps.uniqueIndex(Providers.viewableAs(ComputeServiceContext.class), Providers.idFunction());

    //public final Set<String> allKeys = ImmutableSet.copyOf(Iterables.concat(appProviders.keySet(), allApis.keySet()));

    private String provider = "aws-ec2";
    private String identity = null;
    private String credential = null;
    private String groupName = "autoagent";
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
        this.scriptName = scriptName;
    }

    public synchronized int getAddedNodeCount(){
        return added_node_count;
    }

    public synchronized void increaseAddedNodeCount(int cnt){
        added_node_count += cnt ;
    }

    public synchronized boolean isInAddingStatus() {
        return isInAddingStatus;
    }

    public synchronized  void setIsInAddingStatus(boolean isInAddingStatus) {
        this.isInAddingStatus = isInAddingStatus;
    }

    public synchronized ComputeService initComputeService(String provider, String identity, String credential) {

        // example of specific properties, in this case optimizing image list to
        // only amazon supplied
        Properties properties = new Properties();
        properties.setProperty(PROPERTY_EC2_AMI_QUERY, "owner-id=137112412989;state=available;image-type=machine");
        properties.setProperty(PROPERTY_EC2_CC_AMI_QUERY, "");
        long scriptTimeout = TimeUnit.MILLISECONDS.convert(20, TimeUnit.MINUTES);
        properties.setProperty(TIMEOUT_SCRIPT_COMPLETE, scriptTimeout + "");

        // example of injecting a ssh implementation
        Iterable<Module> modules = ImmutableSet.<Module> of(
                new SshjSshClientModule(),
                new SLF4JLoggingModule(),
                new EnterpriseConfigurationModule());

        ContextBuilder builder = ContextBuilder.newBuilder(provider)
                .credentials(identity, credential)
                .modules(modules)
                .overrides(properties);

        System.out.printf(">> initializing %s%n", builder.getApiMetadata());
        LOG.info(">> initializing " + builder.getApiMetadata());

        return builder.buildView(ComputeServiceContext.class).getComputeService();
    }

    public LoginCredentials getLoginViaKeyForCommandExecution() {
        try {
            String user = "ec2-user";
            String privateKey = Files.toString(new File("/home/ec2-user/.ssh/id_rsa"), UTF_8);
            return LoginCredentials.builder().user(user).privateKey(privateKey).build();
        } catch (Exception e) {
            System.err.println("error reading ssh key " + e.getMessage());
            LOG.debug("error reading ssh key " + e.getMessage());
            return null;
        }
    }

    public void dynamicAgentCommand(String actionCmd) {

        checkNotNull(identity);
        checkNotNull(credential);

        Action action = Action.valueOf(actionCmd.toUpperCase());

        if ((action == Action.RUN || action == Action.TURNON || action == Action.TURNOFF) && scriptName == null) {
            LOG.debug("please pass the local file to run as the last parameter");
            throw new IllegalArgumentException("please pass the local file to run as the last parameter");
        }

        File file = null;
        if (action == Action.RUN || action == Action.TURNOFF || action == Action.TURNON) {
            try {
                file = new ClassPathResource(scriptName).getFile();
            } catch (IOException e) {
                e.printStackTrace();
                LOG.debug("file must exist! " + e.getMessage());
                throw new IllegalArgumentException("file must exist! " + scriptName);
            }
        }

        // note that you can check if a provider is present ahead of time
        //checkArgument(contains(allKeys, provider), "provider %s not in supported list: %s", provider, allKeys);

        LoginCredentials login =  (action != Action.DESTROY) ? getLoginViaKeyForCommandExecution() : null;

        ComputeService compute = initComputeService(provider, identity, credential);

        try {
            switch (action) {
                case ADD:
                    LOG.info(">> adding node to group " + groupName);
                    System.out.printf(">> adding node to group %s%n", groupName);

                    Template template = compute.templateBuilder()
                            .locationId("ap-southeast-1")
                            .hardwareId(InstanceType.M1_MEDIUM)
                            .build();

                    template.getOptions().as(AWSEC2TemplateOptions.class)
                            .authorizePublicKey(Files.toString(new File("/home/ec2-user/.ssh/id_rsa.pub"), Charsets.UTF_8));

                    NodeMetadata node = getOnlyElement(compute.createNodesInGroup(groupName, 1, template));
                    LOG.info("<< node " + node.getId() + "[" + concat(node.getPrivateAddresses(), node.getPublicAddresses()) + "]");
                    System.out.printf("<< node %s: %s%n", node.getId(), concat(node.getPrivateAddresses(), node.getPublicAddresses()));

                    // init to run docker daemon installation
                    LOG.info(">> run command: 'sudo wget -qO- https://get.docker.com/ | sh' with account: "  + login.identity);
                    Map<? extends NodeMetadata, ExecResponse> responses = compute.runScriptOnNodesMatching(//
                            inGroup(groupName),                                     // predicate used to select nodes
                            exec("sudo wget -qO- https://get.docker.com/ | sh"),    // what you actually intend to run
                            overrideLoginCredentials(login)                         // use my local user & ssh key
                                    .runAsRoot(false)                               // don't attempt to run as root (sudo)
                                    .wrapInInitScript(false));                      // run command directly

                    for (Entry<? extends NodeMetadata, ExecResponse> response : responses.entrySet()) {
                        LOG.info("<< node " + response.getKey().getId() + ": " +
                                "[" + concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()) + "]");
                        System.out.printf("<< node %s: %s%n", response.getKey().getId(),
                                concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()));
                        LOG.info("<<     " + response.getValue());
                        System.out.printf("<<     %s%n", response.getValue());
                    }
                    break;

                case RUN:
                    String info = ">> running [" + scriptName + "] on group " + groupName + " as " + login.identity;
                    LOG.info(info);
                    System.out.printf(">> running [%s] on group %s as %s%n", file, groupName, checkNotNull(login).identity);
                    // when running a sequence of commands, you probably want to have jclouds use the default behavior,
                    // which is to fork a background process.
                    Map<? extends NodeMetadata, ExecResponse> responserun = compute.runScriptOnNodesMatching(//
                            inGroup(groupName),
                            Files.toString(file, Charsets.UTF_8), // passing in a string with the contents of the file
                            overrideLoginCredentials(login)
                                    .runAsRoot(false)
                                    .wrapInInitScript(true) // do not display script content when from jclouds API return result
                                    .nameTask("_" + file.getName().replaceAll("\\..*", "")));   // ensuring task name isn't
                                                                                                // the same as the file so status checking works properly

                    for (Entry<? extends NodeMetadata, ExecResponse> response : responserun.entrySet()) {
                        LOG.info("<< node " + response.getKey().getId() + ": " +
                                "[" + concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()) + "]");
                        System.out.printf("<< node %s: %s%n", response.getKey().getId(),
                                concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()));
                        LOG.info("<<     " + response.getValue());
                        System.out.printf("<<     %s%n", response.getValue());
                    }
                    break;

                case TURNOFF:
        	 	    //before to do turn off the VMs, do stop and remove docker container
                    String infof = ">> turnoff [" + scriptName + "] on group " + groupName + " as " + login.identity;
                    LOG.info(infof);
                    System.out.printf(">> turnoff [%s] on group %s as %s%n", scriptName, groupName, checkNotNull(login).identity);
                    Map<? extends NodeMetadata, ExecResponse> stopandremove = compute.runScriptOnNodesMatching(//
                            inGroup(groupName),
                            Files.toString(file, Charsets.UTF_8),   // passing in a string with the contents of the file
                            overrideLoginCredentials(login)
                                    .runAsRoot(false)
                                    .wrapInInitScript(true)         // do not display script content when from jclouds API return result
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
                    LOG.info("<< turnoff nodes " + turnoffs);
                    System.out.printf("<< turnoff nodes %s%n", turnoffs);

                    break;
                case TURNON:
                    String infoo = ">> turnon [" + scriptName + "] on group " + groupName + " as " + login.identity;
                    LOG.info(infoo);
                    System.out.printf(">> turnon [%s] on group %s as %s%n", scriptName, groupName, checkNotNull(login).identity);

                    // you can use predicates to select which nodes you wish to turn on.
                    Set<? extends NodeMetadata> turnons = compute.resumeNodesMatching(Predicates.and(SUSPENDED, inGroup(groupName)));
                    LOG.info("<< turnon nodes " + turnons);
                    System.out.printf("<< turnon nodes %s%n", turnons);

             		//after nodes are turned on, to start new docker container
                    Map<? extends NodeMetadata, ExecResponse> turnonrun = compute.runScriptOnNodesMatching(//
                            inGroup(groupName),
                            Files.toString(file, Charsets.UTF_8), // passing in a string with the contents of the file
                            overrideLoginCredentials(login)
                                    .runAsRoot(false)
                                    .wrapInInitScript(true) // do not display script content when from jclouds API return result
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
                case LISTNODES:
                    Set<? extends ComputeMetadata> nodes = compute.listNodes();
                    System.out.printf(">> No of nodes/instances %d%n", nodes.size());
                    for (ComputeMetadata nodeData : nodes) {
                        System.out.println(">>>>  " + nodeData);
                    }
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
}
