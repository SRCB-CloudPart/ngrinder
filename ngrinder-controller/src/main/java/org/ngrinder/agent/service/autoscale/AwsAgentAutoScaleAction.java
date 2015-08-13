package org.ngrinder.agent.service.autoscale;


import com.beust.jcommander.internal.Maps;
import com.google.common.cache.*;
import com.google.common.io.Files;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.dasein.cloud.*;
import org.dasein.cloud.aws.AWSCloud;
import org.dasein.cloud.compute.*;
import org.dasein.cloud.identity.IdentityServices;
import org.dasein.cloud.identity.SSHKeypair;
import org.dasein.cloud.identity.ShellKeySupport;
import org.dasein.cloud.network.RawAddress;
import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.ngrinder.agent.service.AgentAutoScaleScriptExecutor;
import org.ngrinder.agent.service.AgentManagerService;
import org.ngrinder.common.util.ThreadUtils;
import org.ngrinder.infra.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Sets.newHashSet;
import static java.util.Collections.sort;
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
	private static final String NGRINDER_AGENT_TAG_KEY = "ngrinder_agent";
	/**
	 * Attention, this feature search the default AMI (UNIX, Machine, X86_64) distributed by Amazon, the
	 * default user is ec2-user.
	 */
	private static final String DEFAULT_AMZ_AMI_EC2_USER = "ec2-user";
	/**
	 * The list representing the ordered terminatable vm state.
	 */
	private static final List<VmState> TERMINATABLE_STATE_ORDER = newArrayList(VmState.ERROR,
			VmState.TERMINATED, VmState.STOPPED, VmState.STOPPING, VmState.SUSPENDED, VmState.PAUSED,
			VmState.PAUSING, VmState.PENDING, VmState.RUNNING, VmState.REBOOTING);
	/**
	 * Cache b/w virtual machine ID and node information full mapping, the full information contains the private IP,
	 * the private IP will be the identifier of agent, it is the bridge between VM node and agent. we can use the private
	 * IP to locate the agent is running in which virtual machine.
	 * <p/>
	 * Important: the agent is running in docker container which is located in the virtual machine. And, the docker
	 * agent image should be launched with "--net=host" option.
	 */
	private final Cache<String, AutoScaleNode> vmCache = CacheBuilder.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).build();
	private final Map<String, String> filterMap = new HashMap<String, String>();

	private Config config;
	private AgentManagerService agentManagerService;
	private VirtualMachineSupport virtualMachineSupport;
	private MachineImageSupport machineImageSupport;
	private CloudProvider cloudProvider;
	/**
	 * Cache b/w virtual machine ID and last touched date
	 */
	private Cache<String, Long> touchCache = CacheBuilder.newBuilder().expireAfterWrite(60, TimeUnit.MINUTES).removalListener(this).build();

	/**
	 * Docker initialization script which will be executed when the node is created.
	 */
	private String dockerInitScript;

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
		return config.getAgentAutoScaleControllerIP().replaceAll("\\.", "d");
	}

	private void initFilterMap(Config config) {
		filterMap.put("ngrinder_agent", getTagString(config));
	}

	void initDockerService(Config config) {
		initDockerInitScript();
	}

	void initDockerInitScript() {
		ClassPathResource resource = new ClassPathResource("agent_autoscale_script/docker-init.sh");
		InputStream inputStream = null;
		try {
			inputStream = resource.getInputStream();
			dockerInitScript = IOUtils.toString(inputStream);
		} catch (Exception e) {
			throw processException(e);
		} finally {
			IOUtils.closeQuietly(inputStream);
		}
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
					String shared = checkNotEmpty(config.getAgentAutoScaleIdentity(),
							"agent.auto_scale.identity option should be provided to activate the AWS agent auto scale.");
					String secret = checkNotEmpty(config.getAgentAutoScaleCredential(),
							"agent.auto_scale.credential option should be provided to activate the AWS agent auto scale.");
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
			List<VirtualMachine> existingVMs = listVMByState(newHashSet(VmState.PENDING, VmState.RUNNING, VmState.STOPPED, VmState.STOPPING));
			int size = existingVMs.size();
			if (size > count) {
				terminateNodes(selectTerminatableNodes(existingVMs, size - count));
			} else if (size <= count) {
				launchNodes(tag, count - size);
			}
			suspendNodes();
		} catch (Exception e) {
			throw processException(e);
		}
	}

	List<VirtualMachine> listVMByState(Set<VmState> vmStates) {
		List<VirtualMachine> filterResult = newArrayList();
		try {
			VMFilterOptions vmFilterOptions = VMFilterOptions.getInstance().withTags(filterMap);
			List<VirtualMachine> vms = (List<VirtualMachine>) virtualMachineSupport.listVirtualMachines(vmFilterOptions);
			for (VirtualMachine vm : vms) {
				if (vmStates.contains(vm.getCurrentState())) {
					filterResult.add(vm);
				}
			}
		} catch (Exception e) {
			throw processException(e);
		}
		return filterResult;
	}

	@Override
	public void activateNodes(int count) {
		List<VirtualMachine> vms = listVMByState(newHashSet(VmState.STOPPED));
		List<VirtualMachine> candidates = vms.subList(0, Math.min(count - 1, vms.size() - 1));
		try {
			for (VirtualMachine each : candidates) {
				activateNode(each.getProviderVirtualMachineId());
			}
			waitUntilVmToBeRunning(candidates);
		} catch (Exception e) {
			throw processException(e);
		}

		/*
		 * Update the node information into touchCache.
		 */
		for (VirtualMachine vm : candidates) {
			String ips = getPrivateIPs(vm);
			touch(ips);
		}

		remoteSshExecCommand(candidates, Action.ON.name());
	}

	@Override
	public void suspendNodes() {
		ConcurrentMap<String, AutoScaleNode> vmNodes = vmCache.asMap();
		List<VirtualMachine> result = newArrayList();
		for (String privateIPs : vmNodes.keySet()) {
			try {
				findCanBeSuspendedNodes(vmNodes.get(privateIPs).getMachineId(), result);
			} catch (Exception e) {
				throw processException(e);
			}
		}
		remoteSshExecCommand(result, Action.OFF.name());
		try {
			suspendNode(result);
		} catch (CloudException e) {
			throw processException(e);
		} catch (InternalException e) {
			throw processException(e);
		}
	}

	private void suspendNode(List<VirtualMachine> virtualMachines) throws CloudException, InternalException {
		for (VirtualMachine vm : virtualMachines) {
			String vmId = vm.getProviderVirtualMachineId();
			virtualMachineSupport.stop(vmId);
		}
	}

	private void findCanBeSuspendedNodes(String vmId, List<VirtualMachine> virtualMachines) throws CloudException, InternalException {
		VirtualMachine vm = checkNotNull(virtualMachineSupport.getVirtualMachine(vmId));
		VirtualMachineCapabilities capabilities = virtualMachineSupport.getCapabilities();
		if (capabilities.canStop(vm.getCurrentState())) {
			LOG.info("Suspending {} from state {} ...", vmId, vm.getCurrentState());
			virtualMachines.add(vm);
			//virtualMachineSupport.stop(vmId);
		}
	}

	VirtualMachine activateNode(String vmId) throws CloudException, InternalException {
		VirtualMachine vm = checkNotNull(virtualMachineSupport.getVirtualMachine(vmId));
		VirtualMachineCapabilities capabilities = virtualMachineSupport.getCapabilities();
		if (capabilities.canStart(vm.getCurrentState())) {
			LOG.info("Activating {} from state {} ...", vm.getProviderVirtualMachineId(), vm.getCurrentState());
			virtualMachineSupport.start(vm.getProviderVirtualMachineId());
		} else {
			LOG.info("You cannot activate a VM in the state {} ...", vm.getCurrentState());
		}
		return vm;
	}

	void terminateNode(VirtualMachine vm) throws CloudException, InternalException {
		VirtualMachineCapabilities capabilities = virtualMachineSupport.getCapabilities();
		if (capabilities.canTerminate(vm.getCurrentState())) {
			LOG.info("Terminating {} from state {} ...", vm.getProviderVirtualMachineId(), vm.getCurrentState());
			virtualMachineSupport.terminate(vm.getProviderVirtualMachineId());
		}
	}

	void terminateNodes(List<VirtualMachine> virtualMachines) {

	}

	/**
	 * Select the most appropriate nodes which can be terminated among the given vms
	 * <p/>
	 * This method sort the virtual machine with given #TERMINATABLE_STATE_ORDER state order.
	 * TODO: Not tested yet.
	 *
	 * @param vms   virtual machines
	 * @param count the number of machines which will be terminated.
	 * @return selected virtual machines.
	 */
	List<VirtualMachine> selectTerminatableNodes(List<VirtualMachine> vms, int count) {
		List<VirtualMachine> sorted = new ArrayList<VirtualMachine>(vms);
		sort(sorted, new Comparator<VirtualMachine>() {
			@Override
			public int compare(VirtualMachine o1, VirtualMachine o2) {
				return TERMINATABLE_STATE_ORDER.indexOf(o1.getCurrentState()) - TERMINATABLE_STATE_ORDER.indexOf(o2.getCurrentState());
			}
		});
		return sorted.subList(0, count - 1);
	}


	String searchRequiredImageId(String ownerId, Platform platform, Architecture arch) {
		//suggest to use Amazon distributes AMI, the owner ID is 137112412989.
		try {
			for (MachineImage img : machineImageSupport.searchImages(ownerId, null, platform, arch, ImageClass.MACHINE)) {
				if (img.getCurrentState().equals(MachineImageState.ACTIVE)) {
					LOG.info("Image name {} is available for application.", img.getName());
					return img.getProviderMachineImageId();
				}
			}
		} catch (Exception e) {
			throw processException(e);
		}
		return null;
	}

	public void launchNodes(String tag, int count) throws CloudException, InternalException {
		//below check is very important, in order to quick finish one method when parameter is invalid
		if (count <= 0) {
			return;
		}
		String ownerId = "137112412989";
		String description = "m1.medium";
		Architecture targetArchitecture = Architecture.I64;
		String hostName = getTagString(config);
		String imageId = searchRequiredImageId(ownerId, Platform.UNIX, targetArchitecture);
		VirtualMachineProduct product = getVirtualMachineProduct(description, targetArchitecture);
		VMLaunchOptions options = createVmLaunchOptions(hostName, imageId, product, tag);
		int createdCnt = 0;
		List<String> vmIds = newArrayList();
		while (createdCnt < count) {
			int toCreateCnt = count - createdCnt;
			vmIds.addAll((List<String>) options.buildMany(cloudProvider, toCreateCnt));
			createdCnt += vmIds.size();
		}
		LOG.info("Launched {} virtual machines, waiting for they become running ...", createdCnt);
		List<VirtualMachine> virtualMachines = newArrayList();
		for (String vmId : vmIds) {
			VirtualMachine vm = virtualMachineSupport.getVirtualMachine(vmId);
			virtualMachines.add(vm);
		}
		waitUntilVmToBeRunning(virtualMachines);

		for (VirtualMachine vm : virtualMachines) {
			putNodeIntoVmCache(vm);
		}
		remoteSshExecCommand(virtualMachines, Action.ADD.name());
	}

	private void remoteSshExecCommand(List<VirtualMachine> virtualMachines, String action) {
		AgentAutoScaleScriptExecutor executor = new AgentAutoScaleScriptExecutor(
				config.getAgentAutoScaleControllerIP(), config.getAgentAutoScaleControllerPort(),
				config.getAgentAutoScaleDockerRepo(), config.getAgentAutoScaleDockerTag());
		for (VirtualMachine vm : virtualMachines) {
			try {
				VirtualMachine virtualMachine = virtualMachineSupport.getVirtualMachine(vm.getProviderVirtualMachineId());
				RawAddress[] puip = virtualMachine.getPublicAddresses();
				for (RawAddress ip : puip) {
					String node_ip = ip.getIpAddress();
					if (node_ip.contains(".")) {
						executor.run(node_ip, DEFAULT_AMZ_AMI_EC2_USER, action);
						break;
					}
				}
			} catch (InternalException e) {
				throw processException(e);
			} catch (CloudException e) {
				throw processException(e);
			}
		}
	}

	/**
	 * Put the VM into the vmCache, it is convenient to the next operation, such ADD, ON, OFF.
	 * Attention: ensure that the virtual machine is initialized ready, else, it may have no IP assigned.
	 *
	 * @param vm the virtual machine to be added into cache
	 */
	private void putNodeIntoVmCache(VirtualMachine vm) {
		String vmId = vm.getProviderVirtualMachineId();
		AutoScaleNode node = new AutoScaleNode(vmId, System.currentTimeMillis());
		String ips = getPrivateIPs(vm);
		node.setPrivateIPs(ips);
		vmCache.put(vmId, node);
	}

	private String getPrivateIPs(VirtualMachine vm) {
		RawAddress[] priIPs = vm.getPrivateAddresses();
		String ips = "";
		for (RawAddress rawAddress : priIPs) {
			//If there are more than one IP, use one blank space to separate each IP.
			ips += rawAddress.getIpAddress() + " ";
		}
		ips = StringUtils.trim(ips);
		return ips;
	}

	private void waitUntilVmToBeRunning(List<VirtualMachine> vms) throws InternalException, CloudException {
		for (VirtualMachine vm : vms) {
			while (vm != null && !vm.getCurrentState().equals(VmState.RUNNING)) {
				ThreadUtils.sleep(5000L);
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
		for (VirtualMachineProduct each : virtualMachineSupport.listProducts(vmProductFilterOpt)) {
			if (each.getDescription().contains(description)) {
				return each;
			}
		}
		return null;
	}

	private VMLaunchOptions createVmLaunchOptions(String hostName, String imageId, VirtualMachineProduct product, String tag) throws InternalException, CloudException {
		VMLaunchOptions options = VMLaunchOptions.getInstance(
				checkNotNull(product).getProviderProductId(),
				checkNotNull(imageId),
				checkNotNull(hostName), hostName, hostName)
				.withMetaData(NGRINDER_AGENT_TAG_KEY, tag);
		IdentityServices identity = cloudProvider.getIdentityServices();
		ShellKeySupport keySupport = checkNotNull(identity).getShellKeySupport();
		/*
		 * Set user data to allow remote control VM without tty, then, SSH can operate at background.
		 *
		 * Attention: please do not remove the space between "Defaults" and "requiretty", else the replacement will fail
		 */
		// TODO: Not Tested. Please make the agent_auto_scale_script/docker-init.sh
		options.withUserData(dockerInitScript);

		for (SSHKeypair each : checkNotNull(keySupport).list()) {
			if (checkNotNull(each.getProviderKeypairId()).equalsIgnoreCase("agent")) {
				return options.withBootstrapKey(each.getProviderKeypairId());
			}
		}
		try {
			String pubKey = Files.toString(new File("/home/agent/.ssh/id_rsa.pub"), Charset.forName("ISO-8859-1")).trim();
			pubKey = new String(Base64.encodeBase64(pubKey.getBytes()));
			String keyId = keySupport.importKeypair("agent", pubKey).getProviderKeypairId();
			return options.withBootstrapKey(checkNotNull(keyId));
		} catch (IOException e) {
			throw processException(e);
		}
	}

	/**
	 * The reason why to use VM ID as the bridge between touchCache and vmCache is that VM will lost IP information,
	 * and, VM can have the same name (tag:Name or tag:Description in AWS), the VM ID is unique.
	 * <p/>
	 * When performance test is on going (start, running or stop),  IP of one VM should be available, because first
	 * step is to activate nodes, then the node information required should be cached in vmCache.
	 *
	 * @param privateIP private IP of node
	 */
	@Override
	public void touch(String privateIP) {
		ConcurrentMap<String, AutoScaleNode> vms = vmCache.asMap();
		String foundVmId = null;
		for (String vmId : vms.keySet()) {
			AutoScaleNode node = vms.get(vmId);
			if (node.getPrivateIPs().equalsIgnoreCase(privateIP)) {
				foundVmId = vmId;
				break;
			}
		}
		if (foundVmId != null) {
			touchCache.put(foundVmId, System.currentTimeMillis());
			LOG.info("VM {} with private IP {} is updated in cache.", foundVmId, privateIP);
		}
	}

	@Override
	public void onRemoval(RemovalNotification<String, Long> removal) {
		final String key = removal.getKey();
		RemovalCause removalCause = removal.getCause();
		if (removalCause.equals(RemovalCause.EXPIRED)) {
			Runnable stopRunnable = new Runnable() {
				@Override
				public void run() {
					try {
						virtualMachineSupport.stop(key);
					} catch (InternalException e) {
						throw processException(e);
					} catch (CloudException e) {
						throw processException(e);
					}
				}
			};
			new Thread(stopRunnable).start();
		}
	}

	/**
	 * The action command which will be used to operate the created VM by executing shell script remotely.
	 */
	private enum Action {
		ADD, ON, OFF
	}
}
