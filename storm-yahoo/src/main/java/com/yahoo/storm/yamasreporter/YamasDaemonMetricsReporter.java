package com.yahoo.storm.yamasreporter;

import com.codahale.metrics.MetricRegistry;
import com.yahoo.storm.yamas.client.YamasHttpClient;
import com.yahoo.storm.yamas.dropwizard.ScheduledYamasReporter;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.storm.daemon.metrics.ClientMetricsUtils;
import org.apache.storm.daemon.metrics.reporters.PreparableReporter;
import org.apache.storm.utils.ObjectReader;
import org.apache.storm.validation.ConfigValidationAnnotations.IsMapEntryType;
import org.apache.storm.validation.ConfigValidationAnnotations.IsString;
import org.apache.storm.validation.Validated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class YamasDaemonMetricsReporter implements PreparableReporter, Validated {
    private static final Logger LOG = LoggerFactory.getLogger(YamasDaemonMetricsReporter.class);

    /**
     * Namespace metrics should be sent to.
     */
    @IsString
    public static final String STORM_DAEMON_METRICS_YAMAS_NAMESPACE = "storm.daemon.metrics.yamas.namespace";

    /**
     * Application included in yamas POST.  Not really sure how it is used by yamas.
     */
    @IsString
    public static final String STORM_DAEMON_METRICS_YAMAS_APPLICATION = "storm.daemon.metrics.yamas.application";

    /**
     * A prefix that can be put in front of all metrics.  Dimensions are a much better choice.
     */
    @IsString
    public static final String STORM_DAEMON_METRICS_YAMAS_PREFIX = "storm.daemon.metrics.yamas.prefix";

    /**
     * A http proxy url to send the metrics through, if needed.
     */
    @IsString
    public static final String STORM_DAEMON_METRICS_YAMAS_PROXY = "storm.daemon.metrics.yamas.proxy";

    /**
     * For debugging purposes.  The URL to send the metrics to.
     */
    @IsString
    public static final String STORM_DAEMON_METRICS_YAMAS_DEBUG_URL = "storm.daemon.metrics.yamas.debug.url";

    /**
     * Dimensions that are included with all of the metrics sent.  "host" and "daemon.name" are added
     * automatically.
     */
    @IsMapEntryType(keyType = String.class, valueType = String.class)
    public static final String STORM_DAEMON_METRICS_YAMAS_DIMENSIONS = "storm.daemon.metrics.yamas.dimensions";

    private ScheduledYamasReporter reporter = null;

    @Override
    public void prepare(MetricRegistry metricsRegistry, Map conf) {
        LOG.debug("Preparing...");
        ScheduledYamasReporter.Builder builder = ScheduledYamasReporter.forRegistry(metricsRegistry);
        builder.withApplication((String) conf.getOrDefault(STORM_DAEMON_METRICS_YAMAS_APPLICATION, "STORM_CLUSTER"));
        final String prefix = (String) conf.get(STORM_DAEMON_METRICS_YAMAS_PREFIX);
        if (prefix != null) {
            builder.prefixedWith(prefix);
        }

        String daemonName = System.getProperty("daemon.name");
        if (daemonName != null) {
            builder.withDimension("daemon.name", daemonName);
        } else {
            LOG.warn("NO DAEMON NAME PROVIDED TO YAMAS.");
        }

        Map<String, String> dimensions = (Map<String, String>) conf.get(STORM_DAEMON_METRICS_YAMAS_DIMENSIONS);
        if (dimensions != null) {
            builder.withDimensions(dimensions);
        }
        TimeUnit rateUnit = ClientMetricsUtils.getMetricsRateUnit(conf);
        if (rateUnit != null) {
            builder.convertRatesTo(rateUnit);
        }

        TimeUnit durationUnit = ClientMetricsUtils.getMetricsDurationUnit(conf);
        if (durationUnit != null) {
            builder.convertDurationsTo(durationUnit);
        }
        final String namespace = ObjectReader.getString(conf.get(STORM_DAEMON_METRICS_YAMAS_NAMESPACE));
        YamasHttpClient.Builder clientBuilder = new YamasHttpClient.Builder(namespace);

        final String proxy = (String) conf.get(STORM_DAEMON_METRICS_YAMAS_PROXY);
        if (proxy != null) {
            try {
                clientBuilder.withProxy(proxy);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException(e);
            }
        }

        final String debugUrl = (String) conf.get(STORM_DAEMON_METRICS_YAMAS_DEBUG_URL);
        if (debugUrl != null) {
            LOG.warn("USING YAMAS DEBUG URL {}", debugUrl);
            try {
                clientBuilder.debugFullUrl(debugUrl);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException(e);
            }
        }

        reporter = builder.build(clientBuilder.build());
    }

    @Override
    public void start() {
        if (reporter != null) {
            LOG.debug("Starting...");
            reporter.start(1, TimeUnit.MINUTES);
        } else {
            throw new IllegalStateException("Attempt to start without preparing " + getClass().getSimpleName());
        }
    }

    @Override
    public void stop() {
        if (reporter != null) {
            LOG.debug("Stopping...");
            reporter.stop();
        } else {
            throw new IllegalStateException("Attempt to stop without preparing " + getClass().getSimpleName());
        }
    }
}