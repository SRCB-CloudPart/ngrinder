#!/bin/bash
set -e -x

yum install wget -y

# increase file open limit
echo "root soft  nofile 40000" >> /etc/security/limits
echo "root hard  nofile 40000" >> /etc/security/limits
ulimit -n 40000

# install docker
wget -qO- https://get.docker.com/ | sh >> /tmp/ngrinder_agent_provision.log 2>&1

# allow tcp access
# Below line is for Amazon generic unix system, if the VM is based on Ubuntu, should check /etc/default/docker DOCKER_OPTS
sed -i s/'^OPTIONS='/'OPTIONS="-H tcp:\/\/0.0.0.0:10000 -H unix:\/\/\/var\/run\/docker.sock"'/ /etc/sysconfig/docker

# make docker auto startable
chkconfig docker on

# start docker
service docker start

# download docker image
docker pull ngrinder/agent:3.3-p2