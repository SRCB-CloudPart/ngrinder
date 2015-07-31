package org.ngrinder.agent.service;

/**
 * Created by junoyoon on 15. 7. 28.
 */
public class AgentAutoScaleScriptExecutor {

    private final String controllerIP;
    private final String dockerRepo;
    private final String dockerTag;

    AgentAutoScaleScriptExecutor(String controllerIP, String dockerRepo, String dockerTag) {
        this.controllerIP = controllerIP;
        this.dockerRepo = dockerRepo;
        this.dockerTag = dockerTag;
    }

    public String getControllerIP() {
        return controllerIP;
    }

    public String getDockerRepo() {
        return dockerRepo;
    }

    public String getDockerTag() {
        return dockerTag;
    }

    public void run() {
    }

}
