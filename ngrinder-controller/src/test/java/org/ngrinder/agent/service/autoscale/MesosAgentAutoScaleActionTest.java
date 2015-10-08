package org.ngrinder.agent.service.autoscale;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ngrinder.agent.service.AgentAutoScaleService;
import org.ngrinder.common.util.PropertiesWrapper;
import org.ngrinder.common.util.ThreadUtils;
import org.ngrinder.infra.config.Config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.ngrinder.common.constant.AgentAutoScaleConstants.*;


public class MesosAgentAutoScaleActionTest {

	private MesosAutoScaleAction mesosAutoScaleAction;
	private ThreadedScheduledTaskService scheduledTaskService;
	private Config config;


	@Before
	public void setUp() {
		mesosAutoScaleAction = new MesosAutoScaleAction();
		scheduledTaskService = getScheduledTaskService();
		config = mock(Config.class);
		PropertiesWrapper agentProperties = mock(PropertiesWrapper.class);
		when(config.getAgentAutoScaleProperties()).thenReturn(agentProperties);
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_REPO)).thenReturn("ngrinder/agent");
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_TAG)).thenReturn("3.3-p2");
		when(agentProperties.getPropertyInt(PROP_AGENT_AUTO_SCALE_MAX_NODES)).thenReturn(2);
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_MESOS_SLAVE_ATTRIBUTES)).thenReturn("");
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_TYPE)).thenReturn("mesos");
		when(config.getControllerAdvertisedHost()).thenReturn("127.0.0.1");
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_MESOS_MASTER)).thenReturn("172.16.36.71:5050");
		mesosAutoScaleAction.init(config, scheduledTaskService);
	}

	@After
	public void after() {
		scheduledTaskService.destroy();
	}

	protected ThreadedScheduledTaskService getScheduledTaskService() {
		return new ThreadedScheduledTaskService();
	}

	@Test
	public void testActivateNodes() throws AgentAutoScaleService.NotSufficientAvailableNodeException {
		ThreadUtils.sleep(2000);
		mesosAutoScaleAction.activateNodes(1);
	}
}