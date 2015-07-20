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
package org.ngrinder.common.constant;

/**
 * Clustering related Constants.
 *
 * @since 3.4
 */
public interface AgentDynamicConstants {
	public static final String PROP_AGENT_AUTO_SCALE_TYPE = "agent.auto_scale_type";
	public static final String PROP_AGENT_AUTO_SCALE_AWS_IDENTITY = "agent.auto_scale_aws_identity";
	public static final String PROP_AGENT_AUTO_SCALE_AWS_CREDENTIAL = "agent.auto_scale_aws_credential";
	public static final String PROP_AGENT_AUTO_SCALE_MAX = "agent.auto_scale_max";
	public static final String PROP_AGENT_AUTO_SCALE_GUARD_TIME = "agent.auto_scale_guard_time";
	public static final String PROP_AGENT_AUTO_SCALE_DOCKER_REPO = "agent.auto_scale_docker_repo";
	public static final String PROP_AGENT_AUTO_SCALE_DOCKER_TAG = "agent.auto_scale_docker_tag";
	public static final String PROP_AGENT_AUTO_SCALE_CONTROLLER_IP = "agent.auto_scale_controller_ip";
	public static final String PROP_AGENT_AUTO_SCALE_CONTROLLER_PORT = "agent.auto_scale_controller_port";
}
