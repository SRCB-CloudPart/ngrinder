package org.ngrinder.agent.service.autoscale;


import com.google.common.cache.*;
import com.google.common.io.Files;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.dasein.cloud.Cloud;
import org.dasein.cloud.CloudProvider;
import org.dasein.cloud.ContextRequirements;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.aws.AWSCloud;
import org.dasein.cloud.compute.*;
import org.dasein.cloud.identity.IdentityServices;
import org.dasein.cloud.identity.SSHKeypair;
import org.dasein.cloud.identity.ShellKeySupport;
import org.dasein.cloud.network.RawAddress;
import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.schedule.ScheduledTaskService;
import org.ngrinder.perftest.service.AgentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Sets.newHashSet;
import static java.util.Collections.sort;
import static org.ngrinder.common.util.ExceptionUtils.processException;
import static org.ngrinder.common.util.Preconditions.checkNotEmpty;
import static org.ngrinder.common.util.Preconditions.checkNotNull;
import static org.ngrinder.common.util.ThreadUtils.sleep;

/**
 * Agent auto scale action for aws.
 */
@Qualifier("aws")
public class AwsAgentAutoScaleAction extends AgentAutoScaleAction implements RemovalListener<String, Long> {

	private static final Logger LOG = LoggerFactory.getLogger(AwsAgentAutoScaleAction.class);
	private static final String NGRINDER_AGENT_TAG_KEY = "ngrinder_agent";
	/**
	 * The list representing the ordered terminatable vm state.
	 */
	private static final List<VmState> TERMINATABLE_STATE_ORDER = newArrayList(VmState.ERROR,
			VmState.TERMINATED, VmState.STOPPED, VmState.STOPPING, VmState.SUSPENDED, VmState.PAUSED,
			VmState.PAUSING, VmState.PENDING, VmState.RUNNING, VmState.REBOOTING);

	private final Map<String, String> filterMap = new HashMap<String, String>();
	private Config config;
	private AgentManager agentManager;
	private ScheduledTaskService scheduledTaskService;
	private VirtualMachineSupport virtualMachineSupport;
	private MachineImageSupport machineImageSupport;
	private CloudProvider cloudProvider;

	/**
	 * Cache b/w virtual machine ID and last touched date
	 */
	private Cache<String, Long> touchCache = CacheBuilder.newBuilder().expireAfterWrite(60, TimeUnit.MINUTES).removalListener(this).build();

	/**
	 * Tag for agent group identification.
	 */
	private String tag;

	/**
	 * Max node to be created.
	 */
	private int maxNodeCount = 0;


	/**
	 * Docker initialization script which will be executed when the node is created.
	 */
	private String dockerInitScript;

	private boolean prepared = false;

	@Override
	public boolean isPrepared() {
		return prepared;
	}

	@Override
	public void init(Config config, AgentManager agentManager, ScheduledTaskService scheduledTaskService) {
		this.config = config;
		this.agentManager = agentManager;
		this.scheduledTaskService = scheduledTaskService;
		this.tag = getTagString(config);
		this.maxNodeCount = config.getAgentAutoScaleMaxNodes();
		initFilterMap(config);
		initComputeService(config);
		initDockerInitScript();
		this.scheduledTaskService.runAsync(new Runnable() {
			@Override
			public void run() {
				initNodes(tag, maxNodeCount);
			}
		});

	}

	private String getTagString(Config config) {
		return config.getAgentAutoScaleControllerIP().replaceAll("\\.", "d");
	}

	private void initFilterMap(Config config) {
		filterMap.put("ngrinder_agent", getTagString(config));
	}

	protected void initDockerInitScript() {
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

	protected void initComputeService(Config config) {
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

	protected void initNodes(String tag, int count) {
		try {
			List<VirtualMachine> existingVMs = listVMByState(newHashSet(VmState.PENDING, VmState.RUNNING, VmState.STOPPED, VmState.STOPPING));
			int size = existingVMs.size();
			if (size <= count) {
				prepareNodes(tag, count - size);
			} else if (size > count) {
				terminateNodes(existingVMs, size - count);
			}
		} catch (Exception e) {
			LOG.error("Node initializations is failed.", e);
			throw processException(e);
		} finally {
			prepared = true;
		}
	}


	protected List<VirtualMachine> listAllVM() {
		return listVMByState(Collections.<VmState>emptySet());
	}


	protected List<VirtualMachine> listVMByState(Set<VmState> vmStates) {
		List<VirtualMachine> filterResult = newArrayList();
		try {
			VMFilterOptions vmFilterOptions = VMFilterOptions.getInstance().withTags(filterMap);
			List<VirtualMachine> vms = (List<VirtualMachine>) virtualMachineSupport.listVirtualMachines(vmFilterOptions);
			for (VirtualMachine vm : vms) {
				if (vmStates.isEmpty() || vmStates.contains(vm.getCurrentState())) {
					filterResult.add(vm);
				}
			}
		} catch (Exception e) {
			throw processException(e);
		}
		return filterResult;
	}


	@Override
	public void suspendAllNodes() {
		final List<VirtualMachine> virtualMachines = listVMByState(new HashSet<VmState>());
		try {
			final VirtualMachineCapabilities capabilities = virtualMachineSupport.getCapabilities();
			for (VirtualMachine vm : virtualMachines) {
				if (capabilities.canStop(vm.getCurrentState())) {
					virtualMachineSupport.stop(vm.getProviderVirtualMachineId());
				}
			}
		} catch (Exception e) {
			throw processException(e);
		}
	}

	@Override
	public void activateNodes(int count) throws NotSufficientAvailableNodeException {
		List<VirtualMachine> vms = listVMByState(newHashSet(VmState.STOPPED));
		if (vms.size() < count) {
			throw new NotSufficientAvailableNodeException(String.format("%d node activation is requested. But only %d nodes are available. The activation is canceled.", count, vms.size()));
		}
		List<VirtualMachine> candidates = vms.subList(0, vms.size());
		// To speed up
		if (candidates.isEmpty()) {
			return;
		}
		activateNodes(candidates);
	}

	/**
	 * Activate the given nodes.
	 * Activation includes starting node and starting ngrinder agent docker container.
	 *
	 * @param vms virtual machines to be activated.
	 */
	protected void activateNodes(List<VirtualMachine> vms) {
		try {
			for (VirtualMachine each : vms) {
				try {
					activateNode(each);
				} catch (Exception e) {
					LOG.error("Failed to activate node {}", each.getProviderVirtualMachineId(), e);
				}
			}
			for (VirtualMachine each : vms) {
				try {
					startContainer(each);
				} catch (Exception e) {
					LOG.error("Failed to start container in node {}", each.getProviderVirtualMachineId(), e);
				}
			}


			// FIXME. We need more elaborated way to wait
			for (int i = 0; i < 30; i++) {
				if (agentManager.getAllFreeApprovedAgents().size() >= vms.size()) {
					return;
				}
				sleep(5000);
			}
		} catch (Exception e) {
			throw processException(e);
		}
	}


	protected void activateNode(VirtualMachine vm) {
		try {
			VirtualMachineCapabilities capabilities = virtualMachineSupport.getCapabilities();
			if (capabilities.canStart(vm.getCurrentState())) {
				LOG.info("Activating {} from state {} ...", vm.getProviderVirtualMachineId(), vm.getCurrentState());
				virtualMachineSupport.start(vm.getProviderVirtualMachineId());
			} else {
				LOG.info("You cannot activate a VM in the state {} ...", vm.getCurrentState());
			}
		} catch (Exception e) {
			throw processException(e);
		}
	}


	protected void terminateNodes(List<VirtualMachine> vms, int count) {
		terminateNodes(selectTerminatableNodes(vms, count));
	}

	protected void terminateNodes(List<VirtualMachine> vms) {
		for (VirtualMachine each : vms) {
			try {
				terminateNode(each);
			} catch (Exception e) {
				LOG.error("Error while terminating {} node", each.getProviderVirtualMachineId());
			}
		}
	}

	protected void terminateNode(VirtualMachine vm) {
		try {
			VirtualMachineCapabilities capabilities = virtualMachineSupport.getCapabilities();
			if (capabilities.canTerminate(vm.getCurrentState())) {
				LOG.info("Terminating {} from state {} ...", vm.getProviderVirtualMachineId(), vm.getCurrentState());
				virtualMachineSupport.terminate(vm.getProviderVirtualMachineId());
			}
		} catch (Exception e) {
			throw processException(e);
		}
	}


	/**
	 * Select the most appropriate nodes which can be terminated among the given vms
	 * <p/>
	 * This method sort the virtual machine with given #TERMINATABLE_STATE_ORDER state order.
	 *
	 * @param vms   virtual machines
	 * @param count the number of machines which will be terminated.
	 * @return selected virtual machines.
	 */
	protected List<VirtualMachine> selectTerminatableNodes(List<VirtualMachine> vms, int count) {
		List<VirtualMachine> sorted = new ArrayList<VirtualMachine>(vms);
		sort(sorted, new Comparator<VirtualMachine>() {
			@Override
			public int compare(VirtualMachine o1, VirtualMachine o2) {
				return TERMINATABLE_STATE_ORDER.indexOf(o1.getCurrentState()) - TERMINATABLE_STATE_ORDER.indexOf(o2.getCurrentState());
			}
		});
		return sorted.subList(0, Math.min(sorted.size(), count));
	}


	protected MachineImage getMachineImage(String ownerId) {
		try {
			for (MachineImage img : machineImageSupport.searchImages(ownerId, null, Platform.UNIX, Architecture.I64, ImageClass.MACHINE)) {
				if (img.getCurrentState().equals(MachineImageState.ACTIVE)) {
					LOG.info("Machine image {} is available for application.", img.getName());
					return img;
				}
			}
		} catch (Exception e) {
			throw processException(e);
		}
		throw processException("No machine image owned by " + ownerId + ") is available.");
	}


	public void prepareNodes(String tag, int count) {
		try {
			// To speed up.
			if (count <= 0) {
				return;
			}
			MachineImage machineImage = getMachineImage(getOwnerId());
			VirtualMachineProduct product = getVirtualMachineProduct(getVirtualMachineProductDescription());
			List<String> vmids = (List<String>) createVmLaunchOptions("ngrinder-agent", machineImage, product, tag).buildMany(cloudProvider, count);
			LOG.info("Launched {} virtual machines, waiting for they become running ...", count);
			waitUntilVmState(vmids, VmState.RUNNING, 100);
			suspendAllNodes();
		} catch (Exception e) {
			throw processException(e);
		}
	}


	protected void waitUntilVmState(List<String> vmIds, VmState vmState, int sec) {
		final String[] vmidArr = vmIds.toArray(new String[vmIds.size()]);
		try {
			// FIXME. We need more elaborated way to wait
		} catch (Exception e) {
			throw processException(e);
		}
	}

	protected VirtualMachineProduct getVirtualMachineProduct(String description) {
		VirtualMachineProductFilterOptions vmProductFilterOpt = VirtualMachineProductFilterOptions.getInstance().withArchitecture(Architecture.I64);
		try {
			for (VirtualMachineProduct each : virtualMachineSupport.listProducts(vmProductFilterOpt)) {
				if (each.getDescription().contains(description)) {
					return each;
				}
			}
		} catch (Exception e) {
			throw processException(e);
		}
		throw processException("No virtual machine named(" + description + ") is available.");
	}

	protected VMLaunchOptions createVmLaunchOptions(String hostName, MachineImage machineImage, VirtualMachineProduct product, String tag) {
		VMLaunchOptions options = VMLaunchOptions.getInstance(
				checkNotNull(product).getProviderProductId(),
				checkNotNull(machineImage).getProviderMachineImageId(),
				checkNotNull(hostName), hostName, hostName)
				.withMetaData(NGRINDER_AGENT_TAG_KEY, tag);
		try {
			IdentityServices identity = cloudProvider.getIdentityServices();
			ShellKeySupport keySupport = checkNotNull(identity).getShellKeySupport();
			options.withUserData(dockerInitScript);

			for (SSHKeypair each : checkNotNull(keySupport).list()) {
				if (checkNotNull(each.getProviderKeypairId()).equalsIgnoreCase("ngrinder")) {
					return options.withBootstrapKey(each.getProviderKeypairId());
				}
			}


			File keyFile = new File(config.getHome().getDirectory(), "ssh/id_rsa.pub");
			if (keyFile.exists()) {
				String pubKey = Files.toString(keyFile, Charset.forName("ISO-8859-1")).trim();
				pubKey = new String(Base64.encodeBase64(pubKey.getBytes()));
				String keyId = keySupport.importKeypair("ngrinder", pubKey).getProviderKeypairId();
				options.withBootstrapKey(checkNotNull(keyId));
			}
			return options;
		} catch (Exception e) {
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
	 * @param name private IP of node
	 */
	@Override
	public void touch(String name) {
		synchronized (this) {
			final Long value = touchCache.getIfPresent(name);
			if (value != null) {
				touchCache.put(name, System.currentTimeMillis());
			}
		}
	}

	@Override
	public void onRemoval(RemovalNotification<String, Long> removal) {
		synchronized (this) {
			final String key = removal.getKey();
			RemovalCause removalCause = removal.getCause();
			if (removalCause.equals(RemovalCause.EXPIRED)) {
				scheduledTaskService.runAsync(new Runnable() {
					                              @Override
					                              public void run() {
						                              try {
							                              virtualMachineSupport.stop(checkNotNull(key));
						                              } catch (Exception e) {
							                              throw processException(e);
						                              }
					                              }
				                              }
				);
			}
		}
	}

	protected List<RawAddress> getAddresses(VirtualMachine vm) {
		List<RawAddress> rawAddresses = new ArrayList<RawAddress>(Arrays.asList(vm.getPrivateAddresses()));
		rawAddresses.addAll(Arrays.asList(vm.getPublicAddresses()));
		return rawAddresses;
	}

	public void startContainer(VirtualMachine vm) {
		if (vm.getCurrentState() == VmState.RUNNING) {
			AgentAutoScaleDockerClient dockerClient = null;
			try {
				dockerClient = new AgentAutoScaleDockerClient(config, getAddresses(vm));
				dockerClient.createAndStartContainer("ngrinder-agent");
			} finally {
				IOUtils.closeQuietly(dockerClient);
			}
		}
	}


	protected String getVirtualMachineProductDescription() {
		return "m1.medium";
	}

	protected String getOwnerId() {
		return "137112412989";
	}
}
