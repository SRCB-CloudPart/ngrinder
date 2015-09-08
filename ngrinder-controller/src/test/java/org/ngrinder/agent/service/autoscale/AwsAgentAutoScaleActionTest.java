package org.ngrinder.agent.service.autoscale;

import com.google.common.collect.Sets;
import net.grinder.common.processidentity.AgentIdentity;
import net.grinder.engine.controller.AgentControllerIdentityImplementation;
import org.apache.commons.lang3.StringUtils;
import org.junit.Before;
import org.junit.Test;
import org.ngrinder.agent.model.AutoScaleNode;
import org.ngrinder.agent.service.AgentAutoScaleService;
import org.ngrinder.common.util.PropertiesWrapper;
import org.ngrinder.infra.config.Config;
import org.ngrinder.perftest.service.AgentManager;

import static org.fest.assertions.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assume.assumeThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.ngrinder.common.constant.AgentAutoScaleConstants.*;


/**
 * Created by junoyoon on 15. 8. 4.
 */
public class AwsAgentAutoScaleActionTest {

	private AwsAgentAutoScaleAction awsAgentAutoScaleAction = new AwsAgentAutoScaleAction();

	@Before
	public void init() {
		Config config = mock(Config.class);
		PropertiesWrapper agentProperties = mock(PropertiesWrapper.class);
		when(config.getAgentAutoScaleProperties()).thenReturn(agentProperties);

		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_REGION)).thenReturn("ap-southeast-1");
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_IDENTITY)).thenReturn(System.getProperty("agent.auto_scale.identity"));
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_CREDENTIAL)).thenReturn(System.getProperty("agent.auto_scale.credential"));
		when(config.getControllerAdvertisedHost()).thenReturn("10.251.51.115");
		when(config.getControllerPort()).thenReturn(16001);
		when(config.getRegion()).thenReturn("NONE");
		when(agentProperties.getPropertyInt(PROP_AGENT_AUTO_SCALE_DOCKER_DAEMON_PORT)).thenReturn(10000);
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_REPO)).thenReturn("ngrinder/agent");
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_TAG)).thenReturn("3.3-p1");
		when(agentProperties.getPropertyInt(PROP_AGENT_AUTO_SCALE_MAX_NODES)).thenReturn(2);
		if (StringUtils.isNotBlank(System.getProperty("controller.proxy_host"))) {
			when(config.getProxyHost()).thenReturn(System.getProperty("controller.proxy_host"));
			when(config.getProxyPort()).thenReturn(Integer.parseInt(System.getProperty("controller.proxy_port")));
			System.setProperty("http.proxyHost", System.getProperty("controller.proxy_host"));
			System.setProperty("http.proxyPort", System.getProperty("controller.proxy_port"));
			System.setProperty("https.proxyHost", System.getProperty("controller.proxy_host"));
			System.setProperty("https.proxyPort", System.getProperty("controller.proxy_port"));
		}
		AgentManager agentManager = mock(AgentManager.class);
		when(agentManager.getAllFreeAgents()).thenReturn(Sets.<AgentIdentity>newHashSet(new AgentControllerIdentityImplementation("ww", "10")));
		MockScheduledTaskService scheduledTaskService = new MockScheduledTaskService();
		awsAgentAutoScaleAction.init(config, agentManager, scheduledTaskService);

	}

	@Test
	public void testActivateNodes() throws AgentAutoScaleService.NotSufficientAvailableNodeException {
		assumeThat(awsAgentAutoScaleAction.getActivatableNodeCount(), greaterThan(1));
		awsAgentAutoScaleAction.activateNodes(2, 2);
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