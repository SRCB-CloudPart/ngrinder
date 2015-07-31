package org.ngrinder.agent.service.autoscale;

import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.ngrinder.infra.config.Config;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Created by junoyoon on 15. 7. 29.
 */
public class NullAgentAutoScaleAction extends AgentAutoScaleAction {

    private static final AgentAutoScaleAction NULL_AGENT_AUTO_SCALE_ACTION = new NullAgentAutoScaleAction();

    @Override
    public void initNodes(int count) {
    }

    @Override
    public void activateNodes(int count) {
    }

    @Override
    public void suspendNodes(int count) {
    }

    @Override
    public boolean isInProgress() {
        return false;
    }

    @Override
    public void init(Config config) {

    }
}
