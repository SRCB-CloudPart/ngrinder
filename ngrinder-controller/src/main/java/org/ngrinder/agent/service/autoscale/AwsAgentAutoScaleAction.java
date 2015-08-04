package org.ngrinder.agent.service.autoscale;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.dasein.cloud.*;
import org.dasein.cloud.aws.AWSCloud;
import org.dasein.cloud.compute.*;
import org.dasein.cloud.identity.IdentityServices;
import org.dasein.cloud.identity.SSHKeypair;
import org.dasein.cloud.identity.ShellKeySupport;
import org.dasein.cloud.network.RawAddress;
import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.ngrinder.agent.service.AgentManagerService;
import org.ngrinder.infra.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.ngrinder.common.util.ExceptionUtils.processException;
import static org.ngrinder.common.util.Preconditions.checkNotEmpty;
import static org.ngrinder.common.util.Preconditions.checkNotNull;

/**
 * Created by junoyoon on 15. 7. 29.
 */
@Qualifier("aws")
public class AwsAgentAutoScaleAction extends AgentAutoScaleAction {

    private static final Logger LOG = LoggerFactory.getLogger(AwsAgentAutoScaleAction.class);

    private Config config;

    private AgentManagerService agentManagerService;

    private VirtualMachineSupport virtualMachineSupport;

    private MachineImageSupport machineImageSupport;

    private CloudProvider cloudProvider;

    @Override
    public void init(Config config, AgentManagerService agentManagerService) {
        this.config = config;
        this.agentManagerService = agentManagerService;
        initComputeService(config);
        initDockerService(config);
        initNodes(config.getAgentAutoScaleControllerIP(), config.getAgentAutoScaleMaxNodes());
    }

    private void initDockerService(Config config) {
    }

    private void initComputeService(Config config) {
        try {
            String regionId = checkNotNull(config.getAgentAutoScaleRegion(), "agent.auto_scale.region option should be provided to activate AWS agent auto scale.");
            String cloudName = "AWS";
            String providerName = "Amazon";
            String proxyHost = config.getProxyHost();
            String proxyPort = config.getProxyPort();
            // Use that information to register the cloud
            @SuppressWarnings("unchecked") Cloud cloud = Cloud.register(providerName, cloudName, "", AWSCloud.class);

            // Find what additional fields are necessary to connect to the cloud
            ContextRequirements requirements = cloud.buildProvider().getContextRequirements();
            List<ContextRequirements.Field> fields = requirements.getConfigurableValues();

            // Load the values for the required fields from the system properties
            ProviderContext.Value[] values = new ProviderContext.Value[fields.size()];
            int i = 0;

            for (ContextRequirements.Field f : fields) {
                if (f.type.equals(ContextRequirements.FieldType.KEYPAIR)) {
                    String shared = checkNotEmpty(config.getAgentAutoScaleIdentity(), "agent.auto_scale.identity option should be provided to activate the AWS agent auto scale.");
                    String secret = checkNotEmpty(config.getAgentAutoScaleCredential(), "agent.auto_scale.credential option should be provided to activate the AWS agent auto scale.");
                    values[i] = ProviderContext.Value.parseValue(f, shared, secret);
                } else {
                    if (f.name.equals("proxyHost")) {
                        values[i] = ProviderContext.Value.parseValue(f, proxyHost);
                    } else if (f.name.equals("proxyPort")) {
                        values[i] = ProviderContext.Value.parseValue(f, proxyPort);
                    }
                }
                i++;
            }

            ProviderContext ctx = cloud.createContext("", regionId, values);
            cloudProvider = ctx.connect();
            virtualMachineSupport = checkNotNull(cloudProvider.getComputeServices()).getVirtualMachineSupport();
            machineImageSupport = checkNotNull(cloudProvider.getComputeServices()).getImageSupport();
        } catch (Exception e) {
            throw processException("Exception occured while setting up AWS agent auto scale", e);
        }
    }

    public void initNodes(String label, int count) {
        try {
            // Get the nodes which has the controller ip label, and with state PENDING, RUNNING or STOPPED.
            Set<VmState> vmStates = Sets.newHashSet();
            //vmStates.add(VmState.PENDING);
            vmStates.add(VmState.RUNNING);
            vmStates.add(VmState.STOPPED);
            //VMFilterOptions vmFilterOptions = VMFilterOptions.getInstance().withLabels(label).withVmStates(vmStates);
            VMFilterOptions vmFilterOptions = VMFilterOptions.getInstance().withLabels(label);
            List<VirtualMachine> result = (List<VirtualMachine>) virtualMachineSupport.listVirtualMachines(vmFilterOptions);
            int size = result.size();
            int terminatedCnt = 0;
            int needActionCnt = size - count;
            if (size > count) {
                // TODO: fill the node termination code.
                for(VirtualMachine vm: result){
                    terminateNode(vm);
                    terminatedCnt++;
                    if(terminatedCnt >= needActionCnt){
                        break;
                    }
                }
            } else if (size < count) {
                // TODO: fill the node launch code.
                lanuchNodes(needActionCnt);
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
        Set<VmState> vmStates = Sets.newHashSet();
        vmStates.add(VmState.STOPPED);
        VMFilterOptions vmFilterOptions = VMFilterOptions.getInstance().withLabels(config.getAgentAutoScaleControllerIP()).withVmStates(vmStates);
        try {
            List<VirtualMachine> result = (List<VirtualMachine>) virtualMachineSupport.listVirtualMachines(vmFilterOptions);
            for (VirtualMachine vm: result){
                activateNode(vm);
            }
            waitUntilVmToBeRunning(result);

        } catch (InternalException e) {
            throw processException(e);
        } catch (CloudException e) {
            throw processException(e);
        }
    }

    @Override
    public void suspendNodes() {
        // TODO : fill the node stopping code
        // TODO :
    }

    private void activateNode(VirtualMachine vm) throws CloudException, InternalException {
        VirtualMachineCapabilities capabilities = virtualMachineSupport.getCapabilities();
        VmState currentState = vm.getCurrentState();
        VmState targetState = null;
        if( capabilities.canTerminate(vm.getCurrentState()) ) {
            if( currentState.equals(targetState) ) {
                LOG.info("VM is already {}", targetState);
                return;
            }
            LOG.info("Activating {} from state {} ...", vm.getProviderVirtualMachineId(), vm.getCurrentState());
            virtualMachineSupport.start(vm.getProviderVirtualMachineId());
        }
        else {
            LOG.info("You cannot activate a VM in the state {} ...",  vm.getCurrentState());
        }
    }

    private void terminateNode(VirtualMachine vm) throws CloudException, InternalException {
        VirtualMachineCapabilities capabilities = virtualMachineSupport.getCapabilities();
        VmState currentState = vm.getCurrentState();
        VmState targetState = null;
        if( capabilities.canTerminate(vm.getCurrentState()) ) {
            targetState = VmState.TERMINATED;
            if( currentState.equals(targetState) ) {
                LOG.info("VM is already {}", targetState);
                return;
            }
            LOG.info("Terminating {} from state {} ...", vm.getProviderVirtualMachineId(), vm.getCurrentState());
            virtualMachineSupport.terminate(vm.getProviderVirtualMachineId());
        }
        else {
            LOG.info("You cannot terminate a VM in the state {} ...",  vm.getCurrentState());
        }
    }

    private String searchRequiredImageId(String ownerId, Platform platform, Architecture arch){
        //suggest to use Amazon distributes AMI, the owner ID is 137112412989.
        String imageId = null;
        try {
            for( MachineImage img : machineImageSupport.searchImages(ownerId, null, platform, arch, ImageClass.MACHINE) ) {
                if(img.getCurrentState().equals(MachineImageState.ACTIVE)){
                    LOG.info("Image name {} is available for application.", img.getName());
                    imageId = img.getProviderMachineImageId();
                    break;
                }
            }
        } catch (CloudException e) {
            e.printStackTrace();
        } catch (InternalException e) {
            e.printStackTrace();
        }
        return imageId;
    }

    private void lanuchNodes(int count) throws CloudException, InternalException {
        String ownerId = "137112412989";
        String description = "m1.medium";
        Architecture targetArchitecture = Architecture.I64;
        String hostName = "agent" + config.getAgentAutoScaleControllerIP().replaceAll(".", "d");

        String imageId = searchRequiredImageId(ownerId,  Platform.UNIX,  targetArchitecture);
        VirtualMachineProduct product = getVirtualMachineProduct(description, targetArchitecture);
        VMLaunchOptions options = constructVmLaunchOptions(hostName, imageId, product);

        //VirtualMachine vm = virtualMachineSupport.launch(options);
        List<String> vmIds = Lists.newArrayList(options.buildMany(cloudProvider, count));

        LOG.info("Launched {} virtual machines, waiting for they become running ...", count);

        VMFilterOptions vmFilterOptions = VMFilterOptions.getInstance().withLabels(config.getAgentAutoScaleControllerIP());
        List<VirtualMachine> result = (List<VirtualMachine>) virtualMachineSupport.listVirtualMachines(vmFilterOptions);

        List<VirtualMachine> filteredVMs = Lists.newArrayList();
        for(VirtualMachine vm: result){
            if(vm != null && vmIds.contains(vm.getProviderMachineImageId())){
                filteredVMs.add(vm);
            }
        }

        waitUntilVmToBeRunning(filteredVMs);
    }

    private void waitUntilVmToBeRunning(List<VirtualMachine> filteredVMs) throws InternalException, CloudException {
        for(VirtualMachine vm: filteredVMs) {
            while (vm != null && vm.getCurrentState().equals(VmState.PENDING)) {
                System.out.print(".");
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException ignore) {
                }
                vm = virtualMachineSupport.getVirtualMachine(vm.getProviderVirtualMachineId());
            }
            if( vm == null ) {
                LOG.info("VM self-terminated before entering a usable state");
            }
            else {
                RawAddress[] puip = vm.getPublicAddresses();
                RawAddress [] prip = vm.getPrivateAddresses();
                String spuip = "";
                for(RawAddress ura: puip){
                    spuip += ura.getIpAddress().toLowerCase() + " ";
                }
                String sprip = "";
                for(RawAddress rra: prip){
                    sprip += rra.getIpAddress().toLowerCase() + " ";
                }
                LOG.info("Node " + vm.getProviderVirtualMachineId() + " State change complete (" + vm.getCurrentState() + ")" +
                        ", PubIP: " + spuip + ", PriIP: " + sprip);
            }
        }
    }

    private VirtualMachineProduct getVirtualMachineProduct(String description, Architecture targetArchitecture) throws InternalException, CloudException {

        VirtualMachineProductFilterOptions vmProductFilterOpt = VirtualMachineProductFilterOptions.getInstance().withArchitecture(targetArchitecture);
        VirtualMachineProduct product = null;
        Iterator<VirtualMachineProduct> supported = virtualMachineSupport.listProducts(vmProductFilterOpt).iterator();
        while( supported.hasNext() ) {
            product = supported.next();
            if(product.getDescription().contains(description)) {
                break;
            }
        }
        if( product == null ) {
            LOG.info("Unable to identify a product to use");
            return null;
        }
        return product;
    }

    private VMLaunchOptions constructVmLaunchOptions(String hostName, String imageId, VirtualMachineProduct product) throws InternalException, CloudException {
        checkNotNull(hostName, "Host name should be provided");
        checkNotNull(imageId, "Virtual machine image ID should be provided");
        checkNotNull(product, "Virtual machine product should be provided");

        VMLaunchOptions options = VMLaunchOptions.getInstance(product.getProviderProductId(), imageId, hostName, hostName, hostName);
        options.withLabels(config.getAgentAutoScaleControllerIP());

        IdentityServices identity = cloudProvider.getIdentityServices();
        if( identity == null ) {
            LOG.info("No identity services exist, but shell keys are required.");
            return null;
        }
        ShellKeySupport keySupport = identity.getShellKeySupport();
        if( keySupport == null ) {
            LOG.info("No shell key support exists, but shell keys are required.");
            return null;
        }
        Iterator<SSHKeypair> keys = keySupport.list().iterator();
        String keyId = null;
        boolean found = false;
        while( keys.hasNext() ) {
            keyId = keys.next().getProviderKeypairId();
            if(keyId.equalsIgnoreCase("agent")){
                found = true;
                break;
            }
        }

        String pubKey = "";
        try {
            pubKey = Files.toString(new File("/home/agent/.ssh/id_rsa.pub"), Charset.forName("ISO-8859-1")).trim();
        } catch (IOException e) {
            e.printStackTrace();
        }
        pubKey = new String(Base64.encodeBase64(pubKey.getBytes()));
        System.out.println(pubKey);

        if(!found){
            keyId = keySupport.importKeypair("agent", pubKey).getProviderKeypairId();
        }

        return options.withBootstrapKey(keyId);
    }
}
