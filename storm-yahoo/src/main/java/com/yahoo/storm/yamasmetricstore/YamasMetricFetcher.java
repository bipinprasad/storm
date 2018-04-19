package com.yahoo.storm.yamasmetricstore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.message.BasicHeader;
import org.apache.storm.metricstore.AggLevel;
import org.apache.storm.metricstore.Metric;
import org.apache.storm.metricstore.MetricException;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yjava.byauth.jaas.HttpClientBouncerAuth;

class YamasMetricFetcher {
    private static final Logger LOG = LoggerFactory.getLogger(YamasMetricFetcher.class);
    private static long nextBouncerLogin = 0L;
    private static Header YBYCookieHeader = null;

    static List<Metric> getMetrics(JSONObject query, AggLevel aggLevel)
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

    private static List<Metric> parseQueryResult(String result, AggLevel aggLevel) throws MetricException {
        JSONParser parser = new JSONParser();
        JSONArray metrics;
        try {
            metrics = (JSONArray) parser.parse(result);
        } catch (Exception e) {
            throw new MetricException("Failed to parse query result: " + result, e);
        }

        List<Metric> metricList = new ArrayList<>(metrics.size());
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

    private static List<Metric> parseMetricJson(JSONObject metricJson, AggLevel aggLevel) throws MetricException {
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
            Double metricValue = null;
            if (val instanceof Long) {
                metricValue = ((Long)val).doubleValue();
            } else if (val instanceof Double) {
                metricValue = (Double)val;
            }
            if (metricValue != null) {
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

    private static String postQuery(JSONObject query) throws MetricException {
        HttpPost postRequest = new HttpPost(YamasStore.yamasQueryUrl);
        DefaultProxyRoutePlanner routePlanner = new DefaultProxyRoutePlanner(YamasStore.httpProxy);
        CloseableHttpClient httpClient = HttpClients.custom().setRoutePlanner(routePlanner).build();
        StringEntity input;
        HttpResponse response;
        String output = "";
        try {
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

            BufferedReader br;
            try {
                br = new BufferedReader(new InputStreamReader((response.getEntity().getContent())));
                String line;
                while ((line = br.readLine()) != null) {
                    output += line;
                }
            } catch (IOException e) {
                throw new MetricException("Failed to read response", e);
            }

            if (response.getStatusLine().getStatusCode() != 200) {
                throw new MetricException("Failed to query for metrics " + query.toString() + ", status code: "
                        + response.getStatusLine().getStatusCode());
            }
        } finally {
            try {
                httpClient.close();
            } catch (IOException e) {
                LOG.error("Failed to close httpClient", e);
            }
        }

        return output;
    }

    private static synchronized Header getYbyCookieHeader() throws MetricException {
        if (System.currentTimeMillis() > nextBouncerLogin) {
            String password = getBouncerPassword(YamasStore.bouncerUser);
            HttpClientBouncerAuth localHttpClientBouncerAuth = new HttpClientBouncerAuth();
            String ybyCookie;
            try {
                ybyCookie = localHttpClientBouncerAuth.authenticate2(YamasStore.bouncerLoginUrl, YamasStore.bouncerUser,
                        password.toCharArray(), true);
            } catch (Exception e) {
                throw new MetricException("Failed to get YBYCookie", e);
            }
            YBYCookieHeader = new BasicHeader("Cookie", ybyCookie);
            nextBouncerLogin = System.currentTimeMillis() + 3600L * 1000L;
        }
        return YBYCookieHeader;
    }

    private static String getBouncerPassword(String user) throws MetricException {
        String[] args = new String[]{"ykeykeygetkey", user};
        ProcessBuilder pb = new ProcessBuilder(args);
        String output;
        Process proc;
        int rc;
        try {
            proc = pb.start();
            output = getStream(proc.getInputStream());
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

    private static String getStream(InputStream is) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }
}
