/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.  The ASF licenses this file to you under the Apache License, Version
 * 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package org.apache.storm.metric;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Reservoir;
import java.util.Map;
import java.util.concurrent.Callable;
import org.apache.storm.daemon.metrics.MetricsUtils;
import org.apache.storm.daemon.metrics.reporters.PreparableReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unchecked")
public class StormMetricsRegistry {
    public static final MetricRegistry DEFAULT_REGISTRY = new MetricRegistry();
    private static final Logger LOG = LoggerFactory.getLogger(StormMetricsRegistry.class);

    public static Meter registerMeter(String name) {
        Meter meter = new Meter();
        return register(name, meter);
    }

    public static <T> Gauge<T> registerCallableAsGauge(final String name, final Callable<T> fn) {
        Gauge<T> gauge = () -> {
            try {
                return fn.call();
            } catch (Exception e) {
                LOG.error("Error getting gauge value for {}", name, e);
            }
            return null;
        };
        return register(name, gauge);
    }

    public static <T> Gauge<T> registerGauge(final String name, final Gauge<T> gauge) {
        return register(name, gauge);
    }

    public static void registerProvidedGauge(final String name, Gauge gauge) {
        register(name, gauge);
    }

    public static Histogram registerHistogram(String name, Reservoir reservoir) {
        Histogram histogram = new Histogram(reservoir);
        return register(name, histogram);
    }

    public static void startMetricsReporters(Map<String, Object> topoConf) {
        for (PreparableReporter reporter : MetricsUtils.getPreparableReporters(topoConf)) {
            startMetricsReporter(reporter, topoConf);
        }
    }

    private static void startMetricsReporter(PreparableReporter reporter, Map<String, Object> topoConf) {
        reporter.prepare(StormMetricsRegistry.DEFAULT_REGISTRY, topoConf);
        reporter.start();
        LOG.info("Started statistics report plugin...");
    }

    private static <T extends Metric> T register(String name, T metric) {
        T ret;
        try {
            ret = DEFAULT_REGISTRY.register(name, metric);
        } catch (IllegalArgumentException e) {
            // swallow IllegalArgumentException when the metric exists already
            ret = (T) DEFAULT_REGISTRY.getMetrics().get(name);
            if (ret == null) {
                throw e;
            } else {
                LOG.warn("Metric {} has already been registered", name);
            }
        }
        return ret;
    }
}
