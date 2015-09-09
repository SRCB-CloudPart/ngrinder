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
package org.ngrinder.agent.service.autoscale;

import org.ngrinder.agent.model.AutoScaleNode;
import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.ngrinder.agent.service.AgentAutoScaleService;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.schedule.ScheduledTaskService;
import org.ngrinder.perftest.service.AgentManager;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Null object for AgentAutoScaleAction
 *
 * @since 3.3.2
 */
@Qualifier("null")
public class NullAgentAutoScaleAction extends AgentAutoScaleAction {

	private static final AgentAutoScaleAction NULL_AGENT_AUTO_SCALE_ACTION = new NullAgentAutoScaleAction();


	@Override
	public void init(Config config, AgentManager agentManager, ScheduledTaskService scheduledTaskService) {

	}

	@Override
	public void activateNodes(int total) throws AgentAutoScaleService.NotSufficientAvailableNodeException {

	}


	@Override
	public int getMaxNodeCount() {
		return 0;
	}

	@Override
	public int getActivatableNodeCount() {
		return 0;
	}

	@Override
	public void touch(String name) {
	}


	@Override
	public String getDiagnosticInfo() {
		return "null";
	}

	@Override
	public void destroy() {

	}

	@Override
	public List<AutoScaleNode> getNodes() {
		return new ArrayList<AutoScaleNode>();
	}

	@Override
	public void refresh() {

	}

	@Override
	public void stopNode(String nodeId) {

	}
}
