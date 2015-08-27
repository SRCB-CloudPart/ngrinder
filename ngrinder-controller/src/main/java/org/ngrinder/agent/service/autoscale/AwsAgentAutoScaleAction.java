package org.ngrinder.agent.service.autoscale;


import com.google.common.cache.*;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang3.StringUtils;
import org.dasein.cloud.Cloud;
import org.dasein.cloud.CloudProvider;
import org.dasein.cloud.ContextRequirements;
import org.dasein.cloud.ProviderContext;
import org.dasein.cloud.aws.AWSCloud;
import org.dasein.cloud.compute.*;
import org.dasein.cloud.network.RawAddress;
import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.ngrinder.agent.service.AgentAutoScaleService;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.schedule.ScheduledTaskService;
import org.ngrinder.perftest.service.AgentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Sets.newHashSet;
import static org.apache.commons.lang3.builder.ToStringBuilder.reflectionToString;
import static org.ngrinder.common.constant.AgentAutoScaleConstants.*;
import static org.ngrinder.common.util.ExceptionUtils.processException;
import static org.ngrinder.common.util.Preconditions.checkNotEmpty;
import static org.ngrinder.common.util.Preconditions.checkNotNull;
import static org.ngrinder.common.util.ThreadUtils.sleep;

/**
 * AWS AutoScaleAction.
 */
@Qualifier("aws")
public class AwsAgentAutoScaleAction extends AgentAutoScaleAction implements RemovalListener<String, VirtualMachine> {

	private static final Logger LOG = LoggerFactory.getLogger(AwsAgentAutoScaleAction.class);
	private static final String NGRINDER_AGENT_TAG_KEY = "ngrinder-agent";
	private static final String VM_COUNT_CACHE_STOPPED_NODES = "stopped-nodes";
	private static final String VM_COUNT_CACHE_TOTAL_NODES = "total-node";
	private final Map<String, String> filterMap = new HashMap<String, String>();
	private Config config;
	private AgentManager agentManager;
	private ScheduledTaskService scheduledTaskService;
	private VirtualMachineSupport virtualMachineSupport;
	private CloudProvider cloudProvider;

	/**
	 * Cache b/w virtual machine ID and last touched date
	 */
	private Cache<String, VirtualMachine> touchCache = CacheBuilder.newBuilder().expireAfterAccess(60, TimeUnit.MINUTES).removalListener(this).build();

	/**
	 * Cache b/w virtual machine count by state
	 */
	private Cache<String, Integer> vmCountCache = CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();


	/**
	 * Tag for agent group identification.
	 */
	private String tag;


	@Override
	public String getDiagnosticInfo() {
		StringBuilder builder = new StringBuilder();
		for (VirtualMachine vm : listAllVM()) {
			builder.append(ToStringBuilder.reflectionToString(vm)).append("\n");
		}
		return builder.toString();
	}

	@Override
	public void destroy() {
		cloudProvider.close();
	}

	@Override
	public void init(Config config, AgentManager agentManager, ScheduledTaskService scheduledTaskService) {
		this.config = config;
		this.agentManager = agentManager;
		this.scheduledTaskService = scheduledTaskService;
		this.tag = getTagString(config);
		initFilterMap(config);
		initComputeService(config);
	}

	private String getTagString(Config config) {
		if (config.isClustered()) {
			return config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_CONTROLLER_IP) + ":" + config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_CONTROLLER_PORT);
		} else {
			return config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_CONTROLLER_IP);
		}
	}

	protected void initFilterMap(Config config) {
		filterMap.put(NGRINDER_AGENT_TAG_KEY, tag);
	}

	protected void initComputeService(Config config) {
		try {
			// Use that information to register the cloud
			@SuppressWarnings("unchecked") Cloud cloud = setUpProvider();
			String regionId = checkNotNull(config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_REGION), "agent.auto_scale.region option should be provided to activate AWS agent auto scale.");
			String proxyHost = config.getProxyHost();
			int proxyPort = config.getProxyPort();

			// Find what additional fields are necessary to connect to the cloud
			ContextRequirements requirements = cloud.buildProvider().getContextRequirements();
			List<ContextRequirements.Field> fields = requirements.getConfigurableValues();

			// Load the values for the required fields from the system properties
			List<ProviderContext.Value> values = new ArrayList<ProviderContext.Value>();
			for (ContextRequirements.Field f : fields) {
				if (f.type.equals(ContextRequirements.FieldType.KEYPAIR)) {

					String shared = checkNotEmpty(config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_IDENTITY),
							"agent.auto_scale.identity option should be provided to activate the AWS agent auto scale.");
					String secret = checkNotEmpty(config.getAgentAutoScaleProperties().getProperty(PROP_AGENT_AUTO_SCALE_CREDENTIAL),
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
			this.cloudProvider = ctx.connect();
			this.virtualMachineSupport = checkNotNull(cloudProvider.getComputeServices()).getVirtualMachineSupport();
		} catch (Exception e) {
			throw processException("Exception occured while setting up AWS agent auto scale", e);
		}
	}

	protected Cloud setUpProvider() {
		return Cloud.register("Amazon", "AWS", "", AWSCloud.class);
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
	public void activateNodes(int count) throws AgentAutoScaleService.NotSufficientAvailableNodeException {
		vmCountCache.cleanUp();
		List<VirtualMachine> vms = listVMByState(newHashSet(VmState.STOPPED));
		if (vms.size() < count) {
			throw new AgentAutoScaleService.NotSufficientAvailableNodeException(
					String.format("%d node activation is requested. But only %d stopped nodes (total %d nodes) are available. The activation is canceled.", count, vms.size(), getMaxNodeCount())
			);
		}
		List<VirtualMachine> candidates = vms.subList(0, count);
		// To speed up
		if (candidates.isEmpty()) {
			return;
		}
		activateNodes(candidates);
	}

	@Override
	public int getMaxNodeCount() {
		try {
			return vmCountCache.get(VM_COUNT_CACHE_TOTAL_NODES, new Callable<Integer>() {
				@Override
				public Integer call() throws Exception {
					return Math.min(listAllVM().size(),
							config.getAgentAutoScaleProperties().getPropertyInt(PROP_AGENT_AUTO_SCALE_MAX_NODES));
				}
			});
		} catch (ExecutionException e) {
			LOG.error("Error while counting total nodes", e);
			return 0;
		}
	}


	public int getActivatableNodeCount() {
		try {
			return vmCountCache.get(VM_COUNT_CACHE_STOPPED_NODES, new Callable<Integer>() {
				@Override
				public Integer call() throws Exception {
					return Math.min(listVMByState(newHashSet(VmState.STOPPED)).size(),
							config.getAgentAutoScaleProperties().getPropertyInt(PROP_AGENT_AUTO_SCALE_MAX_NODES));
				}
			});
		} catch (ExecutionException e) {
			LOG.error("Error while counting activatable nodes", e);
			return 0;
		}
	}

	/**
	 * Activate the given nodes.
	 * Activation includes starting node and starting ngrinder agent docker container.
	 *
	 * @param vms virtual machines to be activated.
	 */
	protected void activateNodes(List<VirtualMachine> vms) {
		try {
			List<VirtualMachine> activatedNodes = newArrayList();

			for (VirtualMachine each : vms) {
				try {
					activateNode(each);
					activatedNodes.add(each);
				} catch (Exception e) {
					LOG.error("Failed to activate node {}", each.getProviderVirtualMachineId(), e);
				}
			}
			vmCountCache.invalidate(VM_COUNT_CACHE_STOPPED_NODES);
			/*
			 * this waiting can not be removed, else that start container can not be executed...
			 */
			activatedNodes = waitUntilVmState(activatedNodes, VmState.RUNNING, 3000);
			sleep(2000);
			List<VirtualMachine> containerStartedNode = newArrayList();
			for (VirtualMachine each : activatedNodes) {
				try {
					startContainer(each);
					containerStartedNode.add(each);
				} catch (Exception e) {
					LOG.error("Failed to start container in node {}", each.getProviderVirtualMachineId(), e);
				}
			}

			if (containerStartedNode.isEmpty()) {
				return;
			}
			//We need more elaborated way to wait， here, wait 15min at most
			for (int i = 0; i < 650; i++) {
				if (agentManager.getAllFreeApprovedAgents().size() < containerStartedNode.size()) {
					sleep(1000);
				} else {
					return;
				}
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

	protected List<VirtualMachine> waitUntilVmState(List<VirtualMachine> vms, VmState vmState, int millisec) {
		List<VirtualMachine> result = newArrayList();
		for (VirtualMachine each : vms) {
			VirtualMachine vm = getVirtualMachine(each.getProviderVirtualMachineId());
			int tries = 0;
			try {
				while (vm != null && !vm.getCurrentState().equals(vmState)) {
					sleep(millisec);
					vm = getVirtualMachine(each.getProviderVirtualMachineId());
					if (tries++ >= 20) {
						LOG.info("after 20 tries, it's failed to make the vm to " + vmState.name());
						break;
					}
				}
			} catch (Exception e) {
				LOG.error("Waiting for VM " + vm.getProviderVirtualMachineId() + " has beend failed", e);
			}
			if (vm == null) {
				LOG.info("VM self-terminated before entering a usable state");
			} else {
				result.add(vm);
				LOG.info("Node {}  State change complete ({}), PubIP: {}, PriIP {} ",
						new Object[]{vm.getProviderVirtualMachineId(), vm.getCurrentState(), reflectionToString(vm.getPublicAddresses()), reflectionToString(vm.getPrivateAddresses())});
			}
		}
		return result;

	}


	@Override
	public void touch(String name) {
		synchronized (this) {
			touchCache.getIfPresent(name);
		}
	}

	@Override
	public void onRemoval(RemovalNotification<String, VirtualMachine> removal) {
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
							                              LOG.error("Error while stopping vm {}", key, e);
						                              }
						                              vmCountCache.invalidate(VM_COUNT_CACHE_STOPPED_NODES);
					                              }
				                              }
				);
			}
		}
	}

	protected List<String> getAddresses(VirtualMachine vm) {
		List<String> result = newArrayList();
		for (RawAddress each : vm.getPrivateAddresses()) {
			result.add(each.getIpAddress());
		}
		for (RawAddress each : vm.getPublicAddresses()) {
			result.add(each.getIpAddress());
		}
		return result;
	}

	private VirtualMachine getVirtualMachine(String vmID) {
		try {
			return virtualMachineSupport.getVirtualMachine(vmID);
		} catch (Exception e) {
			throw processException(e);
		}
	}

	public void startContainer(VirtualMachine vm) {
		AgentAutoScaleDockerClient dockerClient = null;
		try {
			dockerClient = new AgentAutoScaleDockerClient(config, vm.getProviderMachineImageId(), getAddresses(vm));
			dockerClient.createAndStartContainer("ngrinder-agent");
		} finally {
			IOUtils.closeQuietly(dockerClient);
		}
	}
}
