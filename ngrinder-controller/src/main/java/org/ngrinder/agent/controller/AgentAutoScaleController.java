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
package org.ngrinder.agent.controller;

import org.ngrinder.agent.service.AgentAutoScaleService;
import org.ngrinder.common.controller.BaseController;
import org.ngrinder.common.controller.RestAPI;
import org.ngrinder.common.util.FileUtils;
import org.ngrinder.infra.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.annotation.PostConstruct;

/**
 * Agent auto scale controller.
 *
 * @since 3.3.1
 */
@Controller
@RequestMapping("/agent/node_mgnt")
@PreAuthorize("hasAnyRole('A', 'S')")
public class AgentAutoScaleController extends BaseController {

	@Autowired
	private AgentAutoScaleService agentAutoScaleService;

	@Autowired
	private Config config;

	private String nodeInitScript;

	@PostConstruct
	public void init() {
		nodeInitScript = FileUtils.getResourceString("agent_autoscale_script/docker-init.sh");
	}

	/**
	 * Show agent's nodes
	 *
	 * @param model model
	 * @return agent/auto_scale
	 */
	@RequestMapping(value = {"/", ""}, method = RequestMethod.GET)
	public String view(Model model) {
		model.addAttribute("autoScaleType", config.getAgentAutoScaleType());
		model.addAttribute("advertisedHost", config.getControllerAdvertisedHost());
		model.addAttribute("totalNodeCount", agentAutoScaleService.getTotalNodeSize());
		model.addAttribute("activatableNodeCount", agentAutoScaleService.getActivatableNodeSize());
		model.addAttribute("nodes", agentAutoScaleService.getNodes());
		model.addAttribute("nodeInitScript", nodeInitScript);
		return "agent/auto_scale";
	}


	@Autowired
	private AgentAutoScaleService service;

	/**
	 * Stop the agent's node.
	 * Note: the value part in request mapping should add ":.+" to "id", if not, the content of "id" may lose
	 * 		 some information if it contains some dot.
	 * 		 e.g. if "id" content is "10.148.15.220-20151008-093050-83070474-5050-27333-0", then application
	 * 		 will get "10.148.15" if not add ":.+" to "id" in the request mapping.
	 *
	 * @param nodeId node id
	 * @param model  model
	 * @return agent/auto_scale
	 */
	@RestAPI
	@RequestMapping(value = "/api/{id:.+}", params = "action=stop", method = RequestMethod.PUT)
	public HttpEntity<String> stopNode(@PathVariable("id") String nodeId, Model model) {
		agentAutoScaleService.stopNode(nodeId);
		return successJsonHttpEntity();
	}

	/**
	 * Stop the agent's node
	 *
	 * @param model model
	 * @return agent/auto_scale
	 */
	@RestAPI
	@RequestMapping(value = "/api/refresh", method = RequestMethod.GET)
	public HttpEntity<String> refresh(Model model) {
		agentAutoScaleService.refresh();
		return successJsonHttpEntity();
	}

}
