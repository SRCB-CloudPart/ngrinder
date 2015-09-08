package org.ngrinder.agent.service.autoscale;


import com.google.common.cache.Cache;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.Sets;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.apache.commons.lang3.StringUtils;
import org.dasein.cloud.*;
import org.dasein.cloud.aws.AWSCloud;
import org.dasein.cloud.compute.*;
import org.dasein.cloud.network.RawAddress;
import org.ngrinder.agent.model.AutoScaleNode;
import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.ngrinder.agent.service.AgentAutoScaleService;
import org.ngrinder.common.exception.NGrinderRuntimeException;
import org.ngrinder.common.util.Suppliers.Supplier;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.schedule.ScheduledTaskService;
import org.ngrinder.perftest.service.AgentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.google.common.cache.CacheBuilder.newBuilder;
import static com.google.common.collect.Lists.newArrayList;
import static java.util.Collections.synchronizedList;
import static net.grinder.util.NetworkUtils.tryHttpConnection;
import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.apache.commons.lang3.builder.ToStringBuilder.reflectionToString;
import static org.ngrinder.common.constant.AgentAutoScaleConstants.*;
import static org.ngrinder.common.util.ExceptionUtils.processException;
import static org.ngrinder.common.util.Preconditions.checkNotEmpty;
import static org.ngrinder.common.util.Preconditions.checkNotNull;
import static org.ngrinder.common.util.Suppliers.memoizeWithExpiration;
import static org.ngrinder.common.util.Suppliers.synchronizedSupplier;
import static org.ngrinder.common.util.ThreadUtils.sleep;

/**
 * AWS AutoScaleAction.
 */
@Qualifier("aws")
public class AwsAgentAutoScaleAction extends AgentAutoScaleAction implements RemovalListener<String, Long> {
	private static final Logger LOG = LoggerFactory.getLogger(AwsAgentAutoScaleAction.class);
	private static final String NGRINDER_AGENT_TAG_KEY = "ngrinder-agent";
	private Map<String, String> filterMap;
	private Config config;
	private AgentManager agentManager;
	private ScheduledTaskService scheduledTaskService;
	private VirtualMachineSupport virtualMachineSupport;
	private CloudProvider cloudProvider;
	private int daemonPort;
	private int maxNodeCount;
	/**
	 * Cache latest virtual machines
	 */
	private final Supplier<List<VirtualMachine>> vmCache = synchronizedSupplier(memoizeWithExpiration(vmSupplier(), 1, TimeUnit.MINUTES));

	/**
	 * Cache b/w virtual machine ID and last touched timestamp
	 */
	private Cache<String, Long> touchCache = newBuilder().expireAfterAccess(60, TimeUnit.MINUTES).removalListener(this).build();


	/**
	 * Proxy to which the AWS calls are made.
	 */
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
	public List<AutoScaleNode> getNodes() {
		List<AutoScaleNode> result = newArrayList();
		for (VirtualMachine each : listAllVM()) {
			AutoScaleNode node = new AutoScaleNode();
			node.setId(each.getProviderVirtualMachineId());
			node.setName(each.getName());
			node.setIps(getAddresses(each));
			node.setState(each.getCurrentState().name());
			result.add(node);
		}
		return result;
	}

	@Override
	public void refresh() {
		vmCache.invalidate();
	}

	@Override
	public void stopNode(String nodeId) {
		try {
			vmCache.invalidate();
			LOG.info("VM : Start stopping {}", nodeId);
			virtualMachineSupport.stop(checkNotNull(nodeId));
			LOG.info("VM : stopping {} is succeeded.", nodeId);
		} catch (Exception e) {
			throw processException("Error while stopping node " + nodeId, e);
		} finally {

			vmCache.invalidate();
		}
	}

	@Override
	public void init(Config config, AgentManager agentManager, ScheduledTaskService scheduledTaskService) {
		this.config = config;
		this.agentManager = agentManager;
		this.scheduledTaskService = scheduledTaskService;
		this.daemonPort = config.getAgentAutoScaleProperties().getPropertyInt(PROP_AGENT_AUTO_SCALE_DOCKER_DAEMON_PORT);
		this.maxNodeCount = config.getAgentAutoScaleProperties().getPropertyInt(PROP_AGENT_AUTO_SCALE_MAX_NODES);
		initProxy(config);
		initFilterMap(config);
		initComputeService(config);
	}

	protected void initProxy(Config config) {
		final String proxyHost = config.getProxyHost();
		final int proxyPort = config.getProxyPort();
		if (StringUtils.isNotBlank(proxyHost) && proxyPort != 0) {
			this.proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
		}
	}

	protected String getTagString(Config config) {
		return config.getControllerAdvertisedHost();
	}

	protected void initFilterMap(Config config) {
		filterMap = new HashMap<String, String>();
		filterMap.put(NGRINDER_AGENT_TAG_KEY, getTagString(config));
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
		synchronized (this) {
			return vmCache.get();
		}
	}

	protected List<VirtualMachine> listVMByState(Set<VmState> vmStates) {
		List<VirtualMachine> filterResult = newArrayList();
		for (VirtualMachine vm : listAllVM()) {
			if (vmStates.isEmpty() || vmStates.contains(vm.getCurrentState())) {
				filterResult.add(vm);
			}
		}
		return filterResult;
	}


	@Override
	public void activateNodes(int total, int required) throws AgentAutoScaleService.NotSufficientAvailableNodeException {
		int activatableNodeCount = getActivatableNodeCount();
		if (activatableNodeCount < required) {
			throw new AgentAutoScaleService.NotSufficientAvailableNodeException(
					String.format("%d node activation is requested. But only %d stopped nodes (total %d nodes) are available. The activation is canceled.", required, activatableNodeCount, getMaxNodeCount())
			);
		}
		List<VirtualMachine> candidates = getActivatableNodes().subList(0, required);
		// To speed up
		if (candidates.isEmpty()) {
			return;
		}
		activateNodes(candidates);
		waitUntilAgentOn(total, 1000);
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
			startContainers(activatedNodes);
		} catch (Exception e) {
			throw processException(e);
		}
	}


	List<VirtualMachine> startNodes(List<VirtualMachine> vms) {
		List<VirtualMachine> result = newArrayList();
		for (VirtualMachine each : vms) {
			try {
				result.add(startNodes(each));
			} catch (Exception e) {
				LOG.error("Failed to activate node {}", each.getProviderVirtualMachineId(), e);
			}
		}
		return result;
	}

	void waitUntilAgentOn(int count, int checkInterval) {
		if (count == 0) {
			return;
		}
		for (int i = 0; i < 100; i++) {
			if (agentManager.getAllFreeApprovedAgents().size() < count) {
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
					for (String ip : getAddresses(each)) {
						for (int i = 0; i < 20; i++) {
							String url = "http://" + ip + ":" + daemonPort;
							LOG.info("Port Activation : try to connect {}", url);

							if (tryHttpConnection(url, timeout, proxy)) {
								LOG.info("Port Activation : {} {} is activated.", each.getProviderVirtualMachineId(), url);
								result.add(each);
								return;
							}
							sleep(timeout);
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

	VirtualMachine startNodes(VirtualMachine vm) {
		try {
			VirtualMachineCapabilities capabilities = virtualMachineSupport.getCapabilities();
			if (capabilities.canStart(vm.getCurrentState())) {
				LOG.info("VM Activation : activate {} from state {} ...", vm.getProviderVirtualMachineId(), vm.getCurrentState());
				virtualMachineSupport.start(vm.getProviderVirtualMachineId());
			} else {
				throw new NGrinderRuntimeException("VM Activation : " + vm.getProviderVirtualMachineId() + " is not the startable state. " + vm.getCurrentState());
			}
			return vm;
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
							                              stopNode(key);
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
		boolean preferPrivateIp = config.getAgentAutoScaleProperties().getPropertyBoolean(PROP_AGENT_AUTO_SCALE_PRIVATE_IP);
		RawAddress[] addresses = preferPrivateIp ? vm.getPrivateAddresses() : vm.getPublicAddresses();
		for (RawAddress each : addresses) {
			result.add(each.getIpAddress());
		}
		return result;
	}

	protected VirtualMachine getVirtualMachine(String vmID) {
		try {
			return virtualMachineSupport.getVirtualMachine(vmID);
		} catch (Exception e) {
			throw processException(e);
		}
	}

	protected void startContainer(VirtualMachine vm) {
		AgentAutoScaleDockerClient dockerClient = null;
		try {
			dockerClient = new AgentAutoScaleDockerClient(config, vm.getProviderVirtualMachineId(), getAddresses(vm), daemonPort);
			dockerClient.createAndStartContainer("ngrinder-agent");
		} finally {
			closeQuietly(dockerClient);
		}
	}
}
