package org.ngrinder.agent.service;

import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.DockerClient.ListContainersParam;
import com.spotify.docker.client.DockerException;
import com.spotify.docker.client.messages.Container;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import com.spotify.docker.client.messages.Image;
import org.ngrinder.infra.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.ngrinder.common.util.ExceptionUtils.processException;

/**
 *  This class is used to control the docker daemon which locates in the created AWS VM. The agent is
 *  is running in the docker container running in VM.
 *
 * @author
 *  shihuc
 *
 * @version
 *  8/14/15 v1
 */
public class AgentAutoScaleDockerClient {
    private static final Logger LOG = LoggerFactory.getLogger(AgentAutoScaleDockerClient.class);

    private Config config;

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
    private String dockerRepo;

    /*
     * The docker tag which identifies which image to run agent
     */
    private String dockerTag;

    /**
     * Constructor function, in this function to do docker client api initialization.
     *
     * @param config used to specify which docker image will be used
     * @param serverIP the docker daemon address
     */
    public AgentAutoScaleDockerClient(Config config, String serverIP){
        this.config = config;

        this.dockerRepo = this.config.getAgentAutoScaleDockerRepo();
        this.dockerTag = this.config.getAgentAutoScaleDockerTag();

        String daemonUri = "http://" + serverIP + ":" + DAEMON_TCP_PORT;
        dockerClient = DefaultDockerClient.builder()
                .uri(daemonUri)
                .connectTimeoutMillis(CONNECT_TIMEOUT_MILLIS)
                .readTimeoutMillis(READ_TIMEOUT_MILLIS)
                .build();
    }

    /**
     * This function is used to close the docker client api HTTP connection. Required to call after usage.
     */
    public void close(){
        if (dockerClient != null) {
            this.dockerClient.close();
        }
    }

    /**
     * This function is used to start one docker container. It will do following behavior:
     * if input id is null:
     * 1> find the image id from the existing images, if not found, pull
     * image from the public repository
     * 2> create one container with the specified image ID
     * 3> start one docker container with the generated container ID
     * else:
     * start container with the specified docker container id.
     *
     * @param id the container id which to start
     * @return the docker container id
     * @throws DockerException
     * @throws InterruptedException
     */
    public String startDockerContainer(String id) throws DockerException, InterruptedException {
        String containerId;

        if(id == null || id.isEmpty()) {
            String imageId = findImageId(dockerRepo, dockerTag);
            checkNotNull(imageId, "There is no required image to start one container");

            String imageName = dockerRepo + ":" + dockerTag;
            containerId = findContainerId(imageId, imageName);
            if(containerId == null){
                containerId = createDockerContainer(config, imageId);
            }
        }else{
            containerId = id;
        }

        Container container = getContainer(containerId);
        checkNotNull(container, "Can not find the container with the given Id...");
        /*
         * If the container is UP, then do not start it.
         */
        if(!container.status().startsWith("Up")) {
            dockerClient.startContainer(containerId);
        }

        if(id == null || id.isEmpty()){
            LOG.info("Start one known docker container: {}", containerId);
        }else{
            LOG.info("Initialize one docker container: {}", containerId);
        }
        return containerId;
    }

    /**
     * Stop the specified docker container.
     *
     * @param id the container id of which to be stopped
     */
    public void stopDockerContainer(String id) throws DockerException, InterruptedException {

        Container container = getContainer(id);
        if(container == null){
            LOG.info("No docker container is not running with Id: {}", id);
            return;
        }

        if(container.status().startsWith("Up")) {
            dockerClient.stopContainer(id, 10);
            LOG.info("Stop docker container: {}", id);
            return;
        }
        LOG.info("Docker container {} is not running...", id);
    }

    private String createDockerContainer(Config config, String imageId) throws DockerException, InterruptedException {
        String address = config.getAgentAutoScaleControllerIP() + ":" + config.getAgentAutoScaleControllerPort();
        ContainerConfig containerConfig = ContainerConfig.builder()
                .image(imageId)
                .env("CONTROLLER_ADDR=" + address)
                .attachStdout(true)
                .build();

        ContainerCreation cc = dockerClient.createContainer(containerConfig);
        String containerId = cc.id();

        LOG.info("Successfully created container ID: {}", containerId);
        return containerId;
    }

    private Container getContainer(String containerId) throws DockerException, InterruptedException {
        ListContainersParam listContainersParam = ListContainersParam.allContainers(true);
        List<Container> containers = dockerClient.listContainers(listContainersParam);

        for(Container con: containers){
            if(con.id().equalsIgnoreCase(containerId)){
                return con;
            }
        }
        return null;
    }

    private String findContainerId(String imageId, String imageName) throws DockerException, InterruptedException {
        checkNotNull(dockerClient, "Docker client should be initialized before use");

        ListContainersParam listContainersParam = ListContainersParam.allContainers(true);
        List<Container> containers = dockerClient.listContainers(listContainersParam);

        String containerId = null;
        for(Container co: containers){
            if(co.image().equalsIgnoreCase(imageId) || co.image().equalsIgnoreCase(imageName)){
                containerId = co.id();
                break;
            }
        }
        LOG.info("Found docker container ID: {} with image ID: {}", containerId, imageId);
        return containerId;
    }

    private String findImageId(String repo, String tag) throws DockerException, InterruptedException {
        checkNotNull(dockerClient, "Docker client should be initialized before use");

        String repoTag = repo + ":" + tag;

        String imageId = filterImageId(repoTag);

        if(imageId == null){
            LOG.info("Can not find the require image from the local images");

            dockerClient.pull(repoTag);
            /*
             * when not find the required image ID form existing images, then to pull image from
             * public repository.
             */
            imageId = filterImageId(repoTag);
        }

        LOG.info("Found docker image ID: {}" , imageId);
        return imageId;
    }

    private String filterImageId(String repoTag) throws DockerException, InterruptedException {
        List<Image> images = dockerClient.listImages();
        String imageId = null;
        for(Image im: images){
            List<String> repoTags = im.repoTags().asList();
            if(repoTags.contains(repoTag)){
                imageId = im.id();
                break;
            }
        }
        return imageId;
    }

}
