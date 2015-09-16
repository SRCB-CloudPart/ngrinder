package org.ngrinder.agent.service.autoscale;

import org.junit.Test;
import org.ngrinder.agent.model.AutoScaleNode;
import org.ngrinder.agent.service.AgentAutoScaleService;

import static org.fest.assertions.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assume.assumeThat;


public class AwsAgentAutoScaleActionTest extends BaseAwsAgentAutoScaleActionTest {


	protected AwsAgentAutoScaleAction createAction() {
		return new AwsAgentAutoScaleAction();
	}

	@Test
	public void testActivateNodes() throws AgentAutoScaleService.NotSufficientAvailableNodeException {
		assumeThat(awsAgentAutoScaleAction.getActivatableNodeCount(), greaterThan(1));
		awsAgentAutoScaleAction.activateNodes(2);
	}

	@Test
	public void testStopNodes() {
		final int maxNodeCount = awsAgentAutoScaleAction.getMaxNodeCount();
		assumeThat(maxNodeCount, greaterThan(1));
		for (AutoScaleNode each : awsAgentAutoScaleAction.getNodes()) {
			awsAgentAutoScaleAction.stopNode(each.getId());
		}
		assertThat(awsAgentAutoScaleAction.getMaxNodeCount()).isEqualTo(maxNodeCount);
	}
}