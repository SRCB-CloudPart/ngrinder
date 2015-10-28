package org.ngrinder.agent.service.autoscale;

import org.apache.mesos.MesosSchedulerDriver;
import org.apache.mesos.Protos;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ngrinder.agent.service.AgentAutoScaleService;
import org.ngrinder.common.util.PropertiesWrapper;
import org.ngrinder.infra.config.Config;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;
import static org.ngrinder.common.constant.AgentAutoScaleConstants.*;
import static org.ngrinder.common.util.ThreadUtils.sleep;


public class MesosAgentAutoScaleActionTest {

    private MesosAutoScaleAction mesosAutoScaleAction;
    private MesosSchedulerDriver driver;
    private ThreadedScheduledTaskService scheduledTaskService;
    private Config config;
    private String frameworkName;
    private String offerId1 = "2015-10-28-0-00001", frameworkId1 = "2015-10-28-1-10001", slaveId1 = "2015-10-28-2-20001";


    @Before
    public void setUp() {
        mesosAutoScaleAction = createMesosAutoscaleAction();
        scheduledTaskService = getScheduledTaskService();
        config = mock(Config.class);
        PropertiesWrapper agentProperties = mock(PropertiesWrapper.class);
        when(config.getAgentAutoScaleProperties()).thenReturn(agentProperties);
        when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_REPO)).thenReturn("ngrinder/agent");
        when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_DOCKER_TAG)).thenReturn("3.3-p2");
        when(agentProperties.getPropertyInt(PROP_AGENT_AUTO_SCALE_MAX_NODES)).thenReturn(2);
        when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_MESOS_RESOURCE_ATTRIBUTES)).thenReturn("CPU:1;MEM:1024");
        when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_TYPE)).thenReturn("mesos");
        when(config.getControllerAdvertisedHost()).thenReturn("127.0.0.1");
        when(config.getRegion()).thenReturn("None");
        when(agentProperties.getProperty(PROP_AGENT_AUTO_SCALE_MESOS_MASTER)).thenReturn("127.0.0.1:5050");

        driver = mock(MesosSchedulerDriver.class);
        frameworkName = mesosAutoScaleAction.getTagString(config);
        mesosAutoScaleAction.init(config, scheduledTaskService, driver);
    }

    @After
    public void after() {
        scheduledTaskService.destroy();
    }

    protected ThreadedScheduledTaskService getScheduledTaskService() {
        return new ThreadedScheduledTaskService();
    }

    protected MesosAutoScaleAction createMesosAutoscaleAction() {
        return spy(new MesosAutoScaleAction() {
            @Override
            public void startFramework() {
                Date date = new Date();
                SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,S");
                String timeline = simpleDateFormat.format(date);
                System.out.println(timeline + " INFO  Mock 'startFramework()' is started");
            }

            @Override
            protected int getTouchCacheDuration() {
                return 30;
            }

            @Override
            protected int getLatchTimer() {
                return 1;
            }
        });
    }

    @Test(expected = AgentAutoScaleService.NotSufficientAvailableNodeException.class)
    public void testActivateNodes() throws AgentAutoScaleService.NotSufficientAvailableNodeException {
        mesosAutoScaleAction.activateNodes(1);
    }

    private Protos.Offer prepareOffer(String cpu, String mem, String offerId, String fwId, String slaveId, String hn) {

        org.apache.mesos.Protos.Value.Scalar cpuScalar = org.apache.mesos.Protos.Value.Scalar.newBuilder().setValue(Double.valueOf(cpu)).build();
        org.apache.mesos.Protos.Value.Scalar memScalar = org.apache.mesos.Protos.Value.Scalar.newBuilder().setValue(Double.valueOf(mem)).build();

        Protos.Resource cpuRes = Protos.Resource.newBuilder().setName("cpus").setType(Protos.Value.Type.SCALAR).mergeScalar(cpuScalar).build();
        Protos.Resource memRes = Protos.Resource.newBuilder().setName("mem").setType(Protos.Value.Type.SCALAR).mergeScalar(memScalar).build();

        return Protos.Offer.newBuilder().setId(Protos.OfferID.newBuilder().setValue(offerId).build())
                .setFrameworkId(Protos.FrameworkID.newBuilder().setValue(fwId).build())
                .setSlaveId(Protos.SlaveID.newBuilder().setValue(slaveId).build())
                .setHostname(hn)
                .addResources(cpuRes)
                .addResources(memRes)
                .build();
    }

    @Test
    public void testGetResourceAttributes() {
        Map<String, String> resAttrs = newHashMap();
        resAttrs.put("cpus", "1");
        resAttrs.put("mem", "1024");
        Map<String, String> res = mesosAutoScaleAction.getResourceAttributes(config);
        assertEquals(resAttrs.size(), res.size());
        assertEquals(resAttrs.get("cpus"), res.get("cpus"));
        assertEquals(resAttrs.get("mem"), res.get("mem"));
    }

    //Test case that there is NO perftest is coming.
    @Test
    public void testResourceOffers1() {
        List<Protos.Offer> offers = newArrayList();
        Protos.Offer offer1 = prepareOffer("2", "2048", offerId1, frameworkId1, slaveId1, "Slave1");
        String slaveId2 = "2015-10-28-2-20002";
        String frameworkId2 = "2015-10-28-1-10002";
        String offerId2 = "2015-10-28-0-00002";
        Protos.Offer offer2 = prepareOffer("4", "4096", offerId2, frameworkId2, slaveId2, "Slave2");
        offers.add(offer1);
        offers.add(offer2);

        mesosAutoScaleAction.resourceOffers(driver, offers);

        assertEquals(0, mesosAutoScaleAction.getNodes().size());
    }

    //Test case that THERE IS perftest is coming.
    @Test
    public void testResourceOffers2() throws Exception {

        final List<Protos.Offer> offers = newArrayList();
        Protos.Offer offer1 = prepareOffer("2", "2048", offerId1, frameworkId1, slaveId1, "Slave1");
        offers.add(offer1);

        Runnable activateRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    mesosAutoScaleAction.activateNodes(1);
                } catch (AgentAutoScaleService.NotSufficientAvailableNodeException e) {
                    System.out.println(e.getMessage());
                }
            }
        };

        Runnable offerRunnable = new Runnable() {
            @Override
            public void run() {

                mesosAutoScaleAction.resourceOffers(driver, offers);
                Protos.TaskID taskId = Protos.TaskID.newBuilder().setValue(frameworkName + "-" + slaveId1).build();
                Protos.TaskStatus taskStatus = Protos.TaskStatus.newBuilder().setTaskId(taskId).setState(Protos.TaskState.TASK_RUNNING).build();
                mesosAutoScaleAction.statusUpdate(driver, taskStatus);
            }
        };

		/*
         * Attention, Here, just simulate the resourceOffer function is called once, and it should be called later than
		 * activateNodes function, so, inorder to sync the order, add a sleep after thread activateNodes called, then, ensure
		 * that resourceOffer thread is finished before to call assertEquals to check the final result, use Thread.join function
		 * before call assertEquals
		 */
        Thread actThread = new Thread(activateRunnable);
        Thread offThread = new Thread(offerRunnable);
        actThread.start();
        Thread.sleep(2000);
        offThread.start();
        offThread.join();

        assertEquals(1, mesosAutoScaleAction.getNodes().size());
    }


    @Test
    public void testCacheTimeout() {

        Protos.TaskID taskId = Protos.TaskID.newBuilder().setValue(frameworkName + "-" + slaveId1).build();
        Protos.TaskStatus taskStatus = Protos.TaskStatus.newBuilder().setTaskId(taskId).setState(Protos.TaskState.TASK_RUNNING)
                .setSlaveId(Protos.SlaveID.newBuilder().setValue(slaveId1).build())
                .build();

        mesosAutoScaleAction.statusUpdate(driver, taskStatus);

        assertEquals(1, mesosAutoScaleAction.getNodes().size());
        mesosAutoScaleAction.touch(slaveId1);
        sleep(40 * 1000);

        assertEquals(0, mesosAutoScaleAction.getNodes().size());
    }

    @Test
    public void testGetActivatableNodeCount(){
        Protos.TaskID taskId = Protos.TaskID.newBuilder().setValue(frameworkName + "-" + slaveId1).build();
        Protos.TaskStatus taskStatus = Protos.TaskStatus.newBuilder().setTaskId(taskId).setState(Protos.TaskState.TASK_RUNNING)
                .setSlaveId(Protos.SlaveID.newBuilder().setValue(slaveId1).build())
                .build();

        mesosAutoScaleAction.statusUpdate(driver, taskStatus);

        int count = mesosAutoScaleAction.getActivatableNodeCount();

        assertEquals(1,count);
    }

    @Test
    public void testGetMaxNodeCount(){
        assertEquals(2, mesosAutoScaleAction.getMaxNodeCount());
    }
}