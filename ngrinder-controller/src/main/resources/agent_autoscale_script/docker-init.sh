#!/bin/bash
set -e -x
yum install wget -y
sed -i s/'Defaults    requiretty'/'#Defaults    requiretty'/ /etc/sudoers                  # allow root access without tty.
wget -qO- https://get.docker.com/ | sh >> /tmp/ngrinder_agent_provision.log 2>&1           # install docker
sed -i s/..../																			   # FIXME: allow tcp access
chkconfig docker on																		   # make docker auto startable
service docker start                                                                       # start docker
docker pull ngrinder/agent:3.3                                                             # download docker image