package org.ngrinder.agent.service.autoscale;

import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.ngrinder.agent.service.AgentManagerService;
import org.ngrinder.infra.config.Config;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Created by junoyoon on 15. 7. 29.
 */
@Component
@Qualifier("mesos")
public class MesosAutoScaleAction extends AgentAutoScaleAction {

    @Override
    public void init(Config config, AgentManagerService agentManagerService) {

    }


    @Override
    public void activateNodes(int count) {

    }

    @Override
    public void suspendNodes() {

    }

    @Override
    public void touch(String name) {

    }
}
