package org.ngrinder.agent.service.autoscale;

import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.ngrinder.agent.service.AgentManagerService;
import org.ngrinder.infra.config.Config;

/**
 * Created by junoyoon on 15. 7. 29.
 */
public class NullAgentAutoScaleAction extends AgentAutoScaleAction {

    private static final AgentAutoScaleAction NULL_AGENT_AUTO_SCALE_ACTION = new NullAgentAutoScaleAction();


    @Override
    public void activateNodes(int count) {
    }

    @Override
    public void suspendNodes() {

    }

    @Override
    public void init(Config config, AgentManagerService agentManagerService) {

    }
}
