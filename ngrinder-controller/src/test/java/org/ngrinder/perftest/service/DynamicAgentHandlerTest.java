package org.ngrinder.perftest.service;

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

import com.google.common.collect.Lists;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.ngrinder.infra.config.Config;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class DynamicAgentHandlerTest {

    @Mock
    private Config config;

    @InjectMocks
    private MockDynamicAgentHandler dah = new MockDynamicAgentHandler();



    private boolean isHomeExisting = true;
    private boolean isDotSshPathExisting = true;
    private boolean isPrivateKeyExisting = true;
    private boolean isPublicKeyExisting = true;
    private File homePath = null;
    private File dotSshPath = null;
    private File privateKey = null;
    private File publicKey = null;

    @Before
    public void setUp(){
        String type = "EC2";
        String identity = "abcd";
        String credential = "a1b2c3d4";
        String ctrlIP = "192.168.0.1";
        String ctrlPort = "8080";
        String repo = "ngrinder/agent";
        String tag = "3.3";

        when(config.getAgentDynamicType()).thenReturn(type);
        when(config.getAgentDynamicEc2Identity()).thenReturn(identity);
        when(config.getAgentDynamicEc2Credential()).thenReturn(credential);
        when(config.getAgentDynamicControllerIP()).thenReturn(ctrlIP);
        when(config.getAgentDynamicControllerPort()).thenReturn(ctrlPort);
        when(config.getAgentDynamicDockerRepo()).thenReturn(repo);
        when(config.getAgentDynamicDockerTag()).thenReturn(tag);
        when(config.getAgentDynamicNodeMax()).thenReturn(2);
        when(config.getAgentDynamicGuardTime()).thenReturn(60);

        prepareSshEnv();
        dah.ctrlIp = ctrlIP;
        dah.init();
        System.out.println("setUp");
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @After
    public void clean(){
        if(!isPrivateKeyExisting){
            privateKey.delete();
        }
        if(!isPublicKeyExisting){
            publicKey.delete();
        }
        if(!isDotSshPathExisting){
            dotSshPath.delete();
        }
        if(!isHomeExisting){
            homePath.delete();
        }
        System.out.println("clean");
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void prepareSshEnv(){
        String user = System.getProperty("user.name");
        if(user.equalsIgnoreCase("root")){
            homePath = new File("/home/agent");
        }else{
            homePath = new File("/home/", user);
        }
        if(!homePath.exists()){
            isHomeExisting = false;
            homePath.mkdirs();
        }
        dotSshPath = new File(homePath,"/.ssh");
        if(!dotSshPath.exists()){
            isDotSshPathExisting = false;
            dotSshPath.mkdirs();
        }
        privateKey = new File(dotSshPath, "/id_rsa");
        if(!privateKey.exists()){
            isPrivateKeyExisting = false;
            try {
                privateKey.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        publicKey = new File(dotSshPath, "/id_rsa.pub");
        if(!publicKey.exists()){
            isPublicKeyExisting = false;
            try {
                publicKey.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Test
    public void addDynamicEc2InstanceTest(){
        dah.condition = 6;
        String testIdentifier = "PerfTest_2_user";
        dah.addDynamicEc2Instance(testIdentifier, 1);

        assertThat(dah.getAddedNodeCount(), is(1));
        assertThat(dah.getTestIdEc2NodeStatusMap(testIdentifier).containsKey("192.168.1.3"), is(true));
        System.out.println("addDynamicEc2InstanceTest is passed");
    }

    @Test
    public void initFirstOneEc2InstanceTest_only_list() {
        dah.doList = true;
        dah.condition = 0;
        dah.initFirstOneEc2Instance();

        assertThat(dah.getStoppedNodeCount(), is(1));
        assertThat(dah.getAddedNodeCount(), is(2));
        assertThat(dah.getTestIdEc2NodeStatusMap(MockDynamicAgentHandler.KEY_FOR_STARTUP).containsKey("192.168.1.1"), is(true));
        System.out.println("initFirstOneEc2InstanceTest_only_list is passed");
    }

    @Test
    public void initFirstOneEc2InstanceTest_list_on() {
        dah.doList = true;
        dah.condition = 1;
        dah.initFirstOneEc2Instance();

        assertThat(dah.getStoppedNodeCount(), is(1));
        assertThat(dah.getAddedNodeCount(), is(2));
        assertThat(dah.getTestIdEc2NodeStatusMap(MockDynamicAgentHandler.KEY_FOR_STARTUP).containsKey("192.168.1.1"), is(true));
        System.out.println("initFirstOneEc2InstanceTest_list_on is passed");
    }

    @Test
    public void initFirstOneEc2InstanceTest_list_add() {
        dah.doList = true;
        dah.condition = 2;
        dah.initFirstOneEc2Instance();

        assertThat(dah.getStoppedNodeCount(), is(0));
        assertThat(dah.getAddedNodeCount(), is(1));
        assertThat(dah.getTestIdEc2NodeStatusMap(MockDynamicAgentHandler.KEY_FOR_STARTUP).containsKey("192.168.1.3"), is(true));
        System.out.println("initFirstOneEc2InstanceTest_list_add is passed");
    }

    @Test
    public void turnOffEc2InstanceTest() {
        //first to list out current nodes
        dah.doList = true;
        dah.condition = 0;
        dah.initFirstOneEc2Instance();

        //turn off the running nodes
        dah.condition = 3;
        dah.turnOffEc2Instance();

        assertThat(dah.getStoppedNodeCount(), is(2));
        assertThat(dah.getAddedNodeCount(), is(2));
        System.out.println("turnOffEc2InstanceTest is passed");
    }

    @Test
    public void turnOnEc2InstanceTest() {
        //first to list out current nodes
        dah.doList = true;
        dah.condition = 0;
        dah.initFirstOneEc2Instance();

        //turn off the running nodes
        String testIdentifier = "PerfTest_1_user";
        dah.condition = 4;
        dah.turnOnEc2Instance(testIdentifier, 1);

        assertThat(dah.getStoppedNodeCount(), is(0));
        assertThat(dah.getAddedNodeCount(), is(2));
        assertThat(dah.getTestIdEc2NodeStatusMap(testIdentifier).containsKey("192.168.1.2"), is(true));
        System.out.println("turnOnEc2InstanceTest is passed");
    }

    @Test
    public void terminateEc2InstanceTest() {
        //first to list out current nodes
        dah.doList = true;
        dah.condition = 0;
        dah.initFirstOneEc2Instance();

        //term the specified nodes
        dah.condition = 5;
        List<String> termList = Lists.newArrayList();
        termList.add("201507070002");
        dah.terminateEc2Instance(termList);

        assertThat(dah.getStoppedNodeCount(), is(0));
        assertThat(dah.getAddedNodeCount(), is(1));
        assertThat(dah.getTestIdEc2NodeStatusMap(MockDynamicAgentHandler.KEY_FOR_STARTUP).containsKey("192.168.1.2"), is(false));
        System.out.println("terminateEc2InstanceTest is passed");
    }
}
