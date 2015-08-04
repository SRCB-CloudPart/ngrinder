package org.ngrinder.agent.service.autoscale;

/**
 * Created by junoyoon on 15. 8. 4.
 */
public class AutoScaleNode {
    private long lastExecuted;
    private long created;
    private String machineId;


    public AutoScaleNode(String machineId, long created) {
        this.machineId = machineId;
        this.created = created;
    }

    public String getMachineId() {
        return machineId;
    }

    public void setMachineId(String machineId) {
        this.machineId = machineId;
    }

    public long getCreated() {
        return created;
    }

    public void setCreated(long created) {
        this.created = created;
    }

    public long getLastExecuted() {
        return lastExecuted;
    }

    public void touch() {
        this.lastExecuted = System.currentTimeMillis();
    }
}
