package org.apache.storm.daemon.nimbus;

import com.codahale.metrics.CachedGauge;
import com.codahale.metrics.DerivativeGauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.SlidingTimeWindowReservoir;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.storm.generated.ClusterSummary;
import org.apache.storm.generated.NimbusSummary;
import org.apache.storm.generated.SupervisorSummary;
import org.apache.storm.generated.TopologySummary;
import org.apache.storm.metric.StormMetricsRegistry;
import org.slf4j.Logger;

class ClusterSummaryMetricSet implements Runnable {
    private static final int CACHING_WINDOW = 5;

    private final ClusterSummaryMetrics clusterSummaryMetrics;
    private final Supplier<Boolean> supplyActivate;
    private final Supplier<ClusterSummary> supplyClusterSummary;
    private final Logger LOG;

    private volatile boolean active = false;

    //Nimbus metrics distribution
    private final Histogram nimbusUptime;

    //Supervisor metrics distribution
    private final Histogram supervisorsUptime, supervisorsNumWorkers, supervisorsNumUsedWorkers, supervisorsUsedMem, supervisorsUsedCpu,
        supervisorsFragmentedMem, supervisorsFragmentedCpu;

    //Topology metrics distribution
    private final Histogram topologiesNumTasks, topologiesNumExecutors, topologiesNumWorker, topologiesUptime, topologiesReplicationCount,
        topologiesRequestedMemOnHeap, topologiesRequestedMemOffHeap, topologiesRequestedCpu, topologiesAssignedMemOnHeap,
        topologiesAssignedMemOffHeap, topologiesAssignedCpu;

    private final StormMetricsRegistry metricsRegistry;

    /**
     * Constructor to put all items in ClusterSummary in MetricSet as a metric.
     * All metrics are derived from a cached ClusterSummary object,
     * expired {@link ClusterSummaryMetricSet#CACHING_WINDOW} seconds after first query in a while from reporters.
     * In case of {@link com.codahale.metrics.ScheduledReporter}, CACHING_WINDOW should be set shorter than
     * reporting interval to avoid outdated reporting.
     *
     * @param metricsRegistry
     * @param supplyActivate returns true/false on whether metrics should be activated/deactivated
     * @param supplyClusterSummary returns a {@link ClusterSummary} object that has member values used to update metrics
     * @param parentLOG is a {@link Logger} instance, may be null
     */
    /**
     *
     */
    ClusterSummaryMetricSet(StormMetricsRegistry metricsRegistry,
                            Supplier<Boolean> supplyActivate, Supplier<ClusterSummary> supplyClusterSummary,
                            Logger parentLOG) {
        this.clusterSummaryMetrics = new ClusterSummaryMetrics();
        this.metricsRegistry = metricsRegistry;
        this.supplyActivate = supplyActivate;
        this.supplyClusterSummary = supplyClusterSummary;
        this.LOG = parentLOG;

        //Nimbus metrics distribution
        nimbusUptime = registerHistogram("nimbuses:uptime-secs");

        //Supervisor metrics distribution
        supervisorsUptime = registerHistogram("supervisors:uptime-secs");
        supervisorsNumWorkers = registerHistogram("supervisors:num-workers");
        supervisorsNumUsedWorkers = registerHistogram("supervisors:num-used-workers");
        supervisorsUsedMem = registerHistogram("supervisors:used-mem");
        supervisorsUsedCpu = registerHistogram("supervisors:used-cpu");
        supervisorsFragmentedMem = registerHistogram("supervisors:fragmented-mem");
        supervisorsFragmentedCpu = registerHistogram("supervisors:fragmented-cpu");

        //Topology metrics distribution
        topologiesNumTasks = registerHistogram("topologies:num-tasks");
        topologiesNumExecutors = registerHistogram("topologies:num-executors");
        topologiesNumWorker = registerHistogram("topologies:num-workers");
        topologiesUptime = registerHistogram("topologies:uptime-secs");
        topologiesReplicationCount = registerHistogram("topologies:replication-count");
        topologiesRequestedMemOnHeap = registerHistogram("topologies:requested-mem-on-heap");
        topologiesRequestedMemOffHeap = registerHistogram("topologies:requested-mem-off-heap");
        topologiesRequestedCpu = registerHistogram("topologies:requested-cpu");
        topologiesAssignedMemOnHeap = registerHistogram("topologies:assigned-mem-on-heap");
        topologiesAssignedMemOffHeap = registerHistogram("topologies:assigned-mem-off-heap");
        topologiesAssignedCpu = registerHistogram("topologies:assigned-cpu");

        //Break the code if out of sync to thrift protocol
        assert ClusterSummary._Fields.values().length == 3
            && ClusterSummary._Fields.findByName("supervisors") == ClusterSummary._Fields.SUPERVISORS
            && ClusterSummary._Fields.findByName("topologies") == ClusterSummary._Fields.TOPOLOGIES
            && ClusterSummary._Fields.findByName("nimbuses") == ClusterSummary._Fields.NIMBUSES;

        final CachedGauge<ClusterSummary> cachedSummary = new CachedGauge<ClusterSummary>(CACHING_WINDOW, TimeUnit.SECONDS) {
            @Override
            protected ClusterSummary loadValue() {
                try {
                    ClusterSummary newSummary = supplyClusterSummary.get();
                    if (LOG != null) {
                        LOG.debug("The new summary is {}", newSummary);
                    }
                    /*
                     * Update histograms based on the new summary. Most common implementation of Reporter reports Gauges before
                     * Histograms. Because DerivativeGauge will trigger cache refresh upon reporter's query, histogram will also be
                     * updated before query
                     */
                    updateHistogram(newSummary);
                    return newSummary;
                } catch (RuntimeException e) {
                    if (LOG != null) {
                        LOG.warn("Get cluster info exception.", e);
                    }
                    throw e;
                }
            }
        };

        clusterSummaryMetrics.put("cluster:num-nimbus-leaders",
            new DerivativeGauge<ClusterSummary, Long>(cachedSummary) {
                @Override
                protected Long transform(ClusterSummary clusterSummary) {
                    return clusterSummary.get_nimbuses().stream()
                        .filter(NimbusSummary::is_isLeader)
                        .count();
                }
            });
        clusterSummaryMetrics.put("cluster:num-nimbuses",
            new DerivativeGauge<ClusterSummary, Integer>(cachedSummary) {
                @Override
                protected Integer transform(ClusterSummary clusterSummary) {
                    return clusterSummary.get_nimbuses_size();
                }
            });
        clusterSummaryMetrics.put("cluster:num-supervisors",
            new DerivativeGauge<ClusterSummary, Integer>(cachedSummary) {
                @Override
                protected Integer transform(ClusterSummary clusterSummary) {
                    return clusterSummary.get_supervisors_size();
                }
            });
        clusterSummaryMetrics.put("cluster:num-topologies",
            new DerivativeGauge<ClusterSummary, Integer>(cachedSummary) {
                @Override
                protected Integer transform(ClusterSummary clusterSummary) {
                    return clusterSummary.get_topologies_size();
                }
            });
        clusterSummaryMetrics.put("cluster:num-total-workers",
            new DerivativeGauge<ClusterSummary, Integer>(cachedSummary) {
                @Override
                protected Integer transform(ClusterSummary clusterSummary) {
                    return clusterSummary.get_supervisors().stream()
                        .mapToInt(SupervisorSummary::get_num_workers)
                        .sum();
                }
            });
        clusterSummaryMetrics.put("cluster:num-total-used-workers",
            new DerivativeGauge<ClusterSummary, Integer>(cachedSummary) {
                @Override
                protected Integer transform(ClusterSummary clusterSummary) {
                    return clusterSummary.get_supervisors().stream()
                        .mapToInt(SupervisorSummary::get_num_used_workers)
                        .sum();
                }
            });
        clusterSummaryMetrics.put("cluster:total-fragmented-memory-non-negative",
            new DerivativeGauge<ClusterSummary, Double>(cachedSummary) {
                @Override
                protected Double transform(ClusterSummary clusterSummary) {
                    return clusterSummary.get_supervisors().stream()
                        //Filtered negative value
                        .mapToDouble(supervisorSummary -> Math.max(supervisorSummary.get_fragmented_mem(), 0))
                        .sum();
                }
            });
        clusterSummaryMetrics.put("cluster:total-fragmented-cpu-non-negative",
            new DerivativeGauge<ClusterSummary, Double>(cachedSummary) {
                @Override
                protected Double transform(ClusterSummary clusterSummary) {
                    return clusterSummary.get_supervisors().stream()
                        //Filtered negative value
                        .mapToDouble(supervisorSummary -> Math.max(supervisorSummary.get_fragmented_cpu(), 0))
                        .sum();
                }
            });
    }

    private void updateHistogram(ClusterSummary newSummary) {
        for (NimbusSummary nimbusSummary : newSummary.get_nimbuses()) {
            nimbusUptime.update(nimbusSummary.get_uptime_secs());
        }
        for (SupervisorSummary summary : newSummary.get_supervisors()) {
            supervisorsUptime.update(summary.get_uptime_secs());
            supervisorsNumWorkers.update(summary.get_num_workers());
            supervisorsNumUsedWorkers.update(summary.get_num_used_workers());
            supervisorsUsedMem.update(Math.round(summary.get_used_mem()));
            supervisorsUsedCpu.update(Math.round(summary.get_used_cpu()));
            supervisorsFragmentedMem.update(Math.round(summary.get_fragmented_mem()));
            supervisorsFragmentedCpu.update(Math.round(summary.get_fragmented_cpu()));
        }
        for (TopologySummary summary : newSummary.get_topologies()) {
            topologiesNumTasks.update(summary.get_num_tasks());
            topologiesNumExecutors.update(summary.get_num_executors());
            topologiesNumWorker.update(summary.get_num_workers());
            topologiesUptime.update(summary.get_uptime_secs());
            topologiesReplicationCount.update(summary.get_replication_count());
            topologiesRequestedMemOnHeap.update(Math.round(summary.get_requested_memonheap()));
            topologiesRequestedMemOffHeap.update(Math.round(summary.get_requested_memoffheap()));
            topologiesRequestedCpu.update(Math.round(summary.get_requested_cpu()));
            topologiesAssignedMemOnHeap.update(Math.round(summary.get_assigned_memonheap()));
            topologiesAssignedMemOffHeap.update(Math.round(summary.get_assigned_memoffheap()));
            topologiesAssignedCpu.update(Math.round(summary.get_assigned_cpu()));
        }
    }

    private Histogram registerHistogram(String name) {
        //This histogram reflects the data distribution across only one ClusterSummary, i.e.,
        // data distribution across all entities of a type (e.g., data from all nimbus/topologies) at one moment.
        // Hence we use half of the CACHING_WINDOW time to ensure it retains only data from the most recent update
        Histogram histogram = new Histogram(new SlidingTimeWindowReservoir(CACHING_WINDOW / 2, TimeUnit.SECONDS));
        clusterSummaryMetrics.put(name, histogram);
        return histogram;
    }

    void setActive(final boolean active) {
        if (this.active != active) {
            this.active = active;
            if (active) {
                metricsRegistry.registerAll(clusterSummaryMetrics);
            } else {
                metricsRegistry.removeAll(clusterSummaryMetrics);
            }
        }
    }

    @Override
    public void run() {
        setActive(supplyActivate.get());
    }
}
