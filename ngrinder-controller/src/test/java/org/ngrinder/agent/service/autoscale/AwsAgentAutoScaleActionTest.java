package org.ngrinder.agent.service.autoscale;

import org.apache.commons.lang3.StringUtils;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.compute.VirtualMachine;
import org.junit.Before;
import org.junit.Test;
import org.ngrinder.infra.config.Config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/**
 * Created by junoyoon on 15. 8. 4.
 */
public class AwsAgentAutoScaleActionTest {

    private AwsAgentAutoScaleAction awsAgentAutoScaleAction = new AwsAgentAutoScaleAction();

    // -Dagent.auto_scale.identity=AKIAIJLRS4YZLKWNWCYQ
    // -Dagent.auto_scale.credential=WGBNzMKsdUE5fVRm4gNotjWtz99v3Std76uPgUkf
    // -Dcontroller.proxy_host=pi-proxy.cloudpi.net
    // -Dcontroller.proxy_port=3128
    // 3128
    @Before
    public void init() {
        Config config = mock(Config.class);
        when(config.getAgentAutoScaleRegion()).thenReturn("ap-southeast-1");
        when(config.getAgentAutoScaleIdentity()).thenReturn(System.getProperty("agent.auto_scale.identity"));
        when(config.getAgentAutoScaleCredential()).thenReturn(System.getProperty("agent.auto_scale.credential"));
        when(config.getAgentAutoScaleControllerIP()).thenReturn("176.34.4.181");
        when(config.getAgentAutoScaleControllerPort()).thenReturn("8080");
        when(config.getAgentAutoScaleMaxNodes()).thenReturn(1);
        when(config.getAgentAutoScaleDockerRepo()).thenReturn("ngrinder/agent");
        when(config.getAgentAutoScaleDockerTag()).thenReturn("3.3");
        if (StringUtils.isNotBlank(System.getProperty("controller.proxy_host"))) {
            when(config.getProxyHost()).thenReturn(System.getProperty("controller.proxy_host"));
            when(config.getProxyPort()).thenReturn(Integer.parseInt(System.getProperty("controller.proxy_port")));
        }
        awsAgentAutoScaleAction.init(config, null);
    }

//    @Test
//    public void testAwsCreation() {
//        awsAgentAutoScaleAction.createNode("wow");
//        for (VirtualMachine each : awsAgentAutoScaleAction.listAgents()) {
//            System.out.println(each);
//        }
//    }

    @Test
    public void testActivateNodes() throws CloudException, InternalException {
        awsAgentAutoScaleAction.activateNodes(1);
    }

//    @Test
//    public void testSuspendNodes() throws CloudException, InternalException {
//        awsAgentAutoScaleAction.suspendNodes();
//    }

//    @Test
//    public void testLanuchNodes() throws CloudException, InternalException {
//        awsAgentAutoScaleAction.launchNodes(1);
//    }

}