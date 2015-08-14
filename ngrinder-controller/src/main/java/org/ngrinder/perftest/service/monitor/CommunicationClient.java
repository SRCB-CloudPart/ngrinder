package org.ngrinder.perftest.service.monitor;

import org.ngrinder.infra.config.Config;
import org.ngrinder.model.PerfTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

/**
 * Communication with monitor node.
 */
public class CommunicationClient {
    private static final Logger LOG = LoggerFactory.getLogger(CommunicationClient.class);
    private Config config;
    private PerfTest perfTest;
    private Socket socket = null;
    private FileOutputStream locationLogStream;

    public CommunicationClient() {
    }

    /**
     * initialization.
     * @param config
     * @param perfTest
     */
    public void init(Config config, PerfTest perfTest) {
        setConfig(config);
        setPerfTest(perfTest);
    }

    public Config getConfig() {
        return this.config;
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    public PerfTest getPerfTest() {
        return perfTest;
    }

    public void setPerfTest(PerfTest perfTest) {
        this.perfTest = perfTest;
    }

    private InputStream getInputStream() throws IOException {
        return socket.getInputStream();
    }

    private OutputStream getOutputStream() throws IOException {
        return socket.getOutputStream();
    }

    /**
     * Connect to monitor server.
     */
    private void connectToMonitor() {
        LOG.info("connecting to monitor server...");
        String host = getPerfTest().getTargetHosts();
        int port = getConfig().getMonitorMessagePort();

        while (true) {
            try {
                this.socket = new Socket(host, port);
                if (socket != null) {
                    break;
                }
            } catch (IOException e) {
                try {
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException e1) {
                    LOG.error("connect to monitor failure. Exception: " + e1);
                    e1.printStackTrace();
                    break;
                }
            }
        }
        LOG.info("connected to monitor server.");
    }

    /**
     * open log file.
     */
    public void openStreamOfLog() {
        try {
            locationLogStream = new FileOutputStream(new File(config.getHome().getPerfTestLogDirectory(getPerfTest()), "monitor.log.zip"));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    /**
     * Notify monitor perftest start.
     * @param perfTest
     */
    public void startMonitor(PerfTest perfTest) {
        LOG.info("send to monitor perftest start.");
        if(socket == null) {
            connectToMonitor();
        }
        OutputStream out = null;
        try {
            // send message.
            out = getOutputStream();
            out.write((MonitorMessage.START_TEST + "-" + perfTest.getId().toString()).getBytes());
        } catch (IOException e) {
            try {
                TimeUnit.SECONDS.sleep(5);
                startMonitor(perfTest);
            } catch (InterruptedException e1) {
                LOG.error("send start message failure.");
                e1.printStackTrace();
            }
        } finally {
            // close the resource.
            try {
                out.close();
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            socket = null;
        }
    }

    /**
     * Notify monitor perftest stop.
     * @param perfTest
     */
    public void stopMonitor(PerfTest perfTest) {
        LOG.info("send to monitor perftest stop.");
        if(socket == null) {
            connectToMonitor();
        }
        OutputStream out = null;
        try {
            // send message.
            out = getOutputStream();
            out.write((MonitorMessage.STOP_TEST + "-" + perfTest.getId().toString()).getBytes());
        } catch (IOException e) {
            try {
                TimeUnit.SECONDS.sleep(5);
                stopMonitor(perfTest);
            } catch (InterruptedException e1) {
                LOG.error("send stop message failure.");
                e1.printStackTrace();
            }
        }
        // receive logs from monitor
        openStreamOfLog();
        receiveFile();
    }

    /**
     * receive logs from monitor.
     */
    public void receiveFile() {
        LOG.info("receive logs from monitor.");

        byte[] bytes = new byte[1024 * 8];
        int num;
        try {
            while((num = socket.getInputStream().read(bytes)) != -1) {
                locationLogStream.write(bytes, 0, num);
            }
            locationLogStream.close();
        }catch (Exception e) {
            LOG.error("receive logs failure.");
        }
    }
}
