package org.ngrinder.agent.service.autoscale;

import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Created by junoyoon on 15. 7. 29.
 */
@Component
@Qualifier("mesos")
public class MesosAutoScaleAction extends AgentAutoScaleAction {


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
}
