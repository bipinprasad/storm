package com.yahoo.storm.yamasmetricstore;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.message.BasicHeader;
import org.apache.storm.metricstore.AggLevel;
import org.apache.storm.metricstore.FilterOptions;
import org.apache.storm.metricstore.Metric;
import org.apache.storm.metricstore.MetricException;
import org.apache.storm.metricstore.MetricStore;
import org.apache.storm.utils.ObjectReader;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yjava.byauth.jaas.HttpClientBouncerAuth;

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
    private static String yamasQueryUrl = null;
    private static String bouncerUser = null;
    static String bouncerLoginUrl = null;
    private static HttpHost httpProxy = null;
    private long nextBouncerLogin = 0L;
    private Header YBYCookieHeader = null;

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
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            response = httpClient.execute(postRequest);
        } catch (IOException e) {
            throw new MetricException("Failed to execute post for " + metricJson.toString(), e);
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
        List<Metric> metrics = this.getMetrics(query, metric.getAggLevel());
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
            List<Metric> metrics = this.getMetrics(query, aggLevel);
            for (Metric m : metrics) {
                scanCallback.cb(m);
            }
        }
    }

    private List<Metric> getMetrics(JSONObject query, AggLevel aggLevel)
            throws MetricException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("metric query: " + query.toString());
        }
        String result = postQuery(query);
        if (LOG.isDebugEnabled()) {
            LOG.debug("metric result: " + result);
        }
        return parseQueryResult(result, aggLevel);
    }

    private String postQuery(JSONObject query) throws MetricException {
        HttpPost postRequest = new HttpPost(yamasQueryUrl);
        DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(httpProxy);
        String output;
        try (CloseableHttpClient httpClient = HttpClients.custom().setRoutePlanner(routePlanner).build()) {
            StringEntity input;
            HttpResponse response;
            try {
                input = new StringEntity(query.toString());
            } catch (UnsupportedEncodingException e) {
                throw new MetricException("Unable to post metric query", e);
            }
            input.setContentType("application/json");
            postRequest.setEntity(input);
            postRequest.addHeader(getYbyCookieHeader());

            try {
                response = httpClient.execute(postRequest);
            } catch (IOException e) {
                throw new MetricException("Failed to get response", e);
            }

            if (response.getStatusLine().getStatusCode() != 200) {
                throw new MetricException("Failed to query for metrics " + query.toString() + ", status code: "
                        + response.getStatusLine().getStatusCode());
            }

            try {
                output = IOUtils.toString(response.getEntity().getContent(), Charset.defaultCharset());
            } catch (IOException e) {
                throw new MetricException("Failed to read response", e);
            }
        } catch (IOException e) {
            throw new MetricException("Failed to close client", e);
        }

        return output;
    }

    private Header getYbyCookieHeader() throws MetricException {
        if (System.currentTimeMillis() > nextBouncerLogin) {
            String password = getBouncerPassword(bouncerUser);
            HttpClientBouncerAuth localHttpClientBouncerAuth = new HttpClientBouncerAuth();
            String ybyCookie;
            try {
                ybyCookie = localHttpClientBouncerAuth.authenticate2(bouncerLoginUrl, bouncerUser,
                        password.toCharArray(), true);
            } catch (Exception e) {
                throw new MetricException("Failed to get YBYCookie", e);
            }
            YBYCookieHeader = new BasicHeader("Cookie", ybyCookie);
            nextBouncerLogin = System.currentTimeMillis() + 3600L * 1000L;
        }
        return YBYCookieHeader;
    }

    private String getBouncerPassword(String user) throws MetricException {
        String[] args = new String[]{"ykeykeygetkey", user};
        ProcessBuilder pb = new ProcessBuilder(args);
        String output;
        Process proc;
        int rc;
        try {
            proc = pb.start();
            output = IOUtils.toString(proc.getInputStream(), Charset.defaultCharset());
            rc = proc.waitFor();
        } catch (Exception e) {
            throw new MetricException("Failed to login to Bouncer", e);
        }
        if (rc != 0) {
            throw new MetricException("Failed to login to Bouncer");
        }
        proc.destroy();
        return output.trim();
    }

    private List<Metric> parseQueryResult(String result, AggLevel aggLevel) throws MetricException {
        JSONParser parser = new JSONParser();
        JSONArray metrics;

        List<Metric> metricList = new ArrayList<>();

        try {
            metrics = (JSONArray) parser.parse(result);
        } catch (Exception e) {
            throw new MetricException("Failed to parse query result: " + result, e);
        }

        if (metrics == null) {
            return metricList;
        }

        for (Object o : metrics) {
            JSONObject metricJson;
            if (o instanceof JSONObject) {
                metricJson = (JSONObject)o;
            } else {
                throw new MetricException("Failed to parse metrics from: " + result);
            }
            List<Metric> parseResults = parseMetricJson(metricJson, aggLevel);
            metricList.addAll(parseResults);
        }
        return metricList;
    }

    private List<Metric> parseMetricJson(JSONObject metricJson, AggLevel aggLevel) throws MetricException {
        List<Metric> metricList = new ArrayList<>();
        String metricName = (String)metricJson.get("metric");
        JSONObject dps = (JSONObject)metricJson.get("dps");

        JSONObject tags = (JSONObject)metricJson.get("tags");
        String topologyId = "";
        String componentId = "";
        String executorId = "";
        String hostname = "";
        String streamId = "";
        int port = 0;

        for (Iterator iterator = tags.keySet().iterator(); iterator.hasNext();) {
            String key = (String) iterator.next();
            Object value = tags.get(key);
            if (value instanceof String) {
                String val = (String)value;
                if (key.equals("topologyId")) {
                    topologyId = val;
                } else if (key.equals("componentId")) {
                    componentId = val;
                } else if (key.equals("executorId")) {
                    executorId = val;
                } else if (key.equals("hostname")) {
                    hostname = val;
                } else if (key.equals("streamId")) {
                    streamId = val;
                } else if (key.equals("port")) {
                    port = Integer.parseInt(val);
                }
            }
        }

        for (Iterator iterator = dps.keySet().iterator(); iterator.hasNext();) {
            String timestampString = (String)iterator.next();
            Object val = dps.get(timestampString);

            if (val != null) {
                double metricValue = ((Number)val).doubleValue();
                long timestamp = Long.parseLong(timestampString) *  1000L;
                Metric m = new Metric(metricName, timestamp, topologyId, metricValue, componentId, executorId,
                        hostname, streamId, port, aggLevel);
                m.setValue(metricValue);

                if (LOG.isDebugEnabled()) {
                    LOG.debug("Created metric " + m);
                }
                metricList.add(m);
            }
        }
        return metricList;
    }
}
