package org.ngrinder.agent.service.autoscale;

import com.spotify.docker.client.*;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerInfo;
import com.spotify.docker.client.messages.HostConfig;
import com.spotify.docker.client.messages.ProgressMessage;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.ngrinder.common.exception.NGrinderRuntimeException;
import org.ngrinder.common.util.PropertiesWrapper;
import org.ngrinder.infra.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.List;

import static org.ngrinder.common.constant.AgentAutoScaleConstants.*;
import static org.ngrinder.common.util.ExceptionUtils.processException;
import static org.ngrinder.common.util.Preconditions.checkTrue;
import static org.ngrinder.common.util.ThreadUtils.sleep;

/**
 * This class is used to control the docker daemon which locates in the created AWS VM. The agent is
 * is running in the docker container running in VM.
 *
 * @author shihuc
 * @version 3.3.2
 */
public class AgentAutoScaleDockerClient implements Closeable {
	private static final Logger LOG = LoggerFactory.getLogger(AgentAutoScaleDockerClient.class);

	private DockerClient dockerClient;

	private static final long CONNECT_TIMEOUT_MILLIS = 2 * 1000;
	private static final long READ_TIMEOUT_MILLIS = 5 * 1000;
	/*
	 * The docker image repository which identifies which image to run agent
	 */
	private final String image;
	private final String controllerUrl;
	private final String region;
	private String machineName;

	/**
	 * Constructor function, in this function to do docker client api initialization.
	 *
	 * @param config    used to specify which docker image will be used
	 * @param addresses the docker daemon addresses
	 */
	public AgentAutoScaleDockerClient(Config config, String machineName, List<String> addresses, int daemonPort) {
		this.machineName = machineName;
		this.region = config.getRegion();
		this.image = getImageName(config);
		controllerUrl = getConnectionUrl(config);
		checkTrue(!addresses.isEmpty(), "Address should contains more than 1 element");
		for (String each : addresses) {
			String daemonUri = "http://" + each + ":" + daemonPort;
			try {
				ProxyAwareDockerClient.Builder builder = ProxyAwareDockerClient.builder();
				if (StringUtils.isNotEmpty(config.getProxyHost()) && config.getProxyPort() != 0) {
					builder = builder
							.proxyHost(config.getProxyHost())
							.proxyPort(config.getProxyPort());
				}
				dockerClient = builder
						.uri(daemonUri)
						.connectTimeoutMillis(CONNECT_TIMEOUT_MILLIS)
						.readTimeoutMillis(READ_TIMEOUT_MILLIS)
						.build();
				LOG.info("Try to connect {} docker using {}", machineName, daemonUri);
				// If this fails, docker client will try with another address.
				dockerClient.ping();
				LOG.info("connected to {} docker using {}", machineName, daemonUri);
				return;
			} catch (Exception e) {
				// Fall through
				LOG.info("Access to {} using {} is failed", machineName, daemonUri);
			}
		}
		throw new NGrinderRuntimeException("No address for " + machineName + " can be connectible ");
	}

	private String getConnectionUrl(Config config) {
		final PropertiesWrapper agentAutoScaleProperties = config.getAgentAutoScaleProperties();
		return agentAutoScaleProperties.getProperty(PROP_AGENT_AUTO_SCALE_CONTROLLER_IP) + ":" + agentAutoScaleProperties.getProperty(PROP_AGENT_AUTO_SCALE_CONTROLLER_PORT);
	}

	private String getImageName(Config config) {
		final PropertiesWrapper agentAutoScaleProperties = config.getAgentAutoScaleProperties();
		return agentAutoScaleProperties.getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_REPO) + ":" + agentAutoScaleProperties.getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_TAG);
	}

	/**
	 * This function is used to close the docker client api HTTP connection. Required to call after usage.
	 */
	@Override
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
	public void stopContainer(String containerId) {
		try {
			LOG.info("Stop docker container: {} in {}", containerId, machineName);
			dockerClient.stopContainer(containerId, 1);
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
			LOG.info("Start docker container: {} in {}", containerId, machineName);
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
				try {
					dockerClient.inspectImage(image);
				} catch (DockerException e) {
					LOG.info("Image " + image + " does not exist. Try to download.");
					try {
						dockerClient.pull(image, new ProgressHandler() {
							@Override
							public void progress(ProgressMessage message) throws DockerException {
								LOG.info("Image " + image + " is downloading {}", message.progressDetail());
							}
						});
					} catch (DockerException ex) {
						throw processException("docker image can not be pullable", ex);
					}
				}

				/*
				 * Below function can wait some time until the http connection is ok before timeout
				 */
				final ContainerInfo containerInfo = dockerClient.inspectContainer(containerId);
				if (containerInfo.state().running()) {
					LOG.info("Container {} is already running, stop it", containerId);
					dockerClient.stopContainer(containerId, 0);
				}

				if (!containerInfo.config().env().contains("CONTROLLER_ADDR=" + controllerUrl)) {
					dockerClient.removeContainer(containerId);
					LOG.error("Wrong CONTROLLER_ADDR ADDR {}. Create New One with {}", machineName, controllerUrl);
					throw new ContainerNotFoundException("Wrong CONTROLLER_ADDR ADDR. Create New One");
				}

			} catch (ContainerNotFoundException e) {
				ContainerConfig containerConfig = ContainerConfig.builder()
						.image(image)
						.hostConfig(HostConfig.builder().networkMode("host").build())
						.env("CONTROLLER_ADDR=" + controllerUrl, "REGION=" + region)
						.build();
				dockerClient.createContainer(containerConfig, containerId);
				LOG.info("Container {} is creating", containerId);
			}

		} catch (Exception e) {
			throw processException(e);
		}
	}

	public ContainerInfo waitUtilContainerIsOn(String containerId) {
		try {
			for (int i = 0; i < 5; i++) {
				try {
					final ContainerInfo containerInfo = dockerClient.inspectContainer(containerId);
					if (containerInfo.state().running()) {
						return containerInfo;
					}
					sleep(1000);
					if (i++ >= 5) {
						throw processException("container " + containerId + " is failed to run");
					}
				} catch (ContainerNotFoundException e) {
					throw processException("Container " + containerId + " is not found.", e);
				}
			}
		} catch (Exception e) {
			throw processException(e);
		}
		return null;
	}

	/**
	 * Unit Test purpose
	 */
	protected void removeContainer(String containerName) {
		try {
			dockerClient.removeContainer(containerName, true);
		} catch (Exception e) {
			throw processException(e);
		}
	}

	/**
	 * Unit Test purpose
	 */
	protected void ping() {
		try {
			dockerClient.ping();
		} catch (Exception e) {
			throw processException(e);
		}
	}

	/**
	 * Unit Test purpose
	 */
	protected ContainerInfo inspectContainer(String containerName) {
		try {
			return dockerClient.inspectContainer(containerName);
		} catch (Exception e) {
			return null;
		}
	}
}
