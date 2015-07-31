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
public interface AgentAutoScaleConstants {
    String PROP_AGENT_AUTO_SCALE_TYPE = "agent.auto_scale.type";
    String PROP_AGENT_AUTO_SCALE_IDENTITY = "agent.auto_scale.identity";
    String PROP_AGENT_AUTO_SCALE_CREDENTIAL = "agent.auto_scale.credential";
    String PROP_AGENT_AUTO_SCALE_MAX_NODES = "agent.auto_scale.max_nodes";
    String PROP_AGENT_AUTO_SCALE_GUARD_TIME = "agent.auto_scale.guard_time";
    String PROP_AGENT_AUTO_SCALE_DOCKER_REPO = "agent.auto_scale.docker_repo";
    String PROP_AGENT_AUTO_SCALE_DOCKER_TAG = "agent.auto_scale.docker_tag";
    String PROP_AGENT_AUTO_SCALE_CONTROLLER_IP = "agent.auto_scale.controller_ip";
    String PROP_AGENT_AUTO_SCALE_CONTROLLER_PORT = "agent.auto_scale.controller_port";
    String PROP_AGENT_AUTO_SCALE_MESOS_FRAMEWORK_NAME = "agent.auto_scale_mesos_framework_name";
    String PROP_AGENT_AUTO_SCALE_MESOS_USER = "agent.auto_scale_mesos_user";
    String PROP_AGENT_AUTO_SCALE_MESOS_PRINCIPAL = "agent.auto_scale_mesos_principal";
    String PROP_AGENT_AUTO_SCALE_MESOS_SECRET = "agent.auto_scale_mesos_secret";
    String PROP_AGENT_AUTO_SCALE_MESOS_MASTER = "agent.auto_scale_mesos_master";
    String PROP_AGENT_AUTO_SCALE_MESOS_LIB_PATH = "agent.auto_scale_mesos_lib_path";
    String PROP_AGENT_AUTO_SCALE_MESOS_SLAVE_ATTRIBUTES = "agent.auto_scale_mesos_slave_attributes";
}
