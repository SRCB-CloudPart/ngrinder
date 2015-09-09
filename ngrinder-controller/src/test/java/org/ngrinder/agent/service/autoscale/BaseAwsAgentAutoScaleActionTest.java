package org.ngrinder.agent.service.autoscale;

import com.google.common.collect.Sets;
import net.grinder.common.processidentity.AgentIdentity;
import net.grinder.engine.controller.AgentControllerIdentityImplementation;
import org.apache.commons.lang3.StringUtils;
import org.junit.Before;
import org.ngrinder.common.util.PropertiesWrapper;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.schedule.ScheduledTaskService;
import org.ngrinder.perftest.service.AgentManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.ngrinder.common.constant.AgentAutoScaleConstants.*;

public class BaseAwsAgentAutoScaleActionTest {


	protected AwsAgentAutoScaleAction awsAgentAutoScaleAction;
	private Config config;
	private AgentManager agentManager;
	private ScheduledTaskService scheduledTaskService;

	@Before
	public void init() {
		awsAgentAutoScaleAction = createAction();

		config = mock(Config.class);
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
		agentManager = mock(AgentManager.class);
		scheduledTaskService = getScheduledTaskService();
		awsAgentAutoScaleAction.init(config, scheduledTaskService);
	}

	protected ScheduledTaskService getScheduledTaskService() {
		return new MockScheduledTaskService();
	}

	protected AwsAgentAutoScaleAction createAction() {
		return new AwsAgentAutoScaleAction();
	}
}
