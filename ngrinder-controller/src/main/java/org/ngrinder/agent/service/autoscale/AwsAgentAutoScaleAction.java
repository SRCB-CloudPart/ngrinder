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
	public void activateNodes(int activateCount, int requiredCount) throws AgentAutoScaleService.NotSufficientAvailableNodeException {
		vmCountCache.cleanUp();
		List<VirtualMachine> vms = listVMByState(newHashSet(VmState.STOPPED));
		if (vms.size() < activateCount) {
			throw new AgentAutoScaleService.NotSufficientAvailableNodeException(
					String.format("%d node activation is requested. But only %d stopped nodes (total %d nodes) are available. The activation is canceled.", activateCount, vms.size(), getMaxNodeCount())
			);
		}
		List<VirtualMachine> candidates = vms.subList(0, activateCount);
		// To speed up
		if (candidates.isEmpty()) {
			return;
		}
		activateNodes(candidates, requiredCount);
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
					final int size = listVMByState(newHashSet(VmState.STOPPED)).size();
					return Math.min(size,
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
	 * @param requiredCount the original required agent count
	 */
	void activateNodes(List<VirtualMachine> vms, int requiredCount) {
		try {
			final int port = config.getAgentAutoScaleProperties().getPropertyInt(PROP_AGENT_AUTO_SCALE_DOCKER_DAEMON_PORT);
			List<VirtualMachine> activatedNodes = startNodes(vms);
			activatedNodes = waitUntilVmState(activatedNodes, VmState.RUNNING, 3 * 1000);
			activatedNodes = waitUntilPortAvailable(activatedNodes, port, 60 * 1000);
			activatedNodes = startContainers(activatedNodes);
			waitUntilAgentOn(activatedNodes,requiredCount, 1000);
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
		vmCountCache.invalidate(VM_COUNT_CACHE_STOPPED_NODES);
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
			if (agentManager.getAllFreeApprovedAgents().size() < requiredCount) {
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

//	List<VirtualMachine> waitUntilPortAvailable(List<VirtualMachine> vms, final int port, final int timeoutmilli) {
//		ExecutorService taskExecutor = Executors.newFixedThreadPool(vms.size());
//		final List<VirtualMachine> result = synchronizedList(new ArrayList<VirtualMachine>());
//		final int timeout = (proxy == null) ? (timeoutmilli / 20) : 4000;
//		for (final VirtualMachine each : vms) {
//			taskExecutor.execute(new Runnable() {
//				@Override
//				public void run() {
//					for (int i = 0; i < 20; i++) {
//						for (String ip : getAddresses(each)) {
//							String url = "http://" + ip + ":" + daemonPort;
//							LOG.info("Port Activation : try to connect {}", url);
//
//							if (tryHttpConnection(url, timeout, proxy)) {
//								LOG.info("Port Activation : {} {} is activated.", each.getProviderVirtualMachineId(), url);
//								result.add(each);
//								return;
//							}
//							//below is a bug???
////							if (proxy != null) {
////								sleep(timeout);
////							}
//							sleep(timeout);
//						}
//					}
//					LOG.error("Port Activation : {} port {} is not activated.", each.getProviderVirtualMachineId(), port);
//				}
//			});
//		}
//		taskExecutor.shutdown();
//		try {
//			taskExecutor.awaitTermination(timeoutmilli, TimeUnit.MILLISECONDS);
//		} catch (InterruptedException e) {
//			throw processException(e);
//		}
//		return result;
//	}

	List<VirtualMachine> waitUntilPortAvailable(List<VirtualMachine> vms, final int port, final int timeoutmilli) {
		List<VirtualMachine> result = synchronizedList(new ArrayList<VirtualMachine>());
		int timeout = (proxy == null) ? (timeoutmilli / 20) : 4000;
		for (final VirtualMachine each : vms) {
			boolean connected = false;
			for (int i = 0; i < 20; i++) {
				for (String ip : getAddresses(each)) {
					String url = "http://" + ip + ":" + daemonPort;
					LOG.info("Port Activation : try to connect {}", url);

					if (tryHttpConnection(url, timeout, proxy)) {
						LOG.info("Port Activation : {} {} is activated.", each.getProviderVirtualMachineId(), url);
						result.add(each);
						connected = true;
						break;
					}
					sleep(timeout);
				}
				if(connected) {
					break;
				}
			}
			if(!connected) {
				LOG.error("Port Activation : {} port {} is not activated.", each.getProviderVirtualMachineId(), port);
			}
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

//	List<VirtualMachine> waitUntilVmState(List<VirtualMachine> vms, final VmState vmState, final int millisec) {
//		final int MAX_RETRY = 20;
//		final List<VirtualMachine> result = synchronizedList(new ArrayList<VirtualMachine>());
//		ExecutorService taskExecutor = Executors.newFixedThreadPool(vms.size());
//		for (final VirtualMachine each : vms) {
//			taskExecutor.submit(new Runnable() {
//				@Override
//				public void run() {
//					final String providerVirtualMachineId = each.getProviderVirtualMachineId();
//					for (int i = 0; i < MAX_RETRY; i++) {
//						VirtualMachine vm = getVirtualMachine(providerVirtualMachineId);
//						if (vm == null) {
//							LOG.error("VM {} self-terminated before entering a usable state", providerVirtualMachineId);
//							return;
//						}
//						if (vm.getCurrentState().equals(vmState)) {
//							LOG.info("Node {}  State change complete ({}), PubIP: {}, PriIP {} ",
//									new Object[]{providerVirtualMachineId, vm.getCurrentState(), reflectionToString(vm.getPublicAddresses()), reflectionToString(vm.getPrivateAddresses())});
//							result.add(vm);
//							return;
//						}
//						sleep(millisec);
//					}
//					LOG.error("Waiting for VM {}  has been failed", providerVirtualMachineId);
//				}
//			});
//		}
//		taskExecutor.shutdown();
//		try {
//			taskExecutor.awaitTermination(millisec * MAX_RETRY, TimeUnit.MILLISECONDS);
//		} catch (InterruptedException e) {
//			throw processException(e);
//		}
//		return result;
//	}


	List<VirtualMachine> waitUntilVmState(List<VirtualMachine> vms, final VmState vmState, final int millisec) {
		final int MAX_RETRY = 20;
		final List<VirtualMachine> result = synchronizedList(new ArrayList<VirtualMachine>());
		for (final VirtualMachine each : vms) {
			final String providerVirtualMachineId = each.getProviderVirtualMachineId();
			for (int i = 0; i < MAX_RETRY; i++) {
				VirtualMachine vm = getVirtualMachine(providerVirtualMachineId);
				if (vm == null) {
					LOG.error("VM {} self-terminated before entering a usable state", providerVirtualMachineId);
					break;
				}
				if (vm.getCurrentState().equals(vmState)) {
					LOG.info("Node {}  State change complete ({}), PubIP: {}, PriIP {} ",
							new Object[]{providerVirtualMachineId, vm.getCurrentState(), reflectionToString(vm.getPublicAddresses()), reflectionToString(vm.getPrivateAddresses())});
					result.add(vm);
					break;
				}
				sleep(millisec);
			}
		}

		return result;
	}

	@Override
	public void touch(String name) {
		synchronized (this) {
			touchCache.put(name, true);
		}
	}

	@Override
	public void onRemoval(RemovalNotification<String, Boolean> removal) {
		synchronized (this) {
			final String key = removal.getKey();
			RemovalCause removalCause = removal.getCause();
			if (removalCause.equals(RemovalCause.EXPIRED)) {
				scheduledTaskService.runAsync(new Runnable() {
					                              @Override
					                              public void run() {
													  LOG.info("To stop node {} ... ", key);
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
