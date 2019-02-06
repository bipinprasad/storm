package org.apache.storm.metric.docker;

import java.util.Map;
import org.apache.storm.container.cgroup.SubSystemType;
import org.apache.storm.container.cgroup.core.CgroupCore;
import org.apache.storm.container.cgroup.core.MemoryCore;

public class DockerMemoryUsage  extends DockerMetricsBase<Long> {

    public DockerMemoryUsage(Map<String, Object> conf) {
        super(conf, SubSystemType.memory);
    }

    @Override
    public Long getDataFrom(CgroupCore core) throws Exception {
        return ((MemoryCore) core).getPhysicalUsage();
    }
}
