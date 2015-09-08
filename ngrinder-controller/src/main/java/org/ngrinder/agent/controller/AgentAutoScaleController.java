package org.ngrinder.agent.controller;

import org.ngrinder.agent.service.AgentAutoScaleService;
import org.ngrinder.common.controller.BaseController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Agent auto scale controller.
 *
 * @since 3.3.1
 */
@Controller
@RequestMapping("/agent/")
@PreAuthorize("hasAnyRole('A', 'S')")
public class AgentAutoScaleController extends BaseController {

	@Autowired
	private AgentAutoScaleService agentAutoScaleService;

	/**
	 * Show agent's nodes
	 *
	 * @param model model
	 * @return agent/auto_scale
	 */

	@RequestMapping(value = {"node_mgnt/", "node_mgnt"}, method = RequestMethod.GET)
	public String view(Model model) {
		model.addAttribute("totalNodeCount", agentAutoScaleService.getTotalNodeSize());
		model.addAttribute("activatableNodeCount", agentAutoScaleService.getActivatableNodeSize());
		model.addAttribute("nodes", agentAutoScaleService.getNodes());
		return "agent/auto_scale";
	}


	@Autowired
	private AgentAutoScaleService service;

	/**
	 * Stop the agent's node
	 *
	 * @param nodeId node id
	 * @param model  model
	 * @return agent/auto_scale
	 */

	@RequestMapping(value = {"node_mgnt/{id}"}, method = RequestMethod.DELETE)
	public String stopNode(@PathVariable("id") String nodeId, Model model) {
		agentAutoScaleService.stopNode(nodeId);
		return view(model);
	}


}
