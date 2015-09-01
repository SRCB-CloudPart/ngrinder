package org.ngrinder.agent.controller;

import org.ngrinder.agent.service.AgentAutoScaleAction;
import org.ngrinder.agent.service.AgentAutoScaleService;
import org.ngrinder.common.controller.BaseController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
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
	private AgentAutoScaleService service;

	/**
	 * Open the agent auto scale viewer.
	 *
	 * @param model model
	 * @return operation/announcement
	 */

	@RequestMapping(value = {"node_mgnt/", "node_mgnt"}, method = RequestMethod.GET)
	public String open(Model model) {
		final AgentAutoScaleAction agentAutoScaleAction = service.getAgentAutoScaleAction();
		model.addAttribute("nodes", agentAutoScaleAction.getNodes());
		return "agent/auto_scale";
	}
}
