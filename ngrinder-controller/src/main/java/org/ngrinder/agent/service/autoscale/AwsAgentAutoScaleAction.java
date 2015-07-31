package org.ngrinder.agent.service.autoscale;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import org.apache.commons.lang3.StringUtils;
import org.dasein.cloud.Cloud;
import org.dasein.cloud.CloudProvider;
import org.dasein.cloud.ContextRequirements;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.aws.AWSCloud;
import org.dasein.cloud.compute.ComputeServices;
import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.ngrinder.infra.config.Config;
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.UnsupportedEncodingException;
import java.util.List;

import static org.ngrinder.common.util.ExceptionUtils.processException;
import static org.ngrinder.common.util.Preconditions.checkNotNull;

/**
 * Created by junoyoon on 15. 7. 29.
 */
@Qualifier("aws")
public class AwsAgentAutoScaleAction extends AgentAutoScaleAction {

    private Config config;

    private ComputeServices computeServices;

    @Override
    public void init(Config config) throws InstantiationException, IllegalAccessException, UnsupportedEncodingException {

        this.config = config;
        initComputeService(config);
        initDockerService(config);
    }

    private void initDockerService(Config config) {
    }

    private void initComputeService(Config config) {
        try {
            String regionId = checkNotNull(config.getAgentAutoScaleRegion(), "agent.auto_scale.region option should be provided to activate AWS agent auto scale.");
            String cloudName = "AWS";
            String providerName = "Amazon";
            String proxyHost = config.getProxyHost();
            String proxyPort = String.valueOf(config.getProxyPort());
            // Use that information to register the cloud
            @SuppressWarnings("unchecked") Cloud cloud = Cloud.register(providerName, cloudName, "", AWSCloud.class);

            // Find what additional fields are necessary to connect to the cloud
            ContextRequirements requirements = cloud.buildProvider().getContextRequirements();
            List<ContextRequirements.Field> fields = requirements.getConfigurableValues();

            // Load the values for the required fields from the system properties
            ProviderContext.Value[] values = new ProviderContext.Value[fields.size()];
            int i = 0;

            for (ContextRequirements.Field f : fields) {
                System.out.print("Loading '" + f.name + "' from ");
                if (f.type.equals(ContextRequirements.FieldType.KEYPAIR)) {
                    String shared = checkNotNull(config.getAgentAutoScaleIdentity(), "agent.auto_scale.identity option should be provided to activate the AWS agent auto scale.");
                    String secret = checkNotNull(config.getAgentAutoScaleCredential(), "agent.auto_scale.credential option should be provided to activate the AWS agent auto scale.");
                    values[i] = ProviderContext.Value.parseValue(f, shared, secret);
                } else {
                    if (f.name.equals("proxyHost") && StringUtils.isNotBlank(proxyHost)) {
                        values[i] = ProviderContext.Value.parseValue(f, proxyHost);
                    } else if (f.name.equals("proxyPort") && StringUtils.isNotBlank(proxyPort)) {
                        values[i] = ProviderContext.Value.parseValue(f, proxyPort);
                    }
                }
                i++;
            }

            ProviderContext ctx = cloud.createContext("", regionId, values);
            CloudProvider provier = ctx.connect();
            computeServices = provier.getComputeServices();
        } catch (Exception e) {
            processException("Exception occured while setting up AWS agent auto scale", e);
        }
    }

    @Override
    public void initNodes(int count) {
    }

    @Override
    public void activateNodes(int count) {
        //config.getAgentAutoScaleMaxNodes()
    }

    @Override
    public void suspendNodes(int count) {

    }

    @Override
    public boolean isInProgress() {
        return false;
    }


}
