package org.ngrinder.agent.service.autoscale;

import com.google.common.collect.Sets;
import net.grinder.common.processidentity.AgentIdentity;
import net.grinder.engine.controller.AgentControllerIdentityImplementation;
import org.apache.commons.lang3.StringUtils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.ngrinder.agent.model.AutoScaleNode;
import org.ngrinder.agent.service.AgentAutoScaleService;
import org.ngrinder.common.util.PropertiesWrapper;
import org.ngrinder.infra.config.Config;
import org.ngrinder.perftest.service.AgentManager;

import java.util.concurrent.TimeUnit;

import static com.google.common.cache.CacheBuilder.newBuilder;
import static org.fest.assertions.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assume.assumeThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.ngrinder.common.constant.AgentAutoScaleConstants.*;
import static org.ngrinder.common.util.Suppliers.memoizeWithExpiration;
import static org.ngrinder.common.util.Suppliers.synchronizedSupplier;


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