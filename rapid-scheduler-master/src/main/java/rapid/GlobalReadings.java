package rapid;

import java.util.HashMap;

public class GlobalReadings {
    private long glid;
    private int activevmms;
    private float allocatedcpu;
    private int powerusagesum;
    private float powerusageavg;

    public int getActivevmms() {
        return activevmms;
    }

    public void setActivevmms(int activevmms) {
        this.activevmms = activevmms;
    }

    public long getGlid() {
        return glid;
    }

    public void setGlid(long glid) {
        this.glid = glid;
    }

    public float getAllocatedcpu() {
        return allocatedcpu;
    }

    public void setAllocatedcpu(float allocatedcpu) {
        this.allocatedcpu = allocatedcpu;
    }

    public int getPowerusagesum() {
        return powerusagesum;
    }

    public void setPowerusagesum(int powerusagesum) {
        this.powerusagesum = powerusagesum;
    }

    public float getPowerusageavg() {
        return powerusageavg;
    }

    public void setPowerusageavg(float powerusageavg) {
        this.powerusageavg = powerusageavg;
    }
}
