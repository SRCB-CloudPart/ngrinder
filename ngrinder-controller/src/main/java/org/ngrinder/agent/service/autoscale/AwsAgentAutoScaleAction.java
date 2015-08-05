package org.ngrinder.agent.service.autoscale;


import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
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
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import static org.apache.commons.lang3.builder.ToStringBuilder.reflectionToString;
import static org.ngrinder.common.util.ExceptionUtils.processException;
import static org.ngrinder.common.util.Preconditions.checkNotEmpty;
import static org.ngrinder.common.util.Preconditions.checkNotNull;

/**
 * Created by junoyoon on 15. 7. 29.
 */
@Qualifier("aws")
public class AwsAgentAutoScaleAction extends AgentAutoScaleAction implements RemovalListener<String, Long> {

	private static final Logger LOG = LoggerFactory.getLogger(AwsAgentAutoScaleAction.class);

	private Config config;

	private AgentManagerService agentManagerService;

	private VirtualMachineSupport virtualMachineSupport;

	private MachineImageSupport machineImageSupport;

	private CloudProvider cloudProvider;

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
		initNodes(getTagString(config), config.getAgentAutoScaleMaxNodes());
	}

	private String getTagString(Config config) {
		return "agent" + config.getAgentAutoScaleControllerIP().replaceAll("\\.", "d");
	}

	private void initFilterMap(Config config) {
		filterMap.put("Name", getTagString(config));
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
					// This is for the controller is behind the proxy.
					if (f.name.equals("proxyHost") && StringUtils.isNotBlank(proxyHost)) {
						values.add(ProviderContext.Value.parseValue(f, proxyHost));
					} else if (f.name.equals("proxyPort") && proxyPort != 0) {
						values.add(ProviderContext.Value.parseValue(f, String.valueOf(proxyPort)));
					}
				}
			}

			ProviderContext ctx = cloud.createContext("", regionId, values.toArray(new ProviderContext.Value[values.size()]));
			cloudProvider = ctx.connect();
			virtualMachineSupport = checkNotNull(cloudProvider.getComputeServices()).getVirtualMachineSupport();
			machineImageSupport = checkNotNull(cloudProvider.getComputeServices()).getImageSupport();

		} catch (Exception e) {
			throw processException("Exception occured while setting up AWS agent auto scale", e);
		}
	}

	public void initNodes(String tag, int count) {
		try {
			// Get the nodes which has the controller ip label, and with state PENDING, RUNNING or STOPPED.
			Set<VmState> vmStates = Sets.newHashSet();
			vmStates.add(VmState.PENDING);
			vmStates.add(VmState.RUNNING);
			vmStates.add(VmState.STOPPED);
			vmStates.add(VmState.STOPPING);
			List<VirtualMachine> result = listVirtualMachineFilterByStatusAndTag(vmStates, tag);
			int size = result.size();
			int terminatedCnt = 0;
			int needActionCnt = Math.abs(size - count);
			boolean terminationDone = false;
			if (size > count) {
				// TODO: fill the node termination code.
				for (VirtualMachine vm : result) {
					if (!terminationDone) {
						//currently, the list operation has issue, result is not right, avoid to impact the existing VM,
						//this moment, do not exec terminate
						//terminateNode(vm);
						if (terminatedCnt >= needActionCnt) {
							terminationDone = true;
						}
						terminatedCnt++;
					} else {
						putNodeIntoVmCache(vm);
					}
				}
			} else if (size <= count) {
				// TODO: fill the node launch code.
				lanuchNodes(needActionCnt);
				for (VirtualMachine vm : result) {
					putNodeIntoVmCache(vm);
				}
			}
			suspendNodes();
		} catch (Exception e) {
			throw processException(e);
		}
	}

	//Test perpose
	public void listNodes(String tag) throws CloudException, InternalException {
		Set<VmState> vmStates = Sets.newHashSet();
		vmStates.add(VmState.PENDING);
		vmStates.add(VmState.RUNNING);
		vmStates.add(VmState.STOPPED);
		List<VirtualMachine> result = listVirtualMachineFilterByStatusAndTag(vmStates, tag);
	}

	private List<VirtualMachine> listVirtualMachineFilterByStatusAndTag(Set<VmState> vmStates, String tag) throws CloudException, InternalException {
		VMFilterOptions vmFilterOptions = VMFilterOptions.getInstance().withVmStates(vmStates);
		List<VirtualMachine> result = (List<VirtualMachine>) virtualMachineSupport.listVirtualMachines(vmFilterOptions);
		List<VirtualMachine> filterResult = Lists.newArrayList();
		for (VirtualMachine vm : result) {
			Map<String, String> vmTags = vm.getTags();
			if (vmTags.containsKey("Name") && vmTags.containsValue(tag)) {
				filterResult.add(vm);
			}
		}
		return filterResult;
	}


	@Override
	public void activateNodes(int count) {
		// TODO : fill the node activation code.
		// TODO : list the stopped nodes and restart them if the count of stopped node is greater than the given count
		//config.getAgentAutoScaleMaxNodes()
		ConcurrentMap<String, AutoScaleNode> vmNodes = vmCache.asMap();
		List<VirtualMachine> result = Lists.newArrayList();
		for (String name : vmNodes.keySet()) {
			try {
				activateNode(vmNodes.get(name).getMachineId(), result);
			} catch (CloudException e) {
				throw processException(e);
			} catch (InternalException e) {
				throw processException(e);
			}
		}
		try {
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
		ConcurrentMap<String, AutoScaleNode> vmNodes = vmCache.asMap();
		for (String name : vmNodes.keySet()) {
			try {
				suspendNode(vmNodes.get(name).getMachineId());
			} catch (CloudException e) {
				throw processException(e);
			} catch (InternalException e) {
				throw processException(e);
			}
		}
	}

	private void suspendNode(String vmId) throws CloudException, InternalException {
		VirtualMachine vm = checkNotNull(virtualMachineSupport.getVirtualMachine(vmId));
		VirtualMachineCapabilities capabilities = virtualMachineSupport.getCapabilities();
		if (capabilities.canStop(vm.getCurrentState())) {
			LOG.info("Suspending {} from state {} ...", vmId, vm.getCurrentState());
			virtualMachineSupport.stop(vmId);
		}
	}

	private void activateNode(String vmId, List<VirtualMachine> virtualMachines) throws CloudException, InternalException {
		VirtualMachine vm = checkNotNull(virtualMachineSupport.getVirtualMachine(vmId));
		VirtualMachineCapabilities capabilities = virtualMachineSupport.getCapabilities();
		if (capabilities.canStart(vm.getCurrentState())) {
			LOG.info("Activating {} from state {} ...", vm.getProviderVirtualMachineId(), vm.getCurrentState());
			virtualMachineSupport.start(vm.getProviderVirtualMachineId());
			touch(vm.getName());
			virtualMachines.add(vm);
		} else {
			LOG.info("You cannot activate a VM in the state {} ...", vm.getCurrentState());
		}
	}

	private void terminateNode(VirtualMachine vm) throws CloudException, InternalException {
		VirtualMachineCapabilities capabilities = virtualMachineSupport.getCapabilities();
		if (capabilities.canTerminate(vm.getCurrentState())) {
			LOG.info("Terminating {} from state {} ...", vm.getProviderVirtualMachineId(), vm.getCurrentState());
			virtualMachineSupport.terminate(vm.getProviderVirtualMachineId());
		}
	}

	private String searchRequiredImageId(String ownerId, Platform platform, Architecture arch) {
		//suggest to use Amazon distributes AMI, the owner ID is 137112412989.
		String imageId = null;
		try {
			for (MachineImage img : machineImageSupport.searchImages(ownerId, null, platform, arch, ImageClass.MACHINE)) {
				if (img.getCurrentState().equals(MachineImageState.ACTIVE)) {
					LOG.info("Image name {} is available for application.", img.getName());
					imageId = img.getProviderMachineImageId();
					break;
				}
			}
		} catch (Exception e) {
			throw processException(e);
		}
		return imageId;
	}

	public void lanuchNodes(int count) throws CloudException, InternalException {
		if (count <= 0) {
			return;
		}
		String ownerId = "137112412989";
		String description = "m1.medium";
		Architecture targetArchitecture = Architecture.I64;
		String hostName = getTagString(config);

		String imageId = searchRequiredImageId(ownerId, Platform.UNIX, targetArchitecture);
		VirtualMachineProduct product = getVirtualMachineProduct(description, targetArchitecture);
		VMLaunchOptions options = constructVmLaunchOptions(hostName, imageId, product);

		//VirtualMachine vm = virtualMachineSupport.launch(options);
		int createdCnt = 0;
		List<String> vmIds = Lists.newArrayList();
		while (createdCnt < count) {
			int toCreateCnt = count - createdCnt;
			List<String> ids = Lists.newArrayList(options.buildMany(cloudProvider, toCreateCnt));
			vmIds.addAll(ids);
			createdCnt += vmIds.size();
		}
		LOG.info("Launched {} virtual machines, waiting for they become running ...", createdCnt);

		List<VirtualMachine> virtualMachines = Lists.newArrayList();
		for (String vmId : vmIds) {
			VirtualMachine vm = virtualMachineSupport.getVirtualMachine(vmId);
			putNodeIntoVmCache(vm);
			virtualMachines.add(vm);
		}
		waitUntilVmToBeRunning(virtualMachines);
	}

	private void putNodeIntoVmCache(VirtualMachine vm) {
		AutoScaleNode node = new AutoScaleNode(vm.getProviderVirtualMachineId(), System.currentTimeMillis());
		vmCache.put(vm.getName(), node);
	}

	private void waitUntilVmToBeRunning(List<VirtualMachine> filteredVMs) throws InternalException, CloudException {
		for (VirtualMachine vm : filteredVMs) {
			while (vm != null && !vm.getCurrentState().equals(VmState.RUNNING)) {
				System.out.print(".");
				try {
					Thread.sleep(5000L);
				} catch (InterruptedException ignore) {
				}
				vm = virtualMachineSupport.getVirtualMachine(vm.getProviderVirtualMachineId());
			}
			if (vm == null) {
				LOG.info("VM self-terminated before entering a usable state");
			} else {
				LOG.info("Node {}  State change complete ({}), PubIP: {}, PriIP {} ",
						new Object[]{vm.getProviderVirtualMachineId(), vm.getCurrentState(), reflectionToString(vm.getPublicAddresses()), reflectionToString(vm.getPrivateAddresses())});
			}
		}
	}

	private VirtualMachineProduct getVirtualMachineProduct(String description, Architecture targetArchitecture) throws InternalException, CloudException {
		VirtualMachineProductFilterOptions vmProductFilterOpt = VirtualMachineProductFilterOptions.getInstance().withArchitecture(targetArchitecture);
		VirtualMachineProduct product = null;
		Iterator<VirtualMachineProduct> supported = virtualMachineSupport.listProducts(vmProductFilterOpt).iterator();
		while (supported.hasNext()) {
			product = supported.next();
			if (product.getDescription().contains(description)) {
				break;
			}
		}
		if (product == null) {
			LOG.info("Unable to identify a product to use");
			return null;
		}
		return product;
	}

	private VMLaunchOptions constructVmLaunchOptions(String hostName, String imageId, VirtualMachineProduct product) throws InternalException, CloudException {
		VMLaunchOptions options = VMLaunchOptions.getInstance(
				checkNotNull(product).getProviderProductId(),
				checkNotNull(imageId),
				checkNotNull(hostName), hostName, hostName);
		options.withLabels(config.getAgentAutoScaleControllerIP());

		IdentityServices identity = cloudProvider.getIdentityServices();
		if (identity == null) {
			LOG.info("No identity services exist, but shell keys are required.");
			return null;
		}
		ShellKeySupport keySupport = identity.getShellKeySupport();
		if (keySupport == null) {
			LOG.info("No shell key support exists, but shell keys are required.");
			return null;
		}
		Iterator<SSHKeypair> keys = keySupport.list().iterator();
		String keyId = null;
		boolean found = false;
		while (keys.hasNext()) {
			keyId = keys.next().getProviderKeypairId();
			if (keyId.equalsIgnoreCase("agent")) {
				found = true;
				break;
			}
		}

		String pubKey = "";
		try {
			pubKey = Files.toString(new File("/home/agent/.ssh/id_rsa.pub"), Charset.forName("ISO-8859-1")).trim();
		} catch (IOException e) {
			throw processException(e);
		}
		pubKey = new String(Base64.encodeBase64(pubKey.getBytes()));
		System.out.println(pubKey);

		if (!found) {
			keyId = keySupport.importKeypair("agent", pubKey).getProviderKeypairId();
		}

		return options.withBootstrapKey(keyId);
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
			return (List<VirtualMachine>) virtualMachineSupport.listVirtualMachines(filterOptions);
		} catch (Exception e) {
			throw processException(e);
		}
	}

	public void createNode(String wow) {

	}
}
