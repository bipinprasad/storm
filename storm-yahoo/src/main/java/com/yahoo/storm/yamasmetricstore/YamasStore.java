package com.yahoo.storm.yamasmetricstore;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.storm.metricstore.AggLevel;
import org.apache.storm.metricstore.FilterOptions;
import org.apache.storm.metricstore.Metric;
import org.apache.storm.metricstore.MetricException;
import org.apache.storm.metricstore.MetricStore;
import org.apache.storm.utils.ObjectReader;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YamasStore implements MetricStore, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(YamasStore.class);

    /* required config options */
    // the cluster maps to the Yamas application.
    public static final String YAMAS_STORE_STORM_CLUSTER_CONFIG = "yamas.store.storm.cluster";
    public static final String YAMAS_STORE_NAMESPACE_CONFIG = "yamas.store.namespace";
    public static final String YAMAS_STORE_PROXY_CONFIG = "yamas.store.http.proxy";
    public static final String YAMAS_STORE_BOUNCER_USER_CONFIG = "yamas.store.bouncer.user";

    /* optional config options */
    public static final String YAMAS_STORE_INSERTION_URL_CONFIG = "yamas.store.insert.url";
    public static final String YAMAS_STORE_QUERY_URL_CONFIG = "yamas.store.query.url";
    public static final String YAMAS_STORE_PROXY_PORT_CONFIG = "yamas.store.http.proxy.port";
    public static final String YAMAS_STORE_BOUNCER_URL_CONFIG = "yamas.store.bouncer.url";

    static String stormCluster = null;
    private static String yamasInsertUrl = null;
    static String namespace = null;
    static String yamasQueryUrl = null;
    static String bouncerUser = null;
    static String bouncerLoginUrl = null;
    static HttpHost httpProxy = null;

    /**
     * Create metric store instance using the configurations provided via the config map.
     *
     * @param config Storm config map
     * @throws MetricException on preparation error
     */
    @Override
    public void prepare(Map<String, Object> config) throws MetricException {
        stormCluster = ObjectReader.getString(config.get(YAMAS_STORE_STORM_CLUSTER_CONFIG));
        if (stormCluster == null) {
            throw new MetricException(YAMAS_STORE_STORM_CLUSTER_CONFIG + " configuration is not set");
        }

        yamasInsertUrl = ObjectReader.getString(config.get(YAMAS_STORE_INSERTION_URL_CONFIG), "http://collector.yms.ops.yahoo.com:4080/yms/V2/sendMessage?namespace=");

        namespace = ObjectReader.getString(config.get(YAMAS_STORE_NAMESPACE_CONFIG));
        if (namespace == null) {
            throw new MetricException(YAMAS_STORE_NAMESPACE_CONFIG + " configuration is not set");
        }
        yamasInsertUrl += namespace;

        yamasQueryUrl = ObjectReader.getString(config.get(YAMAS_STORE_QUERY_URL_CONFIG), "https://yamas.ops.yahoo.com:4443/api/query");

        String proxyHost = ObjectReader.getString(config.get(YAMAS_STORE_PROXY_CONFIG));
        if (proxyHost == null) {
            throw new MetricException(YAMAS_STORE_PROXY_CONFIG + " configuration is not set");
        }
        int proxyPort = ObjectReader.getInt(config.get(YAMAS_STORE_PROXY_PORT_CONFIG), 4080);
        httpProxy = new HttpHost(proxyHost, proxyPort);

        bouncerUser = ObjectReader.getString(config.get(YAMAS_STORE_BOUNCER_USER_CONFIG));
        if (bouncerUser == null) {
            throw new MetricException(YAMAS_STORE_BOUNCER_USER_CONFIG + " configuration is not set");
        }

        bouncerLoginUrl = ObjectReader.getString(config.get(YAMAS_STORE_BOUNCER_URL_CONFIG), "https://gh.bouncer.login.yahoo.com/login/");
    }

    /**
     * Stores a metric in the store.
     *
     * @param metric Metric to store
     * @throws MetricException on error
     */
    @Override
    public void insert(Metric metric) throws MetricException {
        JSONObject metricJson = getMetricJson(metric);
        postMetric(metricJson);
    }

    private JSONObject getMetricJson(Metric metric) {
        JSONObject obj = new JSONObject();
        obj.put("application", stormCluster);
        long timestamp = metric.getTimestamp();
        obj.put("timestamp", new Long(timestamp / 1000L).toString());

        JSONObject dimensions = new JSONObject();
        dimensions.put("topologyId", metric.getTopologyId());
        dimensions.put("componentId", metric.getComponentId());
        dimensions.put("executorId", metric.getExecutorId());
        dimensions.put("hostname", metric.getHostname());
        dimensions.put("streamId", metric.getStreamId());
        dimensions.put("port", metric.getPort());
        obj.put("dimensions", dimensions);

        JSONObject metrics = new JSONObject();
        metrics.put(metric.getMetricName(), metric.getValue());
        obj.put("metrics", metrics);

        return obj;
    }

    private void postMetric(JSONObject metricJson) throws MetricException {
        HttpPost postRequest = new HttpPost(yamasInsertUrl);
        StringEntity input;
        try {
            input = new StringEntity(metricJson.toString());
        } catch (UnsupportedEncodingException e) {
            throw new MetricException("Failed to post metric " + metricJson.toString(), e);
        }
        input.setContentType("application/json");
        postRequest.setEntity(input);

        HttpResponse response;
        CloseableHttpClient httpClient = HttpClients.createDefault();
        try {
            response = httpClient.execute(postRequest);
        } catch (IOException e) {
            throw new MetricException("Failed to execute post for " + metricJson.toString(), e);
        } finally {
            try {
                httpClient.close();
            } catch (IOException e) {
                LOG.error("Failed to close " + httpClient, e);
            }
        }

        if (response.getStatusLine().getStatusCode() != 200) {
            throw new MetricException("Failed inserting metric " + metricJson.toString() + ", HTTP error code : "
                    + response.getStatusLine().getStatusCode());
        }
    }

    /**
     * Fill out the numeric values for a metric.
     *
     * @param metric Metric to populate
     * @return true if the metric was populated, false otherwise
     * @throws MetricException on error
     */
    @Override
    public boolean populateValue(Metric metric) throws MetricException {
        JSONObject query = YamasQueryBuilder.createQuery(metric);
        List<Metric> metrics = YamasMetricFetcher.getMetrics(query, metric.getAggLevel());
        if (metrics.isEmpty() || metrics.size() > 1) {
            return false;
        }
        metric.setValue(metrics.get(0).getValue());
        return true;
    }

    /**
     * Close the metric store.
     */
    @Override
    public void close() {
    }

    /**
     * Scans all metrics in the store and returns the ones matching the specified filtering options.
     *
     * @param filter       options to filter by
     * @param scanCallback callback for each Metric found
     * @throws MetricException on error
     */
    @Override
    public void scan(FilterOptions filter, ScanCallback scanCallback) throws MetricException {
        Map<AggLevel, JSONObject> queryMap = YamasQueryBuilder.createQueries(filter);
        for (Map.Entry<AggLevel, JSONObject> entry : queryMap.entrySet()) {
            AggLevel aggLevel = entry.getKey();
            JSONObject query = entry.getValue();
            List<Metric> metrics = YamasMetricFetcher.getMetrics(query, aggLevel);
            for (Metric m : metrics) {
                scanCallback.cb(m);
            }
        }
    }
}
