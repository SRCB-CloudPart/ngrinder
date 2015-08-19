package org.ngrinder.agent.service.autoscale;

import com.beust.jcommander.internal.Lists;
import com.spotify.docker.client.messages.ContainerInfo;
import org.apache.commons.lang.StringUtils;
import org.dasein.cloud.network.RawAddress;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.ngrinder.common.model.Home;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.config.ConfigTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Created by junoyoon on 15. 8. 18.
 *
 * Modified by shihuc 2015-08-19
 */
public class AgentAutoScaleDockerClientTest {


	private static final Logger LOG = LoggerFactory.getLogger(AgentAutoScaleDockerClientTest.class);

	Config config = mock(Config.class);

	AgentAutoScaleDockerClient dockerClient;

	@Before
	public void init() {

		when(config.getAgentAutoScaleRegion()).thenReturn("ap-southeast-1");
		when(config.getAgentAutoScaleIdentity()).thenReturn(System.getProperty("agent.auto_scale.identity"));
		when(config.getAgentAutoScaleCredential()).thenReturn(System.getProperty("agent.auto_scale.credential"));
		when(config.getAgentAutoScaleControllerIP()).thenReturn("176.34.4.181");
		when(config.getAgentAutoScaleControllerPort()).thenReturn("8080");
		when(config.getAgentAutoScaleMaxNodes()).thenReturn(1);
		when(config.getAgentAutoScaleDockerRepo()).thenReturn("ngrinder/agent");
		when(config.getAgentAutoScaleDockerTag()).thenReturn("3.3");

		String homeDir = System.getProperty("user.home") + "/.ngrinder/";
		File homeFd = new File(homeDir);
		Home home = mock(Home.class);
		when(config.getHome()).thenReturn(home);
		when(config.getHome().getDirectory()).thenReturn(homeFd);

		if (StringUtils.isNotBlank(System.getProperty("controller.proxy_host"))) {
			when(config.getProxyHost()).thenReturn(System.getProperty("controller.proxy_host"));
			when(config.getProxyPort()).thenReturn(Integer.parseInt(System.getProperty("controller.proxy_port")));

			System.setProperty("http.proxyHost", System.getProperty("controller.proxy_host"));
			System.setProperty("http.proxyPort", System.getProperty("controller.proxy_port"));
			System.setProperty("https.proxyHost", System.getProperty("controller.proxy_host"));
			System.setProperty("https.proxyPort", System.getProperty("controller.proxy_port"));
		}

		dockerClient = new AgentAutoScaleDockerClient(config, Lists.newArrayList(new RawAddress("127.0.0.1")));
	}

	@After
	public void clear(){
		System.clearProperty("controller.proxy_host");
		System.clearProperty("controller.proxy_port");

		dockerClient.close();
	}

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

	@Test
	public void constructorTest(){
		assertNotNull(dockerClient);
	}

//	@Test
//	public void constructorTest2(){
//		System.setProperty("controller.proxy_host", "192.168.1.2");
//		System.setProperty("controller.proxy_port", "7071");
//
//		when(config.getProxyHost()).thenReturn(System.getProperty("controller.proxy_host"));
//		when(config.getProxyPort()).thenReturn(Integer.parseInt(System.getProperty("controller.proxy_port")));
//
//		AgentAutoScaleDockerClient dockerClient = new AgentAutoScaleDockerClient(config, Lists.newArrayList(new RawAddress("127.0.0.1")));
//		assertNotNull(dockerClient);
//		dockerClient.close();
//	}

	@Test
	public void createContainerTest1(){
		String containerName = "HelloUT";

		assumeTrue("OK".equalsIgnoreCase(dockerClient.ping()));

		dockerClient.createContainer(containerName);
		String containerId = dockerClient.convertNameToId(containerName);

		assumeTrue(containerId != null);
		dockerClient.removeContainer(containerName);
		LOG.info("createContainer (try branch) is test...");
	}

	@Test
	public void createContainerTest2(){

		String containerName = "HelloUT";

		assumeTrue("OK".equalsIgnoreCase(dockerClient.ping()));

		dockerClient.createContainer(containerName);
		String containerId = dockerClient.convertNameToId(containerName);
		assumeTrue(containerId != null);
		dockerClient.startContainer(containerName);

		dockerClient.createContainer(containerName);
		dockerClient.removeContainer(containerName);

		String containerId2 = dockerClient.convertNameToId(containerName);
		assertTrue(containerId2 == null);
		LOG.info("createContainer (catch branch) is test...");
	}

	@Test
	public void createAndStartContainerTest(){
		String containerName = "HelloUT";

		assumeTrue("OK".equalsIgnoreCase(dockerClient.ping()));

		dockerClient.createAndStartContainer(containerName);
		String containerId = dockerClient.convertNameToId(containerName);
		assumeTrue(containerId != null);

		dockerClient.stopContainer(containerName);
		dockerClient.removeContainer(containerName);

		String containerId2 = dockerClient.convertNameToId(containerName);
		assertTrue(containerId2 == null);
		LOG.info("createAndStartContainer is test...");
	}

	@Test
	public void stopContainerTest(){
		String containerName = "HelloUT";

		assumeTrue("OK".equalsIgnoreCase(dockerClient.ping()));

		dockerClient.createContainer(containerName);
		dockerClient.startContainer(containerName);
		dockerClient.stopContainer(containerName);
		dockerClient.removeContainer(containerName);

		ContainerInfo ci = dockerClient.inspectContainer(containerName);
		assertTrue(ci == null);
		LOG.info("stopContainer is test...");
	}

	@Test
	public void startContainerTest(){
		String containerName = "HelloUT";

		assumeTrue("OK".equalsIgnoreCase(dockerClient.ping()));

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
	public void waitUtilContainerIsOnTest(){
		String containerName = "HelloUT";

		assumeTrue("OK".equalsIgnoreCase(dockerClient.ping()));

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
