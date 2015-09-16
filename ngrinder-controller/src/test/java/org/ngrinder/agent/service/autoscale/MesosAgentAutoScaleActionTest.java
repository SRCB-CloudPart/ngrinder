package org.ngrinder.agent.service.autoscale;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.ngrinder.agent.model.AutoScaleNode;
import org.ngrinder.agent.service.AgentAutoScaleService;
import org.ngrinder.common.util.PropertiesWrapper;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.schedule.ScheduledTaskService;

import static org.fest.assertions.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assume.assumeThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.ngrinder.common.constant.AgentAutoScaleConstants.*;


public class MesosAgentAutoScaleActionTest{

	MesosAutoScaleAction mesosAutoScaleAction;
	private ScheduledTaskService scheduledTaskService;

	String perfTestId = "PerfTest_1_guest";

	private Config config;


	@Before
	public void setUp(){
		mesosAutoScaleAction = new MesosAutoScaleAction();
		config = mock(Config.class);

		PropertiesWrapper agentProperties = mock(PropertiesWrapper.class);
		when(config.getAgentAutoScaleProperties()).thenReturn(agentProperties);
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_REPO)).thenReturn("ngrinder/agent");
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_TAG)).thenReturn("3.3-p2");
		when(agentProperties.getPropertyInt(PROP_AGENT_AUTO_SCALE_MAX_NODES)).thenReturn(2);

		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_TYPE)).thenReturn("mesos");
		when(config.getControllerAdvertisedHost()).thenReturn("176.34.4.181");
		when(agentProperties.getPropertyInt(PROP_AGENT_AUTO_SCALE_CONTROLLER_URL_PORT)).thenReturn(8080);
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_MESOS_FRAMEWORK_NAME)).thenReturn("nGrinder Scheduler");
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_MESOS_LIB_PATH)).thenReturn("/usr/local/mesos-0.20.0/lib/libmesos-0.20.0.so");
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_MESOS_MASTER)).thenReturn("54.179.230.7:5050");

		scheduledTaskService = getScheduledTaskService();
		mesosAutoScaleAction.init(config, scheduledTaskService);
	}

	protected ScheduledTaskService getScheduledTaskService() {
		return new MockScheduledTaskService();
	}

	@Test
	public void testActivateNodes() throws AgentAutoScaleService.NotSufficientAvailableNodeException {
		mesosAutoScaleAction.activateNodes(1);
	}
}