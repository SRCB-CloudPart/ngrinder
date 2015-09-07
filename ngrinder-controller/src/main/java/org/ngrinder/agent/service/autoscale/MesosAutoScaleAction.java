package org.ngrinder.agent.service.autoscale;

import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.ngrinder.agent.service.AgentAutoScaleService;
import org.ngrinder.agent.service.AgentManagerService;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.schedule.ScheduledTaskService;
import org.ngrinder.perftest.service.AgentManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Mesos AgentAutoScaleAction
 *
 * @since 3.3.2
 */
@Qualifier("mesos")
public class MesosAutoScaleAction extends AgentAutoScaleAction {


	@Override
	public void init(Config config, AgentManager agentManager, ScheduledTaskService scheduledTaskService) {
	}

	@Override
	public void activateNodes(int activateCount, int requiredCount) throws AgentAutoScaleService.NotSufficientAvailableNodeException {
	}

	@Override
	public int getMaxNodeCount() {
		return 0;
	}

	@Override
	public int getActivatableNodeCount() {
		return 0;
	}

	@Override
	public void touch(String name) {
	}


	@Override
	public String getDiagnosticInfo() {
		return "mesos";
	}

	@Override
	public void destroy() {
	}
}
