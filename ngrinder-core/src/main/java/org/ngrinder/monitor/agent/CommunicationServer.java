package org.ngrinder.monitor.agent;

import org.ngrinder.infra.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This class is responsible for communication with controller.
 * Receive start or stop signal and send monitor logs of a specified perferftest
 * to controller.
 */
public class CommunicationServer {
    private static final Logger LOG = LoggerFactory.getLogger(CommunicationServer.class);
    private static CommunicationServer instance = null;
    private AgentConfig agentConfig;
    private Socket socket = null;
    private ServerSocket serverSocket = null;
    private RandomAccessFile raf = null;
    private ExecutorService execute = null;
    private Map<String, Long> map = new HashMap<String, Long>();

    private CommunicationServer() {
        this.execute = Executors.newFixedThreadPool(10);
    }

    /**
     * get a singleton instance.
     */
    public static CommunicationServer getInstance() {
        if(instance == null) {
            instance = new CommunicationServer();
        }
        return instance;
    }

    public AgentConfig getAgentConfig() {
        return this.agentConfig;
    }

    public void setAgentConfig(AgentConfig agentConfig) {
        this.agentConfig = agentConfig;
    }

    /**
     * Communication server for receive message from controller before perftest starting
     * or after perftest stoped.
     */
    public void bind() {
        LOG.info("binding port on monitor...");
        if (serverSocket == null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        int port = agentConfig.getMessagePort();
                        serverSocket = new ServerSocket(port);
                        while (true) {
                            LOG.info("listening to port: " + port);
                            socket = serverSocket.accept();
                            execute.submit(new Runnable() {
                                @Override
                                public void run() {
                                    operations();
                                }
                            });
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }).start();
        }
        LOG.info("binding successful.");
    }

    /**
     * open log file on monitor node.
     */
    public void openStreamOfMonitorLog() {
        try {
            raf = new RandomAccessFile(new File(agentConfig.getHome().getLogDirectory(), "agent.log"), "r");
        } catch (FileNotFoundException e) {
            LOG.error("monitor log is not exist.");
            e.printStackTrace();
        }
    }

    /**
     * receive message from controller, then do below operation.
     */
    public void operations() {
        // receive message.
        String[] message = getMessage();

        // perftest start operation
        if(message[0].equals("start_test")) {
            startMessageOp(message);
        }

        // perftest stop operation
        if(message[0].equals("stop_test")) {
            stopMessageOp(message);
        }
    }

    /**
     * parse the message received from controller.
     * @return message. received from controller.
     */
    private String[] getMessage() {
        LOG.info("parseing message...");
        byte[] bytes;
        try {
            // get message.
            bytes = new byte[1024];
            socket.getInputStream().read(bytes);
            // parsing.
            String[] message = new String(bytes, "UTF-8").trim().split("-");
            return message;
        }catch (Exception e) {
            LOG.error("message format is error.");
            e.printStackTrace();
            return null;
        }
    }

    /**
     * operation at perftest starting.
     * @param message start signal message.
     */
    private void startMessageOp(String[] message) {
        LOG.info("perftest starting operation...");
        Long offset = null;
        // whether log is open.
        if(raf == null) {
            openStreamOfMonitorLog();
        }
        // record the offset of the perftest.
        try {
            offset = raf.length();
            map.put(message[1].trim(), offset);
            LOG.info("the perftest " + message[1].trim() + ": offset is started at " + offset);
        } catch (IOException e) {
            LOG.error("Log is not open.");
            e.printStackTrace();
        }
    }

    /**
     * operation after perftest stop.
     * @param message stop signal message.
     */
    private void stopMessageOp(String[] message) {
        LOG.info("perftest stopped operation");
        Long end = null;
        if(raf == null) {
            openStreamOfMonitorLog();
        }
        try {
            // log offset when this perftest has stopped.
            end = raf.length();
            LOG.info("the perftest " + message[1].trim() + ": offset is closed at " + end);
            // send logs.
            if(map.containsKey(message[1].trim())) {
                Long start = map.get(message[1].trim());
                tranportLogs(start, end);
                map.remove(message[1].trim());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void tranportLogs(Long start, Long end) throws IOException {
        LOG.info("sending logs of the perftest.");
        Long head = start;
        Long tail = end;

        // sending logs
        raf.seek(head);
        while (head < tail) {
            String line = raf.readLine();
            socket.getOutputStream().write(line.getBytes());
            socket.getOutputStream().flush();
            head = raf.getFilePointer();
        }
    }
}