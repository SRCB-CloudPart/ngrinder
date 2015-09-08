/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ngrinder.agent.service;

import net.sf.cglib.core.DebuggingClassWriter;
import org.ngrinder.agent.model.AutoScaleNode;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.schedule.ScheduledTaskService;
import org.ngrinder.perftest.service.AgentManager;

import java.util.List;

/**
 * Abstract class for Auto Scale Action. The subclass should handle the specific auto scale action depending on the cloud provider.
 */
public abstract class AgentAutoScaleAction {


	/**
	 * Initialize the AgentAutoScaleAction.
	 *
	 * @param config       config
	 * @param agentManager agentManager for the current activated nodes.
	 */
	public abstract void init(Config config, AgentManager agentManager, ScheduledTaskService scheduledTaskService);

	/**
	 * Activate the given count of node.
	 *
	 * @param total    the total count of necessary agent
	 * @param required the required count of agents which should be activated more.
	 */
	public abstract void activateNodes(int total, int required) throws AgentAutoScaleService.NotSufficientAvailableNodeException;

	/**
	 * Touch the given node
	 *
	 * @param name node name
	 */
	public abstract void touch(String name);

	/**
	 * Stop the given node.
	 *
	 * @param nodeId node id
	 */
	public abstract void stopNode(String nodeId);

	/**
	 * Get the max count of node allowed
	 *
	 * @return the count of node
	 */
	public abstract int getMaxNodeCount();

	/**
	 * Get the count of activatbale node
	 *
	 * @return the count of node
	 */
	public abstract int getActivatableNodeCount();


	/**
	 * For diagnositc
	 *
	 * @return info
	 */
	public abstract String getDiagnosticInfo();

	/**
	 * Destroy component
	 */
	public abstract void destroy();

	/**
	 * Get all nodes info
	 *
	 * @return node info
	 */
	public abstract List<AutoScaleNode> getNodes();


	/**
	 * Refresh caches.
	 */
	public abstract void refresh();
}