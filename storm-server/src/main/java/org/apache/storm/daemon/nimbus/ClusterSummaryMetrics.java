package org.apache.storm.daemon.nimbus;

import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import java.util.HashMap;
import java.util.Map;

class ClusterSummaryMetrics implements MetricSet {
    private static final String SUMMARY = "summary";
    private final Map<String, Metric> metrics = new HashMap<>();

    public com.codahale.metrics.Metric put(String key, com.codahale.metrics.Metric value) {
        return metrics.put(MetricRegistry.name(SUMMARY, key), value);
    }

    @Override
    public Map<String, com.codahale.metrics.Metric> getMetrics() {
        return metrics;
    }
}

