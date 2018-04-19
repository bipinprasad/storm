package com.yahoo.storm.yamasmetricstore;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.storm.metricstore.AggLevel;
import org.apache.storm.metricstore.FilterOptions;
import org.apache.storm.metricstore.Metric;
import org.apache.storm.metricstore.MetricException;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

class YamasQueryBuilder {

    static Map<AggLevel, JSONObject> createQueries(FilterOptions filterOptions)
            throws MetricException {
        if (filterOptions.getMetricName() == null) {
            throw new MetricException("Unable to scan Yamas without a metric name specified");
        }

        String topologyId = filterOptions.getTopologyId() == null ? "*" : filterOptions.getTopologyId();
        String componentId = filterOptions.getComponentId() == null ? "*" : filterOptions.getComponentId();
        String executorId = filterOptions.getExecutorId() == null ? "*" : filterOptions.getExecutorId();
        String hostname = filterOptions.getHostId() == null ? "*" : filterOptions.getHostId();
        Integer port = filterOptions.getPort() == null ? 0 : filterOptions.getPort();
        String streamId = filterOptions.getStreamId() == null ? "*" : filterOptions.getStreamId();

        return createQueries(filterOptions.getMetricName(), filterOptions.getStartTime(), filterOptions.getEndTime(),
                topologyId, componentId, executorId, hostname, port, streamId, filterOptions.getAggLevels());
    }

    private static Map<AggLevel, JSONObject> createQueries(String metricName, long startTime,
                                                           Long endTime, String topologyId, String componentId, String executorId,
                                                           String hostName, Integer port, String streamId, Set<AggLevel> aggLevels)
            throws MetricException {

        Map<AggLevel, JSONObject> queryMap = new HashMap<>(aggLevels.size());

        for (AggLevel aggLevel : aggLevels) {
            JSONObject query = createQuery(metricName, startTime, endTime, topologyId, componentId, executorId,
                    hostName, port, streamId, aggLevel);
            queryMap.put(aggLevel, query);
        }

        return queryMap;
    }

    static JSONObject createQuery(Metric m) throws MetricException {
        return createQuery(m.getMetricName(), m.getTimestamp(), null, m.getTopologyId(), m.getComponentId(),
                m.getExecutorId(), m.getHostname(), m.getPort(), m.getStreamId(), m.getAggLevel());
    }

    private static JSONObject createQuery(String metricName, long startTime, Long endTime,
               String topologyId, String componentId, String executorId, String hostName,
               Integer port, String streamId, AggLevel aggLevel) throws MetricException {

        JSONObject obj = new JSONObject();

        long aggLevelValueMs = aggLevel.getValue() * 1000L * 60L;
        if (aggLevel.equals(AggLevel.AGG_LEVEL_NONE)) {
            aggLevelValueMs = 1000L;  // use 1 second for Yamas
        }

        startTime = aggLevelValueMs * (startTime / aggLevelValueMs);
        obj.put("start", startTime);

        // we need to specify an end range for yamas at least as large as the bucket size
        long minEndTime = startTime + 1000L * 60L * aggLevelValueMs;
        if (endTime == null || minEndTime > endTime) {
            endTime = minEndTime;
        } else {
            // need to round end time to match what the bucket takes for Yamas
            endTime = aggLevelValueMs * ((endTime + 1) / aggLevelValueMs);
        }
        obj.put("end", endTime);

        JSONArray queries = new JSONArray();
        obj.put("queries", queries);

        JSONObject query = new JSONObject();
        queries.add(query);

        query.put("aggregator", "zimsum");
        query.put("metric", YamasStore.namespace + "." + YamasStore.stormCluster + "." + metricName);

        switch (aggLevel) {
            case AGG_LEVEL_NONE:
                query.put("downsample", "1s-avg");
                break;
            case AGG_LEVEL_1_MIN:
                query.put("downsample", "1m-avg");
                break;
            case AGG_LEVEL_10_MIN:
                query.put("downsample", "10m-avg");
                break;
            case AGG_LEVEL_60_MIN:
                query.put("downsample", "60m-avg");
                break;
            default:
                throw new MetricException("Invalid agglevel - " + aggLevel);
        }

        JSONArray filters = new JSONArray();
        query.put("filters", filters);

        obj.put("showQuery", false);

        addFilter(filters, "topologyId", topologyId);
        addFilter(filters, "componentId", componentId);
        addFilter(filters, "executorId", executorId);
        addFilter(filters, "hostname", hostName);
        addFilter(filters, "streamId", streamId);
        addFilter(filters, "port", port);

        return obj;
    }

    private static void addFilter(JSONArray filters, String key, Object value) throws MetricException {
        JSONObject filter = new JSONObject();
        if (value instanceof String) {
            String val = (String)value;
            filter.put("filter", val);
            if (val.contains("*")) {
                filter.put("type", "wildcard");
            } else {
                filter.put("type", "literal_or");
            }
        } else if (value instanceof Integer) {  // currently only used by port - 0 means wildcard
            Integer port = (Integer)value;
            if (port == 0) {
                filter.put("filter", "*");
                filter.put("type", "wildcard");
            } else {
                filter.put("filter", port);
                filter.put("type", "literal_or");
            }
        } else {
            throw new MetricException("Unsupported filter value " + value);
        }

        filter.put("groupBy", true);
        filter.put("tagk", key);

        filters.add(filter);
    }
}
