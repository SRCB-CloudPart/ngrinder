package org.ngrinder.agent.service;

import org.fest.assertions.Assertions;
import org.junit.Test;
import org.ngrinder.agent.service.AgentAutoScaleService;
import org.ngrinder.agent.service.autoscale.AwsAgentAutoScaleAction;
import org.ngrinder.agent.service.autoscale.NullAgentAutoScaleAction;
import org.ngrinder.common.constant.AgentAutoScaleConstants;
import org.ngrinder.common.util.PropertiesWrapper;
import org.ngrinder.infra.config.Config;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.ngrinder.common.constant.AgentAutoScaleConstants.PROP_AGENT_AUTO_SCALE_TYPE;

/**
 * Created by junoyoon on 15. 8. 4.
 */
public class AgentAutoScaleServiceTest {
	private AgentAutoScaleService agentAutoScaleService = new AgentAutoScaleService();

	@Test
	public void testAgentAutoScaleActionCreation() {
		// Given
		Config config = mock(Config.class);
		PropertiesWrapper agentProperties = mock(PropertiesWrapper.class);
		when(config.getAgentAutoScaleProperties()).thenReturn(agentProperties);

		// When
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_TYPE)).thenReturn("aws");
		agentAutoScaleService.setConfig(config);

		// Then
		assertThat(agentAutoScaleService.createAgentAutoScaleAction()).isInstanceOf(AwsAgentAutoScaleAction.class);

		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_TYPE)).thenReturn("");
		assertThat(agentAutoScaleService.createAgentAutoScaleAction()).isInstanceOf(NullAgentAutoScaleAction.class);

		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_TYPE)).thenReturn("meaningleass");
		assertThat(agentAutoScaleService.createAgentAutoScaleAction()).isInstanceOf(NullAgentAutoScaleAction.class);
	}
}