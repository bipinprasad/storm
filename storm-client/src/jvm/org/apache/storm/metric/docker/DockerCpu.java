package org.apache.storm.metric.docker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import org.apache.storm.container.cgroup.SubSystemType;
import org.apache.storm.container.cgroup.core.CgroupCore;
import org.apache.storm.container.cgroup.core.CpuacctCore;

public class DockerCpu extends DockerMetricsBase<Map<String, Long>> {
    long previousSystem = 0;
    long previousUser = 0;
    private int userHz = -1;

    public DockerCpu(Map<String, Object> conf) {
        super(conf, SubSystemType.cpuacct);
    }

    public synchronized int getUserHZ() throws IOException {
        if (userHz < 0) {
            ProcessBuilder pb = new ProcessBuilder("getconf", "CLK_TCK");
            Process p = pb.start();
            BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = in.readLine().trim();
            userHz = Integer.valueOf(line);
        }
        return userHz;
    }

    @Override
    public Map<String, Long> getDataFrom(CgroupCore core) throws IOException {
        CpuacctCore cpu = (CpuacctCore) core;
        Map<CpuacctCore.StatType, Long> stat = cpu.getCpuStat();
        long systemHz = stat.get(CpuacctCore.StatType.system);
        long userHz = stat.get(CpuacctCore.StatType.user);
        long user = userHz - previousUser;
        long sys = systemHz - previousSystem;
        previousUser = userHz;
        previousSystem = systemHz;
        long hz = getUserHZ();
        HashMap<String, Long> ret = new HashMap<>();
        ret.put("user-ms", user * 1000 / hz); //Convert to millis
        ret.put("sys-ms", sys * 1000 / hz);
        return ret;
    }
}
