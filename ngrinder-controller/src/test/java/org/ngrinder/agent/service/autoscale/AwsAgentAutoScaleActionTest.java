package org.ngrinder.agent.service.autoscale;

import org.apache.commons.lang3.StringUtils;
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
        if (StringUtils.isNotBlank(System.getProperty("controller.proxy_host"))) {
            when(config.getProxyHost()).thenReturn(System.getProperty("controller.proxy_host"));
            when(config.getProxyPort()).thenReturn(Integer.parseInt(System.getProperty("controller.proxy_port")));
        }
        awsAgentAutoScaleAction.init(config, null);
    }

    @Test
    public void testAwsCreation() {

    }
}