package org.apache.storm.metric.docker;

import java.io.IOException;
import java.util.Map;
import org.apache.storm.container.cgroup.SubSystemType;
import org.apache.storm.container.cgroup.core.CgroupCore;
import org.apache.storm.container.cgroup.core.CpuCore;

public class DockerCpuGuarantee extends DockerMetricsBase<Long> {

    long previousTime = -1;

    public DockerCpuGuarantee(Map<String, Object> conf) {
        super(conf, SubSystemType.cpu);
    }

    @Override
    public Long getDataFrom(CgroupCore core) throws IOException {
        CpuCore cpu = (CpuCore) core;
        Long msGuarantee = null;
        long now = System.currentTimeMillis();
        if (previousTime > 0) {
            long shares = cpu.getCpuShares();
            //By convention each share corresponds to 1% of a CPU core
            // or 100 = 1 core full time. So the guaranteed number of ms
            // (approximately) should be ...
            msGuarantee = (shares * (now - previousTime)) / 100;
        }
        previousTime = now;
        return msGuarantee;
    }
}
