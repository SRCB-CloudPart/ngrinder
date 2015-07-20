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
import com.google.common.io.Files;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.RunScriptOnNodesException;
import org.jclouds.compute.domain.*;
import org.jclouds.domain.LoginCredentials;
import org.jclouds.ec2.domain.InstanceType;
import org.jclouds.scriptbuilder.domain.Statement;
import org.ngrinder.common.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Lists.newArrayList;
import static org.jclouds.compute.options.TemplateOptions.Builder.overrideLoginCredentials;
import static org.jclouds.compute.predicates.NodePredicates.RUNNING;
import static org.jclouds.compute.predicates.NodePredicates.SUSPENDED;
import static org.jclouds.ec2.compute.options.EC2TemplateOptions.Builder.runScript;

/**
 * Created on 15-7-20.
 * This enum is for the dynamic agent handler class to do related operation according to the command name
 *
 * @author shihuc
 * @version 3.4
 */
public enum Action {
    ADD {
        private ComputeService compute = null;
        private File scriptFile = null;
        private String groupName = null;
        private Statement adminAccessStmt = null;

        @Override
        public void setComputeService(ComputeService computeService){
            this.compute = checkNotNull(computeService, "compute service should be initialized before to use");
        }
        @Override
        public void setScriptFile(File file){
            this.scriptFile = checkNotNull(file, "script file should be prepared before to use");
        }
        @Override
        public void setGroupName(String name){
            this.groupName = checkNotNull(name, "group name should be generated before to use");
        }
        @Override
        public void setAdminAccessStmt(Statement stmt){
            this.adminAccessStmt = checkNotNull(stmt, "admin access statement should be prepared before to use");
        }

        @Override
        public void takeAction(String testIdentifier, List<String> nodeIds) {
            LOG.info(">> begin to add on operation....");

            checkNotNull(compute, "compute service is not initialized");
            checkNotNull(scriptFile, "script file is not initialized");
            checkNotNull(groupName, "group name is not initialized");
            checkNotNull(adminAccessStmt, "admin access statement is not initialized");

            String scriptName = scriptFile.getName();
            Map<String, Long>newAddedNodeStatus = testIdEc2NodeStatus.get(testIdentifier);
            checkNotNull(newAddedNodeStatus, "test identifier mapped node status map must be initialized");

            checkNotNull(addOnOffLogin, "login is invalid, check user home and ssh path or ssh key whether they are existing");
            checkNotNull(nodeIds, "should provide node count to create via jclouds");
            LOG.info(">> prepare to add {} node to group {}", nodeIds.size(), groupName);

            TemplateBuilder templateBuilder = compute.templateBuilder()
                    .locationId("ap-southeast-1").hardwareId(InstanceType.M1_MEDIUM);

            Statement bootInstructions = adminAccessStmt;
            templateBuilder.options(runScript(bootInstructions));
            Template template = templateBuilder.build();

            LOG.info(">> begin to create {} in group {}", nodeIds.size(), groupName);
            addedNodeCount.getAndAdd(nodeIds.size());
            List<String> addIds = newArrayList();
            Set<? extends NodeMetadata> nodes;
            try {
                nodes = compute.createNodesInGroup(groupName, nodeIds.size(), template);
            } catch (RunNodesException e) {
                LOG.debug(e.getMessage());
                throw ExceptionUtils.processException("RunNodesException: Create ec2 node error");
            }

            long addTimeStamp = System.currentTimeMillis();
            for (NodeMetadata node : nodes) {
                String id = node.getId();
                String ip = getPrueIpString(node.getPrivateAddresses().toString());
                LOG.info("<< added node: {} {}", id, concat(node.getPrivateAddresses(), node.getPublicAddresses()));
                addIds.add(id);
                runningIdNodes.put(id, ip);
                newAddedNodeStatus.put(ip, addTimeStamp);
            }

            LOG.info(">> exec {} to initialize nodes as {}", scriptName, addOnOffLogin.identity);
            Map<? extends NodeMetadata, ExecResponse> responseRun;
            try {
                responseRun = compute.runScriptOnNodesMatching(
                        inGivenList(addIds), Files.toString(scriptFile, Charsets.UTF_8),
                        overrideLoginCredentials(addOnOffLogin).runAsRoot(false)
                                .nameTask("_" + scriptName.replaceAll("\\..*", "")));
            } catch (RunScriptOnNodesException e) {
                LOG.debug(e.getMessage());
                throw ExceptionUtils.processException("RunScriptOnNodesException: Run script on nodes error");
            } catch (IOException e) {
                LOG.debug(e.getMessage());
                throw ExceptionUtils.processException("IOException: File IO error");
            }

            for (Map.Entry<? extends NodeMetadata, ExecResponse> response : responseRun.entrySet()) {
                LOG.info("<< {} status {}", response.getKey().getId(), response.getValue());
            }
        }
    },
    ON {
        private ComputeService compute = null;
        private File scriptFile = null;
        private String groupName = null;

        @Override
        public void takeAction(String testIdentifier, List<String> nodeIds) {
            LOG.info(">> begin to do on operation....");
            checkNotNull(compute, "compute service is not initialized");
            checkNotNull(scriptFile, "script file is not initialized");
            checkNotNull(groupName, "group name is not initialized");

            String scriptName = scriptFile.getName();

            Map<String, Long> newAddedNodeStatus = testIdEc2NodeStatus.get(testIdentifier);
            checkNotNull(newAddedNodeStatus, "test identifier mapped node status map must be initialized");

            checkNotNull(addOnOffLogin, "login is invalid, check user home and ssh path or ssh key whether they are existing");
            LOG.info(">> begin to turn on node(s) in group {} as {}", groupName, addOnOffLogin.identity);
            LOG.info(">> nodeIdList content: {}", nodeIds);
            Set<? extends NodeMetadata> turnOn = compute.resumeNodesMatching(Predicates.and(SUSPENDED, inGivenList(nodeIds)));
            long onTimeStamp = System.currentTimeMillis();
            for (NodeMetadata node : turnOn) {
                String id = node.getId();
                String ip = getPrueIpString(node.getPrivateAddresses().toString());
                LOG.info("<< turned on node: {}", id);
                runningIdNodes.put(id, ip);
                stoppedIdNodes.remove(id);
                newAddedNodeStatus.put(ip, onTimeStamp);
            }

            LOG.info(">> exec {} to initialize nodes", scriptName);
            //after nodes are turned on, to start new docker container
            Map<? extends NodeMetadata, ExecResponse> turnOnRun;
            try {
                turnOnRun = compute.runScriptOnNodesMatching(
                        inGivenList(nodeIds), Files.toString(scriptFile, Charsets.UTF_8), // passing in a string with the contents of the file
                        overrideLoginCredentials(addOnOffLogin).runAsRoot(false)
                                .nameTask("_" + scriptName.replaceAll("\\..*", "")));
            } catch (RunScriptOnNodesException e) {
                LOG.debug(e.getMessage());
                throw ExceptionUtils.processException("RunScriptOnNodesException: Run script on nodes error");
            } catch (IOException e) {
                LOG.debug(e.getMessage());
                throw ExceptionUtils.processException("IOException: File IO error");
            }

            for (Map.Entry<? extends NodeMetadata, ExecResponse> response : turnOnRun.entrySet()) {
                LOG.info("<< initialized node {}: {}", response.getKey().getId(),
                        concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()));
                LOG.info("<< {}", response.getValue());
            }
        }

        @Override
        public void setComputeService(ComputeService computeService){
            this.compute = checkNotNull(computeService, "compute service should be initialized before to use");
        }
        @Override
        public void setScriptFile(File file){
            this.scriptFile = checkNotNull(file, "on script file should be prepared before to use");
        }
        @Override
        public void setGroupName(String name){
            this.groupName = checkNotNull(name, "group name should be generated before to use");
        }
    },
    OFF {
        private File scriptFile = null;
        private String groupName = null;
        private ComputeService compute = null;

        @Override
        public void takeAction(String testIdentifier, List<String> nodeIds) {
            LOG.info(">> begin to do off operation....");
            checkNotNull(compute, "compute service is not initialized");
            checkNotNull(scriptFile, "script file is not initialized");
            checkNotNull(groupName, "group name is not initialized");

            //1. before to do turn off the VMs, do stop and remove docker container
            //2. turn off operation will suspend all the nodes in the given group
            String scriptName = scriptFile.getName();
            LOG.info(">> exec {} as {} ", scriptName, addOnOffLogin.identity);
            Map<? extends NodeMetadata, ExecResponse> stopAndRemove;
            try {
                stopAndRemove = compute.runScriptOnNodesMatching(
                        inGivenList(nodeIds),
                        Files.toString(scriptFile, Charsets.UTF_8),   // passing in a string with the contents of the file
                        overrideLoginCredentials(addOnOffLogin).runAsRoot(false)
                                .nameTask("_" + scriptName.replaceAll("\\..*", "")));
            } catch (RunScriptOnNodesException e) {
                LOG.debug(e.getMessage());
                throw ExceptionUtils.processException("RunScriptOnNodesException: Run script on nodes error");
            } catch (IOException e) {
                LOG.debug(e.getMessage());
                throw ExceptionUtils.processException("IOException: File IO error");
            }

            for (Map.Entry<? extends NodeMetadata, ExecResponse> response : stopAndRemove.entrySet()) {
                String id = response.getKey().getId();
                LOG.info("<< node: {} {}", id, concat(response.getKey().getPrivateAddresses(), response.getKey().getPublicAddresses()));
                LOG.info("<< stop and remove status: {}", response.getValue());
            }

            // you can use predicates to select which nodes you wish to turn off.
            LOG.info(">> begin to turn off nodes in group {}", groupName);
            Set<? extends NodeMetadata> turnOff = compute.suspendNodesMatching(Predicates.and(RUNNING, inGivenList(nodeIds)));
            for (NodeMetadata node : turnOff) {
                String id = node.getId();
                String tip = node.getPrivateAddresses().toString();
                LOG.info("<< turn off node {}", node);
                stoppedIdNodes.put(id, getPrueIpString(tip));
                runningIdNodes.remove(id);
            }
            turningOnNodes.clear();
        }

        @Override
        public void setComputeService(ComputeService computeService){
            this.compute = checkNotNull(computeService, "compute service should be initialized before to use");
        }
        @Override
        public void setScriptFile(File file){
            this.scriptFile = checkNotNull(file, "off script file should be prepared before to use");
        }
        @Override
        public void setGroupName(String name){
            this.groupName = checkNotNull(name, "group name should be generated before to use");
        }
    },
    DESTROY {
        private String groupName = null;
        private ComputeService compute = null;

        @Override
        public void takeAction(String testIdentifier, List<String> nodeIds) {
            LOG.info(">> begin to do destroy operation....");
            checkNotNull(compute, "compute service is not initialized");
            checkNotNull(groupName, "group name is not initialized");
            checkNotNull(nodeIds, "which node(s) will be terminated should be specified");

            LOG.info(">> destroy {} nodes in group {}", nodeIds.size(), groupName);
            // you can use predicates to select which nodes you wish to destroy.
            Set<? extends NodeMetadata> destroyed = compute.destroyNodesMatching(inGivenList(nodeIds));
            for (NodeMetadata node : destroyed) {
                String id = node.getId();
                runningIdNodes.remove(id);
                stoppedIdNodes.remove(id);
                turningOnNodes.remove(id);
                addedNodeCount.getAndDecrement();
                LOG.info("<< destroyed node: {}", node);
            }
            LOG.info("<< nodes are destroyed... ");
        }

        @Override
        public void setComputeService(ComputeService computeService){
            this.compute = checkNotNull(computeService, "compute service should be initialized before to use");
        }
        @Override
        public void setGroupName(String name){
            this.groupName = checkNotNull(name, "group name should be generated before to use");
        }
    },
    LIST {
        private String groupName = null;
        private ComputeService compute = null;

        @Override
        public void takeAction(String testIdentifier, List<String> nodeIds) {
            LOG.info(">> begin to do list operation....");
            checkNotNull(compute, "compute service is not initialized");
            checkNotNull(groupName, "group name is not initialized");

            Map<String, Long> newAddedNodeStatus = testIdEc2NodeStatus.get(testIdentifier);
            checkNotNull(newAddedNodeStatus, "test identifier mapped node status map must be initialized");

            LOG.info(">> begin to list nodes status in group {}", groupName);
            Set<? extends NodeMetadata> gnodes = compute.listNodesDetailsMatching(nodeNameStartsWith(groupName));
            LOG.info(">> total number nodes/instances {} in group {}", gnodes.size(), groupName);
            long listTimeStamp = System.currentTimeMillis();
            for (NodeMetadata nodeData : gnodes) {
                LOG.info("    >> " + nodeData);
                NodeMetadata.Status status = nodeData.getStatus();
                String ip = getPrueIpString(nodeData.getPrivateAddresses().toString());
                if (status == NodeMetadata.Status.RUNNING) {
                    runningIdNodes.put(nodeData.getId(), ip);
                    newAddedNodeStatus.put(ip, listTimeStamp);
                } else if (status == NodeMetadata.Status.SUSPENDED) {
                    stoppedIdNodes.put(nodeData.getId(), ip);
                }
            }
            addedNodeCount.getAndSet(runningIdNodes.size() + stoppedIdNodes.size());
            LOG.info(">> total number available {} nodes in group {}", addedNodeCount.get(), groupName);
        }

        @Override
        public void setComputeService(ComputeService computeService){
            this.compute = checkNotNull(computeService, "compute service should be initialized before to use");
        }
        @Override
        public void setGroupName(String name){
            this.groupName = checkNotNull(name, "group name should be generated before to use");
        }
    };

    public abstract void takeAction(String testIdentifier, List<String> nodeIds);

    private static Logger LOG = LoggerFactory.getLogger(Action.class);

    private static ConcurrentHashMap<String, String> runningIdNodes;
    private static ConcurrentHashMap<String, String> stoppedIdNodes;
    private static ConcurrentSkipListSet<String> turningOnNodes;
    private static ConcurrentHashMap<String, Map<String, Long>> testIdEc2NodeStatus;
    private static AtomicInteger addedNodeCount;
    private static LoginCredentials addOnOffLogin;

    public static void setClassParameters(ConcurrentHashMap<String, String> rMap, ConcurrentHashMap<String, String> sMap,
                                    ConcurrentSkipListSet<String> tSet, ConcurrentHashMap<String, Map<String, Long>> testStatusMap,
                                          AtomicInteger count, LoginCredentials login) {
        runningIdNodes = rMap;
        stoppedIdNodes = sMap;
        turningOnNodes = tSet;
        testIdEc2NodeStatus = testStatusMap;
        addedNodeCount = count;
        addOnOffLogin = login;
    }

    public abstract void setComputeService(ComputeService computeService);

    public void setScriptFile(File file){}

    public abstract void setGroupName(String name);

    public void setAdminAccessStmt(Statement stmt){}

    protected static Predicate<ComputeMetadata> nodeNameStartsWith(final String nodeNamePrefix) {
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

    protected static Predicate<NodeMetadata> inGivenList(final List<String> givenList) {
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

    String getPrueIpString(String tip) {
        String ip = tip.replace("[", "");
        ip = ip.replace("]", "");
        LOG.info("tip: {}, ip: {}", tip, ip);
        return ip;
    }
}