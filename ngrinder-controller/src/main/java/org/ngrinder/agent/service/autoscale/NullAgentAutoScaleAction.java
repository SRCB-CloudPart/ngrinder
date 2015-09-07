package org.ngrinder.agent.service.autoscale;

import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.schedule.ScheduledTaskService;
import org.ngrinder.perftest.service.AgentManager;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Null object for AgentAutoScaleAction
 *
 * @since 3.3.2
 */
@Qualifier("null")
public class NullAgentAutoScaleAction extends AgentAutoScaleAction {

	private static final AgentAutoScaleAction NULL_AGENT_AUTO_SCALE_ACTION = new NullAgentAutoScaleAction();


	@Override
	public void init(Config config, AgentManager agentManager, ScheduledTaskService scheduledTaskService) {

	}

	@Override
	public void activateNodes(int activateCount, int requiredCount) {
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
		return "null";
	}

	@Override
	public void destroy() {

	}

}
