package org.ngrinder.perftest.service;

import com.beust.jcommander.internal.Maps;
import com.beust.jcommander.internal.Sets;
import com.google.common.base.Predicates;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.RunScriptOnNodesException;
import org.jclouds.compute.domain.ExecResponse;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.compute.options.TemplateOptions;
import org.jclouds.scriptbuilder.domain.Statement;
import org.ngrinder.common.constant.AgentDynamicConstants;

import java.util.Map;
import java.util.Set;

import static org.jclouds.compute.predicates.NodePredicates.RUNNING;
import static org.jclouds.compute.predicates.NodePredicates.SUSPENDED;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

public class MockDynamicAgentHandler extends DynamicAgentHandler implements AgentDynamicConstants{

    // 0: only list, 1: list + on, 2: list + add, 3: off, 4: on, 5: destroy, 6: add
    protected int condition = 0;
    protected boolean doList = true;
    protected String ctrlIp = "";

    @SuppressWarnings("unchecked")
    @Override
    protected ComputeService initComputeService(String provider, String identity, String credential) {
        ComputeService cs = mock(ComputeService.class);
        Set groupNodes = Sets.newHashSet();
        NodeMetadata nm1 = mock(NodeMetadata.class);
        Set<String> nm1Ip = Sets.newHashSet();
        nm1Ip.add("192.168.1.1");
        NodeMetadata nm2 = mock(NodeMetadata.class);
        Set<String> nm2Ip = Sets.newHashSet();
        nm2Ip.add("192.168.1.2");
        when(nm1.getPrivateAddresses()).thenReturn(nm1Ip);
        when(nm2.getPrivateAddresses()).thenReturn(nm2Ip);
        when(nm1.getId()).thenReturn("201507070001");
        when(nm2.getId()).thenReturn("201507070002");
        groupNodes.add(nm1);
        groupNodes.add(nm2);

        switch (condition){
            case 0:     //list
                setOnlyList(cs, nm1, nm2, groupNodes);
                break;
            case 1:     //list + on
                setListAndOn(cs, nm1, nm2, groupNodes);
                break;
            case 2:     //list + add
                setListAndAdd(cs, nm1, nm2, groupNodes);
                break;
            case 3:     //off
                setOff(cs, nm1, nm2);
                break;
            case 4:     //on
                setOn(cs,nm2);
                break;
            case 5:     //destroy
                setDestroy(cs, nm1, nm2);
                break;
            case 6:     //add
                setAdd(cs);
                break;
        }
        ComputeServiceContext csc = mock(ComputeServiceContext.class);
        when(cs.getContext()).thenReturn(csc);
        doNothing().when(csc).close();
        return cs;
    }

    @SuppressWarnings("unchecked")
    private void setAdd(ComputeService cs){
        TemplateBuilder templateBuilder = mock(TemplateBuilder.class);
        when(cs.templateBuilder()).thenReturn(templateBuilder);
        when(templateBuilder.locationId(anyString())).thenReturn(templateBuilder);
        when(templateBuilder.hardwareId(anyString())).thenReturn(templateBuilder);
        when(templateBuilder.options(any(TemplateOptions.class))).thenReturn(templateBuilder);
        Template template = mock(Template.class);
        when(templateBuilder.build()).thenReturn(template);
        Set addNodes = Sets.newHashSet();
        NodeMetadata node = mock(NodeMetadata.class);
        Set<String> nodeIp = Sets.newHashSet();
        nodeIp.add("192.168.1.3");
        addNodes.add(node);
        String id = "201507070003";

        try {
            String groupName = getGroupName(ctrlIp);
            when(cs.createNodesInGroup(groupName, 1, template)).thenReturn(addNodes);
            when(node.getPrivateAddresses()).thenReturn(nodeIp);
            when(node.getId()).thenReturn(id);
        } catch (RunNodesException e) {
            e.printStackTrace();
        }

        Map adds = Maps.newHashMap();
        ExecResponse res = mock(ExecResponse.class);
        adds.put(node, res);
        try {
            when(cs.runScriptOnNodesMatching(inGivenList(anyList()), anyString(), any(TemplateOptions.class))).thenReturn(adds);
            when(node.getId()).thenReturn(id);
        } catch (RunScriptOnNodesException e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private void setOff(ComputeService cs, NodeMetadata nm1, NodeMetadata nm2){
        when(nm1.getStatus()).thenReturn(NodeMetadata.Status.RUNNING);
        when(nm2.getStatus()).thenReturn(NodeMetadata.Status.SUSPENDED);

        Set<String> node1PriIp = Sets.newHashSet();
        Set<String> node1PubIp = Sets.newHashSet();
        node1PriIp.add("192.168.1.1");
        node1PubIp.add("54.167.1.1");
        Set<String> node2PriIp = Sets.newHashSet();
        Set<String> node2PubIp = Sets.newHashSet();
        node2PriIp.add("192.168.1.2");
        node2PubIp.add("54.167.1.2");

        Map offNodes = Maps.newHashMap();
        ExecResponse res1 = mock(ExecResponse.class);
        offNodes.put(nm1, res1);

        try {
            when(cs.runScriptOnNodesMatching(inGivenList(anyList()), anyString(), any(TemplateOptions.class))).thenReturn(offNodes);
            when(nm1.getId()).thenReturn("201507070001");
            when(nm2.getId()).thenReturn("201507070002");
            when(nm1.getPrivateAddresses()).thenReturn(node1PriIp);
            when(nm2.getPrivateAddresses()).thenReturn(node2PriIp);
            when(nm1.getPublicAddresses()).thenReturn(node1PubIp);
            when(nm2.getPublicAddresses()).thenReturn(node2PubIp);
        } catch (RunScriptOnNodesException e) {
            e.printStackTrace();
        }

        Set turnOff = Sets.newHashSet();
        turnOff.add(nm1);

        when(cs.suspendNodesMatching(Predicates.and(RUNNING, inGivenList(anyList())))).thenReturn(turnOff);
    }

    @SuppressWarnings("unchecked")
    private void setOn(ComputeService cs, NodeMetadata nm2){
        Set<String> node2PriIp = Sets.newHashSet();
        Set<String> node2PubIp = Sets.newHashSet();
        node2PriIp.add("192.168.1.2");
        node2PubIp.add("54.167.1.2");

        Set turnOn = Sets.newHashSet();
        turnOn.add(nm2);

        when(cs.resumeNodesMatching(Predicates.and(SUSPENDED, inGivenList(anyList())))).thenReturn(turnOn);
        when(nm2.getId()).thenReturn("201507070002");
        when(nm2.getPrivateAddresses()).thenReturn(node2PriIp);
        when(nm2.getPublicAddresses()).thenReturn(node2PubIp);


        Map onNodes = Maps.newHashMap();
        ExecResponse res2 = mock(ExecResponse.class);
        onNodes.put(nm2, res2);
        try {
            when(cs.runScriptOnNodesMatching(inGivenList(anyList()), anyString(), any(TemplateOptions.class))).thenReturn(onNodes);
        } catch (RunScriptOnNodesException e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private void setDestroy(ComputeService cs, NodeMetadata nm1, NodeMetadata nm2){
        Set<String> node1PriIp = Sets.newHashSet();
        Set<String> node1PubIp = Sets.newHashSet();
        node1PriIp.add("192.168.1.1");
        node1PubIp.add("54.167.1.1");
        Set<String> node2PriIp = Sets.newHashSet();
        Set<String> node2PubIp = Sets.newHashSet();
        node2PriIp.add("192.168.1.2");
        node2PubIp.add("54.167.1.2");

        Set termNodes = Sets.newHashSet();

        termNodes.add(nm2);
        //when(cs.destroyNodesMatching(Predicates.and(not(TERMINATED), inGivenList(anyList())))).thenReturn(termNodes);
        when(cs.destroyNodesMatching(inGivenList(anyList()))).thenReturn(termNodes);

        when(nm1.getId()).thenReturn("201507070001");
        when(nm1.getPrivateAddresses()).thenReturn(node1PriIp);
        when(nm1.getPublicAddresses()).thenReturn(node1PubIp);
        when(nm2.getId()).thenReturn("201507070002");
        when(nm2.getPrivateAddresses()).thenReturn(node2PriIp);
        when(nm2.getPublicAddresses()).thenReturn(node2PubIp);
    }

    protected Statement createAdminAccess(){
        return mock(Statement.class);
    }

    @SuppressWarnings("unchecked")
    private void setOnlyList(ComputeService cs, NodeMetadata nm1, NodeMetadata nm2, Set groupNodes){
        when(nm1.getStatus()).thenReturn(NodeMetadata.Status.RUNNING);
        when(nm2.getStatus()).thenReturn(NodeMetadata.Status.SUSPENDED);
        when(cs.listNodesDetailsMatching(nodeNameStartsWith(anyString()))).thenReturn(groupNodes);
    }

    protected String getGroupName(String ctrl_ip){
        String groupName = "agt";
        ctrl_ip = ctrl_ip.replaceAll("\\.", "d");
        groupName = groupName + ctrl_ip;
        return groupName;
    }

    @SuppressWarnings("unchecked")
    private void setListAndAdd(ComputeService cs, NodeMetadata nm1, NodeMetadata nm2, Set groupNodes){
        if(doList) {
            //list condition
            when(nm1.getStatus()).thenReturn(NodeMetadata.Status.TERMINATED);
            when(nm2.getStatus()).thenReturn(NodeMetadata.Status.TERMINATED);
            when(cs.listNodesDetailsMatching(nodeNameStartsWith(anyString()))).thenReturn(groupNodes);
            doList = false;
        }else {
            //add condition
            setAdd(cs);
        }
    }

    @SuppressWarnings("unchecked")
    private void setListAndOn(ComputeService cs, NodeMetadata nm1, NodeMetadata nm2, Set groupNodes){
        if(doList) {
            //list condition
            when(nm1.getStatus()).thenReturn(NodeMetadata.Status.SUSPENDED);
            when(nm2.getStatus()).thenReturn(NodeMetadata.Status.SUSPENDED);
            when(cs.listNodesDetailsMatching(nodeNameStartsWith(anyString()))).thenReturn(groupNodes);
            doList = false;
        }else {
            //on condition
            Set onNodes = Sets.newHashSet();
            Set<String> nodeIp = Sets.newHashSet();
            Set<String> nodePubIp = Sets.newHashSet();
            nodeIp.add("192.168.1.1");
            nodePubIp.add("54.167.1.11");
            onNodes.add(nm1);
            when(cs.resumeNodesMatching(Predicates.and(SUSPENDED, inGivenList(anyList())))).thenReturn(onNodes);
            when(nm1.getId()).thenReturn("201507070001");
            when(nm1.getPrivateAddresses()).thenReturn(nodeIp);
            when(nm1.getPublicAddresses()).thenReturn(nodePubIp);

            Map turnOns = Maps.newHashMap();
            ExecResponse res = mock(ExecResponse.class);
            turnOns.put(nm1, res);
            try {
                when(cs.runScriptOnNodesMatching(inGivenList(anyList()), anyString(), any(TemplateOptions.class))).thenReturn(turnOns);
                when(nm1.getId()).thenReturn("201507070001");

            } catch (RunScriptOnNodesException e) {
                e.printStackTrace();
            }
        }
    }
}
