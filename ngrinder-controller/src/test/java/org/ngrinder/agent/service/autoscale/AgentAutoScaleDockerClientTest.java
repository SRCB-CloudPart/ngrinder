package org.ngrinder.agent.service.autoscale;

import com.beust.jcommander.internal.Lists;
import org.dasein.cloud.network.RawAddress;
import org.junit.Test;
import org.mockito.Mockito;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.config.ConfigTest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Created by junoyoon on 15. 8. 18.
 */
public class AgentAutoScaleDockerClientTest {

	@Test
	public void testDockerImageDownload() {
		Config config = mock(Config.class);
		when(config.getAgentAutoScaleControllerIP()).thenReturn("11.11.11.11");
		when(config.getAgentAutoScaleControllerPort()).thenReturn("80");
		when(config.getAgentAutoScaleDockerRepo()).thenReturn("ngrinder/agent");
		when(config.getAgentAutoScaleDockerTag()).thenReturn("3.3-p1");
		AgentAutoScaleDockerClient client = new AgentAutoScaleDockerClient(config, Lists.newArrayList(new RawAddress("127.0.0.1")));
		client.createAndStartContainer("wow2");
	}
}