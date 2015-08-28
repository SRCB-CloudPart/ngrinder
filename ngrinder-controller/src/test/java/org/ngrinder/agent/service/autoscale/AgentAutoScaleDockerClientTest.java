package org.ngrinder.agent.service.autoscale;

import com.spotify.docker.client.DockerException;
import com.spotify.docker.client.messages.ContainerInfo;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ngrinder.common.util.PropertiesWrapper;
import org.ngrinder.infra.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.google.common.collect.Lists.newArrayList;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.ngrinder.common.constant.AgentAutoScaleConstants.*;

/**
 * Created by junoyoon on 15. 8. 18.
 * <p/>
 * Modified by shihuc 2015-08-19
 */
public class AgentAutoScaleDockerClientTest {


	private static final Logger LOG = LoggerFactory.getLogger(AgentAutoScaleDockerClientTest.class);

	Config config = mock(Config.class);

	AgentAutoScaleDockerClient dockerClient;

	@Before
	public void init() {
		PropertiesWrapper agentProperties = mock(PropertiesWrapper.class);
		when(config.getAgentAutoScaleProperties()).thenReturn(agentProperties);
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_CONTROLLER_IP)).thenReturn("176.34.4.181");
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_CONTROLLER_PORT)).thenReturn("8080");
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_REPO)).thenReturn("ngrinder/agent");
		when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_TAG)).thenReturn("3.3-p1");
		List<String> address = newArrayList("127.0.0.1");
		Exception ex = null;
		try {
			dockerClient = new AgentAutoScaleDockerClient(config, "hello", address, 10000);
		} catch (Exception e) {
			ex = e;
		}
		assumeNoException(ex);

	}

	@After
	public void clear() {
		IOUtils.closeQuietly(dockerClient);
	}

	@Test
	public void testDockerImageDownload() throws DockerException, InterruptedException {
		dockerClient.createAndStartContainer("wow2");
	}


	@Test
	public void testCreateContainer1() throws DockerException, InterruptedException {
		String containerName = "HelloUT";
		dockerClient.createContainer(containerName);
		dockerClient.removeContainer(containerName);
		LOG.info("createContainer (try branch) is test...");
	}

	@Test
	public void testCreateContainer2() throws DockerException, InterruptedException {
		String containerName = "HelloUT";
		dockerClient.createContainer(containerName);
		dockerClient.startContainer(containerName);
		dockerClient.stopContainer(containerName);
		dockerClient.removeContainer(containerName);
		LOG.info("createContainer (catch branch) is test...");
	}

	@Test
	public void testCreateAndStartContainer() throws DockerException, InterruptedException {
		String containerName = "HelloUT";
		dockerClient.createAndStartContainer(containerName);
		dockerClient.stopContainer(containerName);
		dockerClient.removeContainer(containerName);

	}

	@Test
	public void stopContainerTest() throws DockerException, InterruptedException {
		String containerName = "HelloUT";
		dockerClient.createContainer(containerName);
		dockerClient.startContainer(containerName);
		dockerClient.stopContainer(containerName);
		dockerClient.removeContainer(containerName);

		ContainerInfo ci = dockerClient.inspectContainer(containerName);
		assertTrue(ci == null);
		LOG.info("stopContainer is test...");
	}

	@Test
	public void testStartContainer() throws DockerException, InterruptedException {
		String containerName = "HelloUT";
		dockerClient.createContainer(containerName);
		dockerClient.startContainer(containerName);

		ContainerInfo ci = dockerClient.inspectContainer(containerName);
		assumeTrue(ci != null);
		assertTrue(ci.state().running());

		dockerClient.stopContainer(containerName);
		dockerClient.removeContainer(containerName);

		LOG.info("startContainer is test...");
	}

	@Test
	public void testWaitUtilContainerIsOn() throws DockerException, InterruptedException {
		String containerName = "HelloUT";

		dockerClient.createContainer(containerName);
		dockerClient.startContainer(containerName);
		dockerClient.waitUtilContainerIsOn(containerName);

		ContainerInfo ci = dockerClient.inspectContainer(containerName);
		assumeTrue(ci != null);
		assertTrue(ci.state().running());

		dockerClient.stopContainer(containerName);
		dockerClient.removeContainer(containerName);

		LOG.info("startContainer is test...");
	}
}
