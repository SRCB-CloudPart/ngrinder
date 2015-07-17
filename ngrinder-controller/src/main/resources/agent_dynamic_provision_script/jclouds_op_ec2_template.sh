#!/bin/bash

#This is script template, which will be used to construct new script for RUN, ON, OFF operation
#PLEASE DO NO MODIFY if you do not understand the working mechanism.
#
#This template will be interpreted through freemarker, therefore, below variable parameter will
#follow the syntax of ftl

AGENT_CTRL_IP=${agent_controller_ip}
AGENT_CTRL_PORT=${agent_controller_port}
AGENT_IMG_REPO=${agent_image_repo}
AGENT_IMG_TAG=${agent_image_tag}
AGENT_WORK_MODE=${agent_work_mode}

####################################################################################
START_TIME=`date +%T`
DATE=`date +%Y-%m-%d-%H%M-%S --date=$START_TIME`
PID=$$
####################################################################################
RET_MAX=3
function SCRIPT_START()
{
    LOG_PRINT "PID             :  $PID"
    LOG_PRINT `echo`
}

function LOG_PRINT()
{
    echo "[`date "+%D %T"`][$AGENT_WORK_MODE]  $1" >> /tmp/AutoAgentProvision.log
}

function check_install_docker()
{
    sudo docker 1>>/dev/null 2>&1
    if [[ $? == 0 ]]; then
       LOG_PRINT "Docker is existing already"
    else
       cnt=1
       while true
       do
            sudo wget -qO- https://get.docker.com/ | sh >> /tmp/AutoAgentProvision.log 2>&1
            #sudo yum install -y docker-io >> /tmp/AutoAgentProvision.log 2>&1
            if [[ $? != 0 ]];then
                LOG_PRINT "Install docker failed $cnt times..."
            else
                LOG_PRINT "Docker is installed successfully"
                break
            fi
            sleep 5
            cnt=$((cnt + 1))
            if [[ $cnt -eq $RET_MAX ]]; then
                exit 1
            fi
       done
    fi
    sleep 2
    cnt=1
    while true
    do
        sudo service docker start >> /tmp/AutoAgentProvision.log 2>&1
        if [[ $? == 0 ]];then
            LOG_PRINT "Start docker daemon service successfully.."
            break
        else
            LOG_PRINT "Start docker daemon service failed $cnt times..."
        fi
        sleep 5
        cnt=$((cnt + 1))
        if [[ $cnt -eq $RET_MAX ]]; then
            exit 2
        fi
    done
}

function check_pull_agent_image()
{
    IS_FOUND="NO"
    finds=`sudo docker images | awk '{print $1":"$2}'`
    for item in $finds
    do
        if [[ $item == $AGENT_IMG_REPO:$AGENT_IMG_TAG ]]; then
            IS_FOUND="YES"
            break
        fi
    done
    if [[ $IS_FOUND != "YES" ]];then
       LOG_PRINT "Begin to pull ngrinder agent [$AGENT_IMG_REPO:$AGENT_IMG_TAG]..."
       cnt=1
       while true
       do
            sudo docker pull $AGENT_IMG_REPO:$AGENT_IMG_TAG >> /tmp/AutoAgentProvision.log 2>&1
            if [[ $? != 0 ]]; then
                LOG_PRINT ">>>>>----->>>>> docker pull $AGENT_IMG_REPO:$AGENT_IMG_TAG failed $cnt times..."
            else
                LOG_PRINT "=============== docker pull $AGENT_IMG_REPO:$AGENT_IMG_TAG successfully..."
                break
            fi
            sleep 5
            cnt=$((cnt + 1))
            if [[ $cnt -eq $RET_MAX ]]; then
                exit 3
            fi
       done
    else
       LOG_PRINT "Agent image [$AGENT_IMG_REPO:$AGENT_IMG_TAG] is existing."
    fi
}

function check_start_agent_container()
{
    AGENT_RUNNING="NO"
    agents=`sudo docker ps | awk '{print $2}'`
    for item in $agents
    do
        if [[ $item == $AGENT_IMG_REPO:$AGENT_IMG_TAG ]]; then
            AGENT_RUNNING="YES"
            break
        fi
    done
    if [[ $AGENT_RUNNING != "YES" ]];then
        cnt=1
        while true
        do
            sudo docker run --net=host -d -e "CONTROLLER_ADDR=$AGENT_CTRL_IP:$AGENT_CTRL_PORT" $AGENT_IMG_REPO:$AGENT_IMG_TAG >>/tmp/AutoAgentProvision.log 2>&1
            if [[ $? != 0 ]]; then
                LOG_PRINT "Start docker container [$AGENT_CTRL_IP:$AGENT_CTRL_PORT]- [$AGENT_IMG_REPO:$AGENT_IMG_TAG] failed $cnt times..."
            else
                LOG_PRINT "Start docker container [$AGENT_CTRL_IP:$AGENT_CTRL_PORT]- [$AGENT_IMG_REPO:$AGENT_IMG_TAG] successfully..."
                break
            fi
            sleep 5
            cnt=$((cnt + 1))
            if [[ $cnt -eq $RET_MAX ]]; then
                exit 4
            fi
        done
    else
        LOG_PRINT "Docker container [$AGENT_CTRL_IP:$AGENT_CTRL_PORT]- [$AGENT_IMG_REPO:$AGENT_IMG_TAG] is running..."
    fi
}

function check_stop_remove_agent_container()
{
    agents=`sudo docker ps -a| awk '{print $1}'`
    for item in $agents
    do
        if [[ X$item != X"CONTAINER" ]];then
            cnt=1
            while true
            do
                sudo docker stop $item >> /tmp/AutoAgentProvision.log 2>&1
                if [[ $? != 0 ]]; then
                    LOG_PRINT "Stop container $item failed $cnt times..."
                else
                    LOG_PRINT "Stop container $item successfully..."
                    break
                fi
                cnt=$((cnt + 1))
                if [[ $cnt -eq $RET_MAX ]]; then
                    break
                fi
            done
            cnt=1
            while true
            do
                sudo docker rm -f $item >> /tmp/AutoAgentProvision.log 2>&1
                if [[ $? != 0 ]]; then
                    LOG_PRINT "Remove container $item failed $cnt times..."
                else
                    LOG_PRINT "Remove container $item successfully..."
                    break
                fi
                cnt=$((cnt + 1))
                if [[ $cnt -eq $RET_MAX ]]; then
                    break
                fi
            done
        fi
    done
}

SCRIPT_START
case $AGENT_WORK_MODE in
    "ADD")
        check_install_docker
        check_pull_agent_image
        check_start_agent_container
        ;;
    "ON")
        check_install_docker
        check_stop_remove_agent_container
        check_start_agent_container
        ;;
    "OFF")
        check_stop_remove_agent_container
        ;;
     *)
        LOG_PRINT "Error, not supported operation: $AGENT_WORK_MODE"
        exit 5
        ;;
esac