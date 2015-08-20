package org.ngrinder.agent.service.autoscale;

import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.ngrinder.agent.service.AgentManagerService;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.schedule.ScheduledTaskService;
import org.ngrinder.perftest.service.AgentManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Created by junoyoon on 15. 7. 29.
 */
@Qualifier("mesos")
public class MesosAutoScaleAction extends AgentAutoScaleAction {


	@Override
	public void init(Config config, AgentManager agentManager, ScheduledTaskService scheduledTaskService) {

	}

	@Override
	public void activateNodes(int count) {

	}

	@Override
	public void suspendAllNodes() {

	}

	@Override
	public void touch(String name) {

	}

	@Override
	public boolean isPrepared() {
		return false;
	}

	@Override
	public String getDiagnosticInfo() {
		return "mesos";
	}
}
