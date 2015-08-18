package org.ngrinder.agent.service.autoscale;

import com.spotify.docker.client.ContainerNotFoundException;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.ProxyAwareDockerClient;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerInfo;
import org.apache.commons.io.IOUtils;
import org.ngrinder.common.exception.NGrinderRuntimeException;
import org.ngrinder.common.util.ThreadUtils;
import org.ngrinder.infra.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.ngrinder.common.util.ExceptionUtils.processException;

/**
 * This class is used to control the docker daemon which locates in the created AWS VM. The agent is
 * is running in the docker container running in VM.
 *
 * @author shihuc
 * @version 8/14/15 v1
 */
public class AgentAutoScaleDockerClient {
	private static final Logger LOG = LoggerFactory.getLogger(AgentAutoScaleDockerClient.class);

	private Config config;
	private String serverIP;

	private DockerClient dockerClient;

	/*
	 * Consider the condition when VM is activated, and then to start docker, this moment, the network
	 * or docker daemon in VM may not ready, so, set the connect timeout longer is reasonable.
	 */
	private static final long CONNECT_TIMEOUT_MILLIS = 10 * 60 * 1000;
	private static final long READ_TIMEOUT_MILLIS = 120000;

	private static final int DAEMON_TCP_PORT = 10000;

	/*
	 * The docker image repository which identifies which image to run agent
	 */
	private String image;
	private String controllerUrl;

	/**
	 * Constructor function, in this function to do docker client api initialization.
	 *
	 * @param config   used to specify which docker image will be used
	 * @param serverIP the docker daemon address
	 */
	public AgentAutoScaleDockerClient(Config config, String serverIP) {
		this.config = config;
		this.serverIP = serverIP;
		this.image = this.config.getAgentAutoScaleDockerRepo() + ":" + this.config.getAgentAutoScaleDockerTag();
		String daemonUri = "http://" + serverIP + ":" + DAEMON_TCP_PORT;

		controllerUrl = config.getAgentAutoScaleControllerIP() + ":" + config.getAgentAutoScaleControllerPort();
		dockerClient = ProxyAwareDockerClient.builder()
				.proxyHost(config.getProxyHost())
				.proxyPort(config.getProxyPort())
				.uri(daemonUri)
				.connectTimeoutMillis(CONNECT_TIMEOUT_MILLIS)
				.readTimeoutMillis(READ_TIMEOUT_MILLIS)
				.build();

	}

	/**
	 * This function is used to close the docker client api HTTP connection. Required to call after usage.
	 */
	public void close() {
		IOUtils.closeQuietly(dockerClient);
	}

	/**
	 * This function is used to create and start one docker container.
	 * If the container exists, it skips the creation.
	 *
	 * @param containerId the container id or name which to start
	 */
	public void createAndStartContainer(String containerId) {
		createContainer(containerId);
		startContainer(containerId);
	}

	/**
	 * Stop the specified docker container.
	 *
	 * @param containerId the container id or name of which to be stopped
	 */
	public void stopContainer(String containerId)  {
		try {
			dockerClient.stopContainer(containerId, 1);
			LOG.info("Stop docker container: {}", containerId);
		} catch (Exception e) {
			throw processException(e);
		}
	}

	/**
	 * Start the specified docker container.
	 *
	 * @param containerId the container id or name of which to be started
	 */
	protected void startContainer(String containerId) {
		try {
			dockerClient.startContainer(containerId);
		} catch (Exception e) {
			throw processException(e);
		}
	}

	/**
	 * Create the docker container with the given name.
	 *
	 * @param containerId the container id or name of which to be created
	 */
	protected void createContainer(String containerId) {
		LOG.info("Create docker container: {}", containerId);
		try {
			try {
				final ContainerInfo containerInfo = dockerClient.inspectContainer(containerId);
				if (containerInfo.state().running()) {
					LOG.info("Container {} is already running, stop it", containerId);
					dockerClient.stopContainer(containerId, 0);
				}
				ContainerConfig containerConfig = ContainerConfig.builder()
						.image(image)
						.env("CONTROLLER_ADDR=" + controllerUrl)
						.hostname(serverIP)
						.build();
				dockerClient.createContainer(containerConfig);
				LOG.info("Container {} is already created", containerId);
			} catch (ContainerNotFoundException e) {
				LOG.info("Container {} already exist, skip creation", containerId);
			}

		} catch (Exception e) {
			throw processException(e);
		}
	}

	public ContainerInfo waitUtilContainerIsOn(String containerId) {
		try {
			for (int i = 0; i < 5; i++) {
				final ContainerInfo containerInfo = dockerClient.inspectContainer(containerId);
				if (containerInfo.state().running()) {
					return containerInfo;
				}
				ThreadUtils.sleep(1000);
				if (i++ >= 5) {
					throw new NGrinderRuntimeException("container " + containerId + " is failed to run");
				}
			}
		} catch (Exception e) {
			throw processException(e);
		}
		return null;
	}

}
