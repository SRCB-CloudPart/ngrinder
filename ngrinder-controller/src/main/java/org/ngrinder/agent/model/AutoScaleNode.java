package org.ngrinder.agent.model;

import java.util.List;

/**
 * Created by junoyoon on 15. 9. 1.
 */
public class AutoScaleNode {
	private String id;
	private String name;
	private String state;
	private List<String> ips;

	public AutoScaleNode() {

	}

	public List<String> getIps() {
		return ips;
	}

	public void setIps(List<String> ips) {
		this.ips = ips;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getState() {
		return state;
	}

	public void setState(String state) {
		this.state = state;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}
}
