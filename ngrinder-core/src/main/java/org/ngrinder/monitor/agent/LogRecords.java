package org.ngrinder.monitor.agent;

import org.apache.commons.io.monitor.FileAlterationMonitor;

import java.io.File;

/**
 * record parameters of log operations.
 */
public class LogRecords {
    private long offset;
    private File current;
    private FileAlterationMonitor monitor;

    public long getOffset() {
        return this.offset;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }

    public File getCurrent() {
        return this.current;
    }

    public void setCurrent(File current) {
        this.current = current;
    }

    public FileAlterationMonitor getMonitor() {
        return this.monitor;
    }

    public void setMonitor(FileAlterationMonitor monitor) {
        this.monitor = monitor;
    }
}
