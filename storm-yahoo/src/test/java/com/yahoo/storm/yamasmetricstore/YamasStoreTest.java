package com.yahoo.storm.yamasmetricstore;

import java.util.HashMap;
import java.util.Map;
import org.apache.storm.metric.StormMetricsRegistry;
import org.apache.storm.metricstore.MetricException;
import org.junit.Test;

public class YamasStoreTest {

    /**
     * Creates Yamas Store using config options valid for testing on a dev grid box.
     *
     * @throws MetricException on preparation error
     */
    @Test
    public void sampleYamasStore() throws MetricException {
        Map<String, Object> config = new HashMap<>();
        config.put(YamasStore.YAMAS_STORE_STORM_CLUSTER_CONFIG, "unitTestCluster1");
        config.put(YamasStore.YAMAS_STORE_NAMESPACE_CONFIG, "TEST-Ystorm-integration");
        config.put(YamasStore.YAMAS_STORE_PROXY_CONFIG, "httpproxy-res.blue.ygrid.yahoo.com");
        config.put(YamasStore.YAMAS_STORE_BOUNCER_USER_CONFIG, "hadoop_re");
        YamasStore store = new YamasStore();
        store.prepare(config, new StormMetricsRegistry());
    }
}
