package org.ngrinder.monitor.agent;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.ngrinder.infra.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * This class is responsible for communication with controller.
 * Receive start or stop signal and send monitor logs of a specified perferftest
 * to controller.
 */
public class CommunicationServer {
    private static final Logger LOG = LoggerFactory.getLogger(CommunicationServer.class);
    private static final int COMPRESS_BUFFER_SIZE = 8 * 1024;
    private static CommunicationServer instance = null;
    private AgentConfig agentConfig;
    private Socket socket = null;
    private ServerSocket serverSocket = null;
    private ExecutorService execute = null;
    private Map<String, LogRecords> map = new ConcurrentHashMap<String, LogRecords>();
    private List<File> list = new ArrayList<File>();

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

    private void createMonitorForLogDirectory(final String perftestId) {
        // get monitor directory.
        File directory = new File(agentConfig.getMonitorServerLog()).getParentFile();
        if(!directory.exists()) {
            directory.mkdirs();
        }

        // create monitor for log directory.
        long interval = TimeUnit.SECONDS.toMillis(1);
        FileAlterationObserver observer = new FileAlterationObserver(directory);
        observer.addListener(new FileAlterationListenerAdaptor(){
            /**
             * listening file create.
             * @param file created file.
             */
            @Override
            public void onFileCreate(File file) {
                LOG.info("App creates a new log file. named: {}", file.getName());
                list.add(file);
            }

            /**
             * listening file modify.
             * @param file modified file.
             */
            @Override
            public void onFileChange(File file) {
                LOG.info("logs is adding in {}", file.getName());
                if((list == null) || (list.size() == 0)) {
                    LogRecords records = new LogRecords();
                    records.setCurrent(file);
                    records.setOffset(file.length());
                    map.put(perftestId, records);
                }
            }

            /**
             * listening delete file.
             * @param file deleted file.
             */
            @Override
            public void onFileDelete(File file) {
                LOG.info("a log file has been deleted. the file is {}", file.getName());
            }
        });

        FileAlterationMonitor monitor = new FileAlterationMonitor(interval, observer);
        try {
            monitor.start();
            LogRecords records = map.get(perftestId);
            records.setMonitor(monitor);
            map.put(perftestId, records);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * operation at perftest starting.
     * @param message start signal message.
     */
    private void startMessageOp(String[] message) {
        LOG.info("perftest starting operation...");
        // open monitor for log.
        createMonitorForLogDirectory(message[1].trim());
    }

    /**
     * operation after perftest stop.
     * @param message stop signal message.
     */
    private void stopMessageOp(String[] message) {
        LOG.info("perftest stopped operation");
        Long end = null;
        LogRecords records = map.get(message[1].trim());
        try {
            records.getMonitor().stop();
        } catch (Exception e) {
            e.printStackTrace();
        }
        if((list == null) || (list.size() == 0)) {
            end = records.getCurrent().length();
        }
        else {
            end = list.get(list.size() - 1).length();
        }

        // send log and compress the stream.
        ByteArrayOutputStream out = null;
        ZipOutputStream zos = null;
        try {
            out = new ByteArrayOutputStream();
            zos = new ZipOutputStream(out);
            sendlog(records.getOffset(), -1, records.getCurrent(), zos);
            if((list !=null) && (list.size() != 0)) {
                if(list.size() > 1) {
                    for(int i = 0; i < list.size() - 1; i++) {
                        sendlog(0, -1, list.get(i), zos);
                    }
                }

                sendlog(0, end, list.get(list.size() - 1), zos);
            }

            zos.finish();
            zos.flush();
        }catch (IOException e) {
            LOG.error("Error occurs while compressing log : {} ", e.getMessage());
            LOG.debug("Details : ", e);
        }finally {
            IOUtils.closeQuietly(zos);
            IOUtils.closeQuietly(out);
        }
    }

    private void sendlog(long start, long end, File file, ZipOutputStream zos) {
        try{
            ZipEntry zipEntry = new ZipEntry(file.getName());
            zipEntry.setTime(file.lastModified());
            zos.putNextEntry(zipEntry);
            byte[] buffer = new byte[COMPRESS_BUFFER_SIZE];
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            int count;
            raf.seek(start);
            while (((count = raf.read(buffer, 0, COMPRESS_BUFFER_SIZE)) != -1) || (raf.getFilePointer() <= end)) {
                zos.write(buffer, 0, count);
            }
            zos.flush();
            zos.closeEntry();
        }catch (IOException e) {
            LOG.error("Error occurs while compressing {} : {}", file.getAbsolutePath(), e.getMessage());
            LOG.debug("Details ", e);
        }
    }
}