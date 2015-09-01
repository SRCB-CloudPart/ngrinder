package org.ngrinder.agent.service.autoscale;

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;
import org.ngrinder.common.util.PropertiesWrapper;
import org.ngrinder.infra.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.google.common.collect.Lists.newArrayList;
import static org.junit.Assume.assumeNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.ngrinder.common.constant.AgentAutoScaleConstants.PROP_AGENT_AUTO_SCALE_DOCKER_REPO;
import static org.ngrinder.common.constant.AgentAutoScaleConstants.PROP_AGENT_AUTO_SCALE_DOCKER_TAG;

/**
 * Created by junoyoon on 15. 8. 18.
 * <p/>
 * Modified by shihuc 2015-08-19
 */
public class AwsAgentAutoScaleDockerClientTest {


	private static final Logger LOG = LoggerFactory.getLogger(AwsAgentAutoScaleDockerClientTest.class);

	Config config = mock(Config.class);

	AgentAutoScaleDockerClient dockerClient;

	@Test
	public void testConnection() {
		PropertiesWrapper agentProperties = mock(PropertiesWrapper.class);
		when(config.getAgentAutoScaleProperties()).thenReturn(agentProperties);
		when(config.getControllerAdvertisedHost()).thenReturn("176.34.4.181");
		when(config.getControllerPort()).thenReturn(16001);
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_REPO)).thenReturn("ngrinder/agent");
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_TAG)).thenReturn("3.3-p2");
		if (StringUtils.isNotBlank(System.getProperty("controller.proxy_host"))) {
			when(config.getProxyHost()).thenReturn(System.getProperty("controller.proxy_host"));
			when(config.getProxyPort()).thenReturn(Integer.parseInt(System.getProperty("controller.proxy_port")));
			System.setProperty("http.proxyHost", System.getProperty("controller.proxy_host"));
			System.setProperty("http.proxyPort", System.getProperty("controller.proxy_port"));
			System.setProperty("https.proxyHost", System.getProperty("controller.proxy_host"));
			System.setProperty("https.proxyPort", System.getProperty("controller.proxy_port"));
		}
		List<String> address = newArrayList("54.251.21.16");
		Exception ex = null;
		try {
			dockerClient = new AgentAutoScaleDockerClient(config, "hello", address, 10000);
		} catch (Exception e) {
			ex = e;
		}
		assumeNoException(ex);
	}

}
