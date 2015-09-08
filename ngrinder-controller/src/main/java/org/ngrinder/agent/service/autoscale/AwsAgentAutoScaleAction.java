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
import org.ngrinder.common.exception.NGrinderRuntimeException;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.schedule.ScheduledTaskService;
import org.ngrinder.perftest.service.AgentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.*;
import java.util.concurrent.*;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Sets.newHashSet;
import static java.util.Collections.synchronizedList;
import static net.grinder.util.NetworkUtils.tryHttpConnection;
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
public class AwsAgentAutoScaleAction extends AgentAutoScaleAction implements RemovalListener<String, Boolean> {

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
	private int daemonPort;

	/**
	 * Cache b/w virtual machine ID and last touched date
	 */
	private Cache<String, Boolean> touchCache = CacheBuilder.newBuilder().expireAfterAccess(60, TimeUnit.MINUTES).removalListener(this).build();

	/**
	 * Cache b/w virtual machine count by state
	 */
	private Cache<String, Integer> vmCountCache = CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();


	/**
	 * Tag for agent group identification.
	 */
	private String tag;

	private Proxy proxy;

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
		this.daemonPort = config.getAgentAutoScaleProperties().getPropertyInt(PROP_AGENT_AUTO_SCALE_DOCKER_DAEMON_PORT);
		final String proxyHost = config.getProxyHost();
		final int proxyPort = config.getProxyPort();
		if (StringUtils.isNotBlank(proxyHost) && proxyPort != 0) {
			this.proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
		}

		initFilterMap(config);
		initComputeService(config);
		initCache(config);
	}

	private void initCache(Config config) {
		for (VirtualMachine vm : listAllVM()) {
			if (vm.getCurrentState() != VmState.STOPPED) {
				touchCache.put(vm.getProviderVirtualMachineId(), true);
			}
		}
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

	private Supplier<List<VirtualMachine>> vmSupplier() {
		return new Supplier<List<VirtualMachine>>() {
			public List<VirtualMachine> get() {
				try {
					VMFilterOptions vmFilterOptions = VMFilterOptions.getInstance().withTags(filterMap);
					return (List<VirtualMachine>) virtualMachineSupport.listVirtualMachines(vmFilterOptions);
				} catch (Exception e) {
					throw processException(e);
				}
			}

			@Override
			public void invalidate() {
				// Do nothing.
			}
		};
	}


	protected List<VirtualMachine> listAllVM() {
		return vmCache.get();
	}


	protected List<VirtualMachine> listVMByState(Set<VmState> vmStates) {
		List<VirtualMachine> filterResult = newArrayList();
		for (VirtualMachine vm : vmCache.get()) {
			if (vmStates.isEmpty() || vmStates.contains(vm.getCurrentState())) {
				filterResult.add(vm);
			}
		} 
		return filterResult;
	}


	@Override
	public void activateNodes(int count) throws AgentAutoScaleService.NotSufficientAvailableNodeException {
		int activatableNodeCount = getActivatableNodeCount();
		if (activatableNodeCount < count) {
			throw new AgentAutoScaleService.NotSufficientAvailableNodeException(
					String.format("%d node activation is requested. But only %d stopped nodes (total %d nodes) are available. The activation is canceled.", count, activatableNodeCount, getMaxNodeCount())
			);
		}
		List<VirtualMachine> candidates = getActivatableNodes().subList(0, count);
		// To speed up
		if (candidates.isEmpty()) {
			return;
		}
		activateNodes(candidates);
	}

	private List<VirtualMachine> getActivatableNodes() {
		return listVMByState(Sets.newHashSet(VmState.STOPPED));
	}

	@Override
	public int getMaxNodeCount() {
		return Math.min(listAllVM().size(), maxNodeCount);

	}

	@Override
	public int getActivatableNodeCount() {
		return Math.min(getActivatableNodes().size(), maxNodeCount);
	}

	/**
	 * Activate the given nodes.
	 * Activation includes starting node and starting ngrinder agent docker container.
	 *
	 * @param vms virtual machines to be activated.
	 */
	void activateNodes(List<VirtualMachine> vms) {
		try {
			List<VirtualMachine> activatedNodes = startNodes(vms);
			activatedNodes = waitUntilVmState(activatedNodes, VmState.RUNNING, 3 * 1000);
			activatedNodes = waitUntilPortAvailable(activatedNodes, daemonPort, 60 * 1000);
			activatedNodes = startContainers(activatedNodes);
			waitUntilAgentOn(activatedNodes, 1000);
		} catch (Exception e) {
			throw processException(e);
		}
	}


	List<VirtualMachine> startNodes(List<VirtualMachine> vms) {
		List<VirtualMachine> result = newArrayList();
		for (VirtualMachine each : vms) {
			try {
				startNodes(each);
				result.add(each);
			} catch (Exception e) {
				LOG.error("Failed to activate node {}", each.getProviderVirtualMachineId(), e);
			}
		}
		return result;
	}

	void waitUntilAgentOn(List<VirtualMachine> activatedNodes, int requiredCount, int checkInterval) {
		if (activatedNodes.isEmpty()) {
			return;
		}
		LOG.info("Free agents： {}, activate agents: {}, required agents: {}",
				new Object[]{agentManager.getAllFreeApprovedAgents().size(), activatedNodes.size(), requiredCount});
		//We need more elaborated way to wait， here, wait 15min at most
		for (int i = 0; i < 650; i++) {
			if (agentManager.getAllFreeApprovedAgents().size() < activatedNodes.size()) {
				sleep(checkInterval);
			} else {
				return;
			}
		}
	}

	List<VirtualMachine> startContainers(List<VirtualMachine> activatedNodes) {
		List<VirtualMachine> containerStartedNode = newArrayList();
		for (VirtualMachine each : activatedNodes) {
			try {
				LOG.info("Container Starting : Start the container in VM {}", each.getProviderVirtualMachineId());
				startContainer(each);
				containerStartedNode.add(each);
			} catch (Exception e) {
				LOG.error("Container Starting : Failed to start container in node {}", each.getProviderVirtualMachineId(), e);
			}
		}
		return containerStartedNode;
	}

	List<VirtualMachine> waitUntilPortAvailable(List<VirtualMachine> vms, final int port, final int timeoutmilli) {
		ExecutorService taskExecutor = Executors.newFixedThreadPool(vms.size());
		final List<VirtualMachine> result = synchronizedList(new ArrayList<VirtualMachine>());
		final int timeout = (proxy == null) ? (timeoutmilli / 20) : 4000;
		for (final VirtualMachine each : vms) {
			taskExecutor.execute(new Runnable() {
				@Override
				public void run() {
					for (int i = 0; i < 20; i++) {
						for (String ip : getAddresses(each)) {
							String url = "http://" + ip + ":" + daemonPort;
							LOG.info("Port Activation : try to connect {}", url);

							if (tryHttpConnection(url, timeout, proxy)) {
								LOG.info("Port Activation : {} {} is activated.", each.getProviderVirtualMachineId(), url);
								result.add(each);
								return;
							}
							if (proxy != null) {
								sleep(timeout);
							}
						}
					}
					LOG.error("Port Activation : {} port {} is not activated.", each.getProviderVirtualMachineId(), port);
				}
			});
		}
		taskExecutor.shutdown();
		try {
			taskExecutor.awaitTermination(timeoutmilli, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			throw processException(e);
		}
		return result;
	}

	void startNodes(VirtualMachine vm) {
		try {
			VirtualMachineCapabilities capabilities = virtualMachineSupport.getCapabilities();
			if (capabilities.canStart(vm.getCurrentState())) {
				LOG.info("VM Activation : activate {} from state {} ...", vm.getProviderVirtualMachineId(), vm.getCurrentState());
				virtualMachineSupport.start(vm.getProviderVirtualMachineId());
			} else {
				throw new NGrinderRuntimeException("VM Activation : " + vm.getProviderVirtualMachineId() + " is not the startable state. " + vm.getCurrentState());
			}
		} catch (Exception e) {
			LOG.error("VM Activation : can not activate {}", vm.getProviderVirtualMachineId(), e);
			throw processException(e);
		}
	}

	List<VirtualMachine> waitUntilVmState(List<VirtualMachine> vms, final VmState vmState, final int millisec) {
		final int MAX_RETRY = 20;
		final List<VirtualMachine> result = synchronizedList(new ArrayList<VirtualMachine>());
		ExecutorService taskExecutor = Executors.newFixedThreadPool(vms.size());
		for (final VirtualMachine each : vms) {
			taskExecutor.submit(new Runnable() {
				@Override
				public void run() {
					final String providerVirtualMachineId = each.getProviderVirtualMachineId();
					for (int i = 0; i < MAX_RETRY; i++) {
						VirtualMachine vm = getVirtualMachine(providerVirtualMachineId);
						if (vm == null) {
							LOG.error("VM {} self-terminated before entering a usable state", providerVirtualMachineId);
							return;
						}
						if (vm.getCurrentState().equals(vmState)) {
							LOG.info("Node {}  State change complete ({}), PubIP: {}, PriIP {} ",
									new Object[]{providerVirtualMachineId, vm.getCurrentState(), reflectionToString(vm.getPublicAddresses()), reflectionToString(vm.getPrivateAddresses())});
							result.add(vm);
							vmCache.invalidate();
							touch(vm.getProviderVirtualMachineId());
							return;
						}
						sleep(millisec);
					}
					LOG.error("Waiting for VM {}  has been failed", providerVirtualMachineId);
				}
			});
		}
		taskExecutor.shutdown();
		try {
			taskExecutor.awaitTermination(millisec * MAX_RETRY, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			throw processException(e);
		}
		return result;
	}


	@Override
	public void touch(final String name) {
		synchronized (this) {
			try {
				Long time = touchCache.getIfPresent(name);
				if (time == null) {
					time = System.currentTimeMillis();
				}
				touchCache.put(name, time);
			} catch (Exception e) {
				LOG.error("Error while touch node {}", name, e);
			}
		}
	}

	@Override
	public void onRemoval(@SuppressWarnings("NullableProblems") RemovalNotification<String, Long> removal) {
		final String key = checkNotNull(removal).getKey();
		RemovalCause removalCause = removal.getCause();
		if (removalCause.equals(RemovalCause.EXPIRED)) {
			synchronized (this) {
				scheduledTaskService.runAsync(new Runnable() {
					                              @Override
					                              public void run() {
						                              try {
							                              virtualMachineSupport.stop(checkNotNull(key));
						                              } catch (Exception e) {
							                              LOG.error("Error while stopping vm {}", key, e);
						                              }
					                              }
				                              }
				);
			}
		}
	}

	protected List<String> getAddresses(VirtualMachine vm) {
		List<String> result = newArrayList();
		boolean preferPrivateIp = config.getAgentAutoScaleProperties().getPropertyBoolean(PROP_AGENT_AUTO_SCALE_PREFER_PRIVATE_IP);

		RawAddress[] addresses = preferPrivateIp ? vm.getPrivateAddresses() : vm.getPublicAddresses();
		for (RawAddress each : addresses) {
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
			dockerClient = new AgentAutoScaleDockerClient(config, vm.getProviderVirtualMachineId(), getAddresses(vm), daemonPort);
			dockerClient.createAndStartContainer("ngrinder-agent");
		} finally {
			IOUtils.closeQuietly(dockerClient);
		}
	}
}
