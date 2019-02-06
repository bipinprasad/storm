package org.apache.storm.metric.docker;

import java.util.Map;
import org.apache.storm.container.cgroup.SubSystemType;
import org.apache.storm.container.cgroup.core.CgroupCore;
import org.apache.storm.container.cgroup.core.MemoryCore;
import org.apache.storm.metric.cgroup.CGroupMemoryLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DockerMemoryLimit extends DockerMetricsBase<Long> {
    private static final Logger LOG = LoggerFactory.getLogger(CGroupMemoryLimit.class);
    private static final long BYTES_PER_MB = 1024 * 1024;
    private final long workerLimitBytes;

    /**
     * The constructor.
     * @param conf storm conf
     */
    public DockerMemoryLimit(Map<String, Object> conf) {
        super(conf, SubSystemType.memory);
        //In some cases we might be limiting memory in the supervisor and not in the cgroups
        long limit = -1;
        try {
            limit = Long.valueOf(System.getProperty("worker.memory_limit_mb", "-1"));
        } catch (NumberFormatException e) {
            LOG.warn("Error Parsing worker.memory_limit_mb {}", e);
        }
        workerLimitBytes = BYTES_PER_MB * limit;
    }

    @Override
    public Long getDataFrom(CgroupCore core) throws Exception {
        if (workerLimitBytes > 0) {
            return workerLimitBytes;
        }
        return ((MemoryCore) core).getPhysicalUsageLimit();
    }
}
