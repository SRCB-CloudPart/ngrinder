package org.ngrinder.agent.service.autoscale;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import org.apache.commons.lang3.StringUtils;
import org.dasein.cloud.*;
import org.dasein.cloud.aws.AWSCloud;
import org.dasein.cloud.compute.VMFilterOptions;
import org.dasein.cloud.compute.VMLaunchOptions;
import org.dasein.cloud.compute.VirtualMachine;
import org.dasein.cloud.compute.VirtualMachineSupport;
import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.ngrinder.agent.service.AgentManagerService;
import org.ngrinder.infra.config.Config;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.ngrinder.common.util.ExceptionUtils.processException;
import static org.ngrinder.common.util.Preconditions.checkNotEmpty;
import static org.ngrinder.common.util.Preconditions.checkNotNull;

/**
 * Created by junoyoon on 15. 7. 29.
 */
@Qualifier("aws")
public class AwsAgentAutoScaleAction extends AgentAutoScaleAction implements RemovalListener<String, Long> {

    private Config config;

    private AgentManagerService agentManagerService;

    private VirtualMachineSupport virtualMachineSupport;

    /**
     * Cache b/w host name and last touched date
     */
    private Cache<String, Long> touchCache = CacheBuilder.newBuilder().expireAfterWrite(60, TimeUnit.MINUTES).removalListener(this).build();

    /**
     * Cache b/w host name and vmId
     */
    private final Cache<String, AutoScaleNode> vmCache = CacheBuilder.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).build();

    private final Map<String, String> filterMap = new HashMap<String, String>();

    private final VMFilterOptions filterOptions = VMFilterOptions.getInstance().withTags(filterMap);

    @Override
    public void init(Config config, AgentManagerService agentManagerService) {
        this.config = config;
        this.agentManagerService = agentManagerService;
        initFilterMap(config);
        initComputeService(config);
        initDockerService(config);
        initNodes(config.getAgentAutoScaleControllerIP(), config.getAgentAutoScaleMaxNodes());
    }

    private void initFilterMap(Config config) {
        filterMap.put("ngrinder_agent_for", config.getAgentAutoScaleControllerIP());
    }

    private void initDockerService(Config config) {
    }

    private void initComputeService(Config config) {
        try {
            String regionId = checkNotNull(config.getAgentAutoScaleRegion(), "agent.auto_scale.region option should be provided to activate AWS agent auto scale.");
            String cloudName = "AWS";
            String providerName = "Amazon";
            String proxyHost = config.getProxyHost();
            int proxyPort = config.getProxyPort();
            // Use that information to register the cloud
            @SuppressWarnings("unchecked") Cloud cloud = Cloud.register(providerName, cloudName, "", AWSCloud.class);

            // Find what additional fields are necessary to connect to the cloud
            ContextRequirements requirements = cloud.buildProvider().getContextRequirements();
            List<ContextRequirements.Field> fields = requirements.getConfigurableValues();

            // Load the values for the required fields from the system properties
            List<ProviderContext.Value> values = new ArrayList<ProviderContext.Value>();
            for (ContextRequirements.Field f : fields) {
                if (f.type.equals(ContextRequirements.FieldType.KEYPAIR)) {
                    String shared = checkNotEmpty(config.getAgentAutoScaleIdentity(), "agent.auto_scale.identity option should be provided to activate the AWS agent auto scale.");
                    String secret = checkNotEmpty(config.getAgentAutoScaleCredential(), "agent.auto_scale.credential option should be provided to activate the AWS agent auto scale.");
                    values.add(ProviderContext.Value.parseValue(f, shared, secret));
                } else {
                    if (f.name.equals("proxyHost") && StringUtils.isNotBlank(proxyHost)) {
                        values.add(ProviderContext.Value.parseValue(f, proxyHost));
                        ;
                    } else if (f.name.equals("proxyPort") && proxyPort != 0) {
                        values.add(ProviderContext.Value.parseValue(f, String.valueOf(proxyPort)));
                    }
                }
            }
            ProviderContext ctx = cloud.createContext("", regionId, values.toArray(new ProviderContext.Value[values.size()]));
            CloudProvider provier = ctx.connect();
            virtualMachineSupport = checkNotNull(provier.getComputeServices()).getVirtualMachineSupport();
        } catch (Exception e) {
            throw processException("Exception occured while setting up AWS agent auto scale", e);
        }
    }

    public void initNodes(String label, int count) {
        try {
            // Get the nodes which has the controller ip label.
            List<VirtualMachine> result = (List<VirtualMachine>) virtualMachineSupport.listVirtualMachines(filterOptions);
            System.out.println(result);
            int size = result.size();
            if (size > count) {
                // TODO: fill the node termination code.
            } else if (size < count) {
                // TODO: fill the node launch code.
            }
        } catch (Exception e) {
            throw processException(e);
        }
    }


    @Override
    public void activateNodes(int count) {
        // TODO : fill the node activation code.
        // TODO : list the stopped nodes and restart them if the count of stopped node is greater than the given count
        //config.getAgentAutoScaleMaxNodes()
    }

    @Override
    public void suspendNodes() {
        // TODO : fill the node stopping code
        // TODO :
    }

    @Override
    public void touch(String name) {
        touchCache.put(name, System.currentTimeMillis());
    }

    @Override
    public void onRemoval(RemovalNotification<String, Long> removal) {
        String key = removal.getKey();
    }

    public List<VirtualMachine> listAgents() {
        try {
            return (List<VirtualMachine>)virtualMachineSupport.listVirtualMachines(filterOptions);
        } catch (Exception e) {
            throw processException(e);
        }
    }

    public void createNode(String wow) {

    }
}
