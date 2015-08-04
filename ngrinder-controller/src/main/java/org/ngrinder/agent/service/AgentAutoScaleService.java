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
package org.ngrinder.agent.service;

import org.ngrinder.agent.service.autoscale.NullAgentAutoScaleAction;
import org.ngrinder.infra.config.Config;
import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

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
@Component("agentAutoScaleService")
public class AgentAutoScaleService {
    private static final Logger LOG = LoggerFactory.getLogger(AgentAutoScaleService.class);

    @Autowired
    Config config;

    @Autowired
    private AgentManagerService agentManagerService;

    private static final AgentAutoScaleAction NULL_AGENT_AUTO_SCALE_ACTION = new NullAgentAutoScaleAction();
    private AgentAutoScaleAction agentAutoScaleAction = NULL_AGENT_AUTO_SCALE_ACTION;
    private Set<Class<? extends AgentAutoScaleAction>> agentAutoScaleActions = new Reflections("org.ngrinder.agent.service.autoscale").getSubTypesOf(AgentAutoScaleAction.class);
    private ReentrantLock lock = new ReentrantLock();


    @PostConstruct
    public void init() {
        config.addSystemConfListener(new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                initAgentAutoScaleService();
            }
        });
        initAgentAutoScaleService();
    }

    void initAgentAutoScaleService() {
        agentAutoScaleAction = createAgentAutoScaleAction();
        agentAutoScaleAction.init(config, agentManagerService);
    }


    AgentAutoScaleAction createAgentAutoScaleAction() {
        AgentAutoScaleAction action = NULL_AGENT_AUTO_SCALE_ACTION;
        if (config.isAgentAutoScaleEnabled()) {
            String agentAutoScaleType = config.getAgentAutoScaleType();
            try {
                Class<? extends AgentAutoScaleAction> type = NullAgentAutoScaleAction.class;
                for (Class<? extends AgentAutoScaleAction> each : agentAutoScaleActions) {
                    Qualifier annotation = each.getAnnotation(Qualifier.class);
                    if (annotation.value().equals(agentAutoScaleType)) {
                        type = each;
                        break;
                    }
                }
                action = type.newInstance();
            } catch (InstantiationException e) {
                throw processException(e);
            } catch (IllegalAccessException e) {
                throw processException(e);
            }
        }
        return action;
    }

    public void touchNode(String name) {
        agentAutoScaleAction.touch(name);
    }


    public void activateNodes(int count) {
        lock.lock();
        try {
            agentAutoScaleAction.activateNodes(count);
        } finally {
            lock.unlock();
        }
    }

    public boolean isInProgress() {
        return lock.isLocked();
    }
}
