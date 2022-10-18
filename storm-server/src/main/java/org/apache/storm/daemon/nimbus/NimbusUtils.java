/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.storm.daemon.nimbus;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import javax.security.auth.Subject;
import org.apache.storm.Config;
import org.apache.storm.Constants;
import org.apache.storm.DaemonConfig;
import org.apache.storm.StormTimer;
import org.apache.storm.blobstore.AtomicOutputStream;
import org.apache.storm.blobstore.BlobStore;
import org.apache.storm.blobstore.BlobStoreAclHandler;
import org.apache.storm.blobstore.InputStreamWithMeta;
import org.apache.storm.blobstore.KeySequenceNumber;
import org.apache.storm.blobstore.LocalFsBlobStore;
import org.apache.storm.callback.DefaultWatcherCallBack;
import org.apache.storm.cluster.ClusterStateContext;
import org.apache.storm.cluster.ClusterUtils;
import org.apache.storm.cluster.DaemonType;
import org.apache.storm.cluster.IStormClusterState;
import org.apache.storm.container.oci.OciUtils;
import org.apache.storm.daemon.DaemonCommon;
import org.apache.storm.daemon.Shutdownable;
import org.apache.storm.daemon.StormCommon;
import org.apache.storm.generated.AlreadyAliveException;
import org.apache.storm.generated.Assignment;
import org.apache.storm.generated.AuthorizationException;
import org.apache.storm.generated.BeginDownloadResult;
import org.apache.storm.generated.Bolt;
import org.apache.storm.generated.BoltAggregateStats;
import org.apache.storm.generated.ClusterSummary;
import org.apache.storm.generated.CommonAggregateStats;
import org.apache.storm.generated.ComponentAggregateStats;
import org.apache.storm.generated.ComponentPageInfo;
import org.apache.storm.generated.ComponentType;
import org.apache.storm.generated.Credentials;
import org.apache.storm.generated.DebugOptions;
import org.apache.storm.generated.ErrorInfo;
import org.apache.storm.generated.ExecutorInfo;
import org.apache.storm.generated.ExecutorStats;
import org.apache.storm.generated.ExecutorSummary;
import org.apache.storm.generated.GetInfoOptions;
import org.apache.storm.generated.IllegalStateException;
import org.apache.storm.generated.InvalidTopologyException;
import org.apache.storm.generated.KeyAlreadyExistsException;
import org.apache.storm.generated.KeyNotFoundException;
import org.apache.storm.generated.KillOptions;
import org.apache.storm.generated.LSTopoHistory;
import org.apache.storm.generated.ListBlobsResult;
import org.apache.storm.generated.LogConfig;
import org.apache.storm.generated.LogLevel;
import org.apache.storm.generated.LogLevelAction;
import org.apache.storm.generated.Nimbus.Iface;
import org.apache.storm.generated.Nimbus.Processor;
import org.apache.storm.generated.NimbusSummary;
import org.apache.storm.generated.NodeInfo;
import org.apache.storm.generated.NotAliveException;
import org.apache.storm.generated.NumErrorsChoice;
import org.apache.storm.generated.OwnerResourceSummary;
import org.apache.storm.generated.ProfileAction;
import org.apache.storm.generated.ProfileRequest;
import org.apache.storm.generated.ReadableBlobMeta;
import org.apache.storm.generated.RebalanceOptions;
import org.apache.storm.generated.SettableBlobMeta;
import org.apache.storm.generated.SpecificAggregateStats;
import org.apache.storm.generated.SpoutAggregateStats;
import org.apache.storm.generated.SpoutSpec;
import org.apache.storm.generated.StormBase;
import org.apache.storm.generated.StormTopology;
import org.apache.storm.generated.SubmitOptions;
import org.apache.storm.generated.SupervisorAssignments;
import org.apache.storm.generated.SupervisorInfo;
import org.apache.storm.generated.SupervisorPageInfo;
import org.apache.storm.generated.SupervisorSummary;
import org.apache.storm.generated.SupervisorWorkerHeartbeat;
import org.apache.storm.generated.SupervisorWorkerHeartbeats;
import org.apache.storm.generated.TopologyActionOptions;
import org.apache.storm.generated.TopologyHistoryInfo;
import org.apache.storm.generated.TopologyInfo;
import org.apache.storm.generated.TopologyInitialStatus;
import org.apache.storm.generated.TopologyPageInfo;
import org.apache.storm.generated.TopologyStatus;
import org.apache.storm.generated.TopologySummary;
import org.apache.storm.generated.WorkerMetricPoint;
import org.apache.storm.generated.WorkerMetrics;
import org.apache.storm.generated.WorkerResources;
import org.apache.storm.generated.WorkerSummary;
import org.apache.storm.logging.ThriftAccessLogger;
import org.apache.storm.metric.ClusterMetricsConsumerExecutor;
import org.apache.storm.metric.StormMetricsRegistry;
import org.apache.storm.metric.api.DataPoint;
import org.apache.storm.metric.api.IClusterMetricsConsumer;
import org.apache.storm.metric.api.IClusterMetricsConsumer.ClusterInfo;
import org.apache.storm.metricstore.AggLevel;
import org.apache.storm.metricstore.Metric;
import org.apache.storm.metricstore.MetricStore;
import org.apache.storm.metricstore.MetricStoreConfig;
import org.apache.storm.nimbus.AssignmentDistributionService;
import org.apache.storm.nimbus.DefaultTopologyValidator;
import org.apache.storm.nimbus.ILeaderElector;
import org.apache.storm.nimbus.ITopologyActionNotifierPlugin;
import org.apache.storm.nimbus.ITopologyValidator;
import org.apache.storm.nimbus.IWorkerHeartbeatsRecoveryStrategy;
import org.apache.storm.nimbus.NimbusInfo;
import org.apache.storm.nimbus.WorkerHeartbeatsRecoveryStrategyFactory;
import org.apache.storm.scheduler.Cluster;
import org.apache.storm.scheduler.DefaultScheduler;
import org.apache.storm.scheduler.ExecutorDetails;
import org.apache.storm.scheduler.INimbus;
import org.apache.storm.scheduler.IScheduler;
import org.apache.storm.scheduler.SchedulerAssignment;
import org.apache.storm.scheduler.SchedulerAssignmentImpl;
import org.apache.storm.scheduler.SupervisorDetails;
import org.apache.storm.scheduler.SupervisorResources;
import org.apache.storm.scheduler.Topologies;
import org.apache.storm.scheduler.TopologyDetails;
import org.apache.storm.scheduler.WorkerSlot;
import org.apache.storm.scheduler.blacklist.BlacklistScheduler;
import org.apache.storm.scheduler.multitenant.MultitenantScheduler;
import org.apache.storm.scheduler.resource.ResourceAwareScheduler;
import org.apache.storm.scheduler.resource.ResourceUtils;
import org.apache.storm.scheduler.resource.normalization.NormalizedResourceRequest;
import org.apache.storm.scheduler.resource.normalization.ResourceMetrics;
import org.apache.storm.security.INimbusCredentialPlugin;
import org.apache.storm.security.auth.ClientAuthUtils;
import org.apache.storm.security.auth.IAuthorizer;
import org.apache.storm.security.auth.ICredentialsRenewer;
import org.apache.storm.security.auth.IGroupMappingServiceProvider;
import org.apache.storm.security.auth.IPrincipalToLocal;
import org.apache.storm.security.auth.NimbusPrincipal;
import org.apache.storm.security.auth.ReqContext;
import org.apache.storm.security.auth.ThriftConnectionType;
import org.apache.storm.security.auth.ThriftServer;
import org.apache.storm.security.auth.workertoken.WorkerTokenManager;
import org.apache.storm.shade.com.google.common.annotations.VisibleForTesting;
import org.apache.storm.shade.com.google.common.base.Strings;
import org.apache.storm.shade.com.google.common.collect.ImmutableMap;
import org.apache.storm.shade.com.google.common.collect.MapDifference;
import org.apache.storm.shade.com.google.common.collect.Maps;
import org.apache.storm.shade.org.apache.curator.framework.CuratorFramework;
import org.apache.storm.shade.org.apache.zookeeper.ZooDefs;
import org.apache.storm.shade.org.apache.zookeeper.data.ACL;
import org.apache.storm.stats.ClientStatsUtil;
import org.apache.storm.stats.StatsUtil;
import org.apache.storm.thrift.TException;
import org.apache.storm.utils.BufferInputStream;
import org.apache.storm.utils.ConfigUtils;
import org.apache.storm.utils.LocalState;
import org.apache.storm.utils.ObjectReader;
import org.apache.storm.utils.ReflectionUtils;
import org.apache.storm.utils.RotatingMap;
import org.apache.storm.utils.ServerConfigUtils;
import org.apache.storm.utils.ServerUtils;
import org.apache.storm.utils.SimpleVersion;
import org.apache.storm.utils.Time;
import org.apache.storm.utils.TimeCacheMap;
import org.apache.storm.utils.TupleUtils;
import org.apache.storm.utils.Utils;
import org.apache.storm.utils.Utils.UptimeComputer;
import org.apache.storm.utils.VersionInfo;
import org.apache.storm.utils.WrappedAlreadyAliveException;
import org.apache.storm.utils.WrappedAuthorizationException;
import org.apache.storm.utils.WrappedIllegalStateException;
import org.apache.storm.utils.WrappedInvalidTopologyException;
import org.apache.storm.utils.WrappedNotAliveException;
import org.apache.storm.validation.ConfigValidation;
import org.apache.storm.zookeeper.AclEnforcement;
import org.apache.storm.zookeeper.ClientZookeeper;
import org.apache.storm.zookeeper.Zookeeper;
import org.json.simple.JSONValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NimbusUtils {
    private NimbusUtils() {}

    private static final Logger LOG = LoggerFactory.getLogger(Nimbus.class);
    public static final List<ACL> ZK_ACLS = Arrays.asList(ZooDefs.Ids.CREATOR_ALL_ACL.get(0));
    public static final SimpleVersion MIN_VERSION_SUPPORT_RPC_HEARTBEAT = new SimpleVersion("2.0.0");
    static final String STORM_VERSION = VersionInfo.getVersion();
    public static final Subject NIMBUS_SUBJECT = new Subject();
    static {
        NIMBUS_SUBJECT.getPrincipals().add(new NimbusPrincipal());
        NIMBUS_SUBJECT.setReadOnly();
    }

    public static List<ACL> getNimbusAcls(Map<String, Object> conf) {
        List<ACL> acls = null;
        if (Utils.isZkAuthenticationConfiguredStormServer(conf)) {
            acls = ZK_ACLS;
        }
        return acls;
    }

    static final List<String> EMPTY_STRING_LIST = Collections.unmodifiableList(Collections.emptyList());
    static final Set<String> EMPTY_STRING_SET = Collections.unmodifiableSet(Collections.emptySet());
    static final RotatingMap<String, Long> topologyCleanupDetected = new RotatingMap<>(2);

    // END TOPOLOGY STATE TRANSITIONS

    // TOPOLOGY STATE TRANSITIONS
    static StormBase make(TopologyStatus status) {
        StormBase ret = new StormBase();
        ret.set_status(status);
        //The following are required for backwards compatibility with clojure code
        ret.set_component_executors(Collections.emptyMap());
        ret.set_component_debug(Collections.emptyMap());
        return ret;
    }

    @SuppressWarnings("deprecation")
    static <T extends AutoCloseable> TimeCacheMap<String, T> fileCacheMap(Map<String, Object> conf) {
        return new TimeCacheMap<>(ObjectReader.getInt(conf.get(DaemonConfig.NIMBUS_FILE_COPY_EXPIRATION_SECS), 600),
            (id, stream) -> {
                try {
                    stream.close();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
    }

    //Not symmetric difference. Performing A.entrySet() - B.entrySet()
    static <K, V> Map<K, V> mapDiff(Map<? extends K, ? extends V> first, Map<? extends K, ? extends V> second) {
        Map<K, V> ret = new HashMap<>();
        for (Entry<? extends K, ? extends V> entry : second.entrySet()) {
            if (!entry.getValue().equals(first.get(entry.getKey()))) {
                ret.put(entry.getKey(), entry.getValue());
            }
        }
        return ret;
    }

    static IScheduler wrapAsBlacklistScheduler(Map<String, Object> conf, IScheduler scheduler,
        StormMetricsRegistry metricsRegistry) {
        BlacklistScheduler blacklistWrappedScheduler = new BlacklistScheduler(scheduler);
        blacklistWrappedScheduler.prepare(conf, metricsRegistry);
        return blacklistWrappedScheduler;
    }

    static IScheduler makeScheduler(Map<String, Object> conf, INimbus inimbus) {
        String schedClass = (String) conf.get(DaemonConfig.STORM_SCHEDULER);
        IScheduler scheduler = inimbus == null ? null : inimbus.getForcedScheduler();
        if (scheduler != null) {
            LOG.info("Using forced scheduler from INimbus {} {}", scheduler.getClass(), scheduler);
        } else if (schedClass != null) {
            LOG.info("Using custom scheduler: {}", schedClass);
            scheduler = ReflectionUtils.newInstance(schedClass);
        } else {
            LOG.info("Using default scheduler");
            scheduler = new DefaultScheduler();
        }
        return scheduler;
    }

    /**
     * Constructs a TimeCacheMap instance with a blob store timeout whose expiration callback invokes cancel on the value held by an expired
     * entry when that value is an AtomicOutputStream and calls close otherwise.
     *
     * @param conf the config to use
     * @return the newly created map
     */
    @SuppressWarnings("deprecation")
    static <T extends AutoCloseable> TimeCacheMap<String, T> makeBlobCacheMap(Map<String, Object> conf) {
        return new TimeCacheMap<>(ObjectReader.getInt(conf.get(DaemonConfig.NIMBUS_BLOBSTORE_EXPIRATION_SECS), 600),
            (id, stream) -> {
                try {
                    if (stream instanceof AtomicOutputStream) {
                        ((AtomicOutputStream) stream).cancel();
                    } else {
                        stream.close();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
    }

    /**
     * Constructs a TimeCacheMap instance with a blobstore timeout and no callback function.
     *
     * @param conf the config to use
     * @return the newly created TimeCacheMap
     */
    @SuppressWarnings("deprecation")
    static TimeCacheMap<String, Iterator<String>> makeBlobListCacheMap(Map<String, Object> conf) {
        return new TimeCacheMap<>(ObjectReader.getInt(conf.get(DaemonConfig.NIMBUS_BLOBSTORE_EXPIRATION_SECS), 600));
    }

    static ITopologyActionNotifierPlugin createTopologyActionNotifier(Map<String, Object> conf) {
        String clazz = (String) conf.get(DaemonConfig.NIMBUS_TOPOLOGY_ACTION_NOTIFIER_PLUGIN);
        ITopologyActionNotifierPlugin ret = null;
        if (clazz != null && !clazz.isEmpty()) {
            ret = ReflectionUtils.newInstance(clazz);
            try {
                ret.prepare(conf);
            } catch (Exception e) {
                LOG.warn("Ignoring exception, Could not initialize {}", clazz, e);
                ret = null;
            }
        }
        return ret;
    }

    @SuppressWarnings("unchecked")
    static List<ClusterMetricsConsumerExecutor> makeClusterMetricsConsumerExecutors(Map<String, Object> conf) {
        Collection<Map<String, Object>> consumers = (Collection<Map<String, Object>>) conf.get(
            DaemonConfig.STORM_CLUSTER_METRICS_CONSUMER_REGISTER);
        List<ClusterMetricsConsumerExecutor> ret = new ArrayList<>();
        if (consumers != null) {
            for (Map<String, Object> consumer : consumers) {
                ret.add(new ClusterMetricsConsumerExecutor((String) consumer.get("class"), consumer.get("argument")));
            }
        }
        return ret;
    }

    static Subject getSubject() {
        return ReqContext.context().subject();
    }

    static Map<String, Object> readTopoConf(String topoId, TopoCache tc) throws KeyNotFoundException,
        AuthorizationException, IOException {
        return tc.readTopoConf(topoId, getSubject());
    }

    static List<String> getKeyListFromId(Map<String, Object> conf, String id) {
        List<String> ret = new ArrayList<>(3);
        ret.add(ConfigUtils.masterStormCodeKey(id));
        ret.add(ConfigUtils.masterStormConfKey(id));
        if (!ConfigUtils.isLocalMode(conf)) {
            ret.add(ConfigUtils.masterStormJarKey(id));
        }
        return ret;
    }

    public static int getVersionForKey(String key, NimbusInfo nimbusInfo,
        CuratorFramework zkClient) throws KeyNotFoundException {
        KeySequenceNumber kseq = new KeySequenceNumber(key, nimbusInfo);
        return kseq.getKeySequenceNumber(zkClient);
    }

    static StormTopology readStormTopology(String topoId, TopoCache tc) throws KeyNotFoundException, AuthorizationException,
        IOException {
        return tc.readTopology(topoId, getSubject());
    }

    static Map<String, Object> readTopoConfAsNimbus(String topoId, TopoCache tc) throws KeyNotFoundException,
        AuthorizationException, IOException {
        return tc.readTopoConf(topoId, NIMBUS_SUBJECT);
    }

    static StormTopology readStormTopologyAsNimbus(String topoId, TopoCache tc) throws KeyNotFoundException,
        AuthorizationException, IOException {
        return tc.readTopology(topoId, NIMBUS_SUBJECT);
    }

    /**
     * convert {topology-id -> SchedulerAssignment} to {topology-id -> {executor [node port]}}.
     *
     * @return {topology-id -> {executor [node port]}} mapping
     */
    static Map<String, Map<List<Long>, List<Object>>> computeTopoToExecToNodePort(
        Map<String, SchedulerAssignment> schedAssignments, List<String> assignedTopologyIds) {
        Map<String, Map<List<Long>, List<Object>>> ret = new HashMap<>();
        for (Entry<String, SchedulerAssignment> schedEntry : schedAssignments.entrySet()) {
            Map<List<Long>, List<Object>> execToNodePort = new HashMap<>();
            for (Entry<ExecutorDetails, WorkerSlot> execAndNodePort : schedEntry.getValue().getExecutorToSlot().entrySet()) {
                ExecutorDetails exec = execAndNodePort.getKey();
                WorkerSlot slot = execAndNodePort.getValue();
                execToNodePort.put(exec.toList(), slot.toList());
            }
            ret.put(schedEntry.getKey(), execToNodePort);
        }
        for (String id : assignedTopologyIds) {
            ret.putIfAbsent(id, null);
        }
        return ret;
    }

    static int numUsedWorkers(SchedulerAssignment assignment) {
        if (assignment == null) {
            return 0;
        }
        return assignment.getSlots().size();
    }

    /**
     * Convert {topology-id -> SchedulerAssignment} to {topology-id -> {WorkerSlot WorkerResources}}. Make sure this can deal with other
     * non-RAS schedulers later we may further support map-for-any-resources.
     *
     * @param schedAssignments the assignments
     * @return The resources used per slot
     */
    static Map<String, Map<WorkerSlot, WorkerResources>> computeTopoToNodePortToResources(
        Map<String, SchedulerAssignment> schedAssignments) {
        Map<String, Map<WorkerSlot, WorkerResources>> ret = new HashMap<>();
        for (Entry<String, SchedulerAssignment> schedEntry : schedAssignments.entrySet()) {
            ret.put(schedEntry.getKey(), schedEntry.getValue().getScheduledResources());
        }
        return ret;
    }

    static List<List<Long>> changedExecutors(Map<List<Long>, NodeInfo> map, Map<List<Long>,
        List<Object>> newExecToNodePort) {
        HashMap<NodeInfo, List<List<Long>>> tmpSlotAssigned = map == null ? new HashMap<>() : Utils.reverseMap(map);
        HashMap<List<Object>, List<List<Long>>> slotAssigned = new HashMap<>();
        for (Entry<NodeInfo, List<List<Long>>> entry : tmpSlotAssigned.entrySet()) {
            NodeInfo ni = entry.getKey();
            List<Object> key = new ArrayList<>(2);
            key.add(ni.get_node());
            key.add(ni.get_port_iterator().next());
            List<List<Long>> value = new ArrayList<>(entry.getValue());
            value.sort(Comparator.comparing(a -> a.get(0)));
            slotAssigned.put(key, value);
        }
        HashMap<List<Object>, List<List<Long>>> tmpNewSlotAssigned = newExecToNodePort == null ? new HashMap<>() :
            Utils.reverseMap(newExecToNodePort);
        HashMap<List<Object>, List<List<Long>>> newSlotAssigned = new HashMap<>();
        for (Entry<List<Object>, List<List<Long>>> entry : tmpNewSlotAssigned.entrySet()) {
            List<List<Long>> value = new ArrayList<>(entry.getValue());
            value.sort(Comparator.comparing(a -> a.get(0)));
            newSlotAssigned.put(entry.getKey(), value);
        }
        Map<List<Object>, List<List<Long>>> diff = mapDiff(slotAssigned, newSlotAssigned);
        List<List<Long>> ret = new ArrayList<>();
        for (List<List<Long>> val : diff.values()) {
            ret.addAll(val);
        }
        return ret;
    }

    static Set<WorkerSlot> newlyAddedSlots(Assignment old, Assignment current) {
        Set<NodeInfo> oldSlots = new HashSet<>(old.get_executor_node_port().values());
        Set<NodeInfo> niRet = new HashSet<>(current.get_executor_node_port().values());
        niRet.removeAll(oldSlots);
        Set<WorkerSlot> ret = new HashSet<>();
        for (NodeInfo ni : niRet) {
            ret.add(new WorkerSlot(ni.get_node(), ni.get_port_iterator().next()));
        }
        return ret;
    }

    static Map<String, SupervisorDetails> basicSupervisorDetailsMap(IStormClusterState state) {
        Map<String, SupervisorDetails> ret = new HashMap<>();
        for (Entry<String, SupervisorInfo> entry : state.allSupervisorInfo().entrySet()) {
            String id = entry.getKey();
            SupervisorInfo info = entry.getValue();
            ret.put(id, new SupervisorDetails(id, info.get_server_port(), info.get_hostname(),
                                              info.get_scheduler_meta(), null, info.get_resources_map()));
        }
        return ret;
    }

    /**
     * NOTE: this can return false when a topology has just been activated.  The topology may still be
     * in the STORMS_SUBTREE.
     */
    static boolean isTopologyActive(IStormClusterState state, String topoName) {
        return state.getTopoId(topoName).isPresent();
    }

    static boolean isTopologyActiveOrActivating(IStormClusterState state, String topoName) {
        return isTopologyActive(state, topoName) || state.activeStorms().contains(topoName);
    }

    /**
     * Returns the topologyId of the topology using this blob.
     * @param state the cluster state
     * @param topoCache the topology cache
     * @param key the blob key
     * @return null or id
     */
    static String topologyUsingThisBlob(IStormClusterState state, TopoCache topoCache, String key) {
        for (String topologyId : state.activeStorms()) {
            Map<String, Object> topoConf = null;
            try {
                topoConf = readTopoConfAsNimbus(topologyId, topoCache);
            } catch (KeyNotFoundException | AuthorizationException | IOException e) {
                continue;
            }
            if (topoConf == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> blobstoreMap = (Map<String, Map<String, Object>>) topoConf.get(Config.TOPOLOGY_BLOBSTORE_MAP);
            if (blobstoreMap != null && blobstoreMap.containsKey(key)) {
                return topologyId;
            }
        }
        return null;
    }

    static Map<String, Object> tryReadTopoConf(String topoId, TopoCache tc)
        throws NotAliveException, AuthorizationException, IOException {
        try {
            return readTopoConfAsNimbus(topoId, tc);
            //Was a try-cause but I looked at the code around this and key not found is not wrapped in runtime,
            // so it is not needed
        } catch (KeyNotFoundException e) {
            if (topoId == null) {
                throw new NullPointerException();
            }
            throw new WrappedNotAliveException(topoId);
        }
    }

    static void rotateTopologyCleanupMap(long deletionDelay) {
        if (Time.currentTimeMillis() - topologyCleanupRotationTime > deletionDelay) {
            topologyCleanupDetected.rotate();
            topologyCleanupRotationTime = Time.currentTimeMillis();
        }
    }

    static long getTopologyCleanupDetectedTime(String topologyId) {
        Long firstDetectedForDeletion = topologyCleanupDetected.get(topologyId);
        if (firstDetectedForDeletion == null) {
            firstDetectedForDeletion = Time.currentTimeMillis();
            topologyCleanupDetected.put(topologyId, firstDetectedForDeletion);
        }
        return firstDetectedForDeletion;
    }

    /**
     * From a set of topologies that have been found to cleanup, return a set that has been detected for a minimum
     * amount of time. Topology entries first detected less than NIMBUS_TOPOLOGY_BLOBSTORE_DELETION_DELAY_MS ago are
     * ignored. The delay is to prevent a race conditions such as when a blobstore is created and when the topology
     * is submitted. It is possible the Nimbus cleanup timer task will find entries to delete between these two events.
     *
     * <p>Tracked topology entries are rotated out of the stored map periodically.
     *
     * @param toposToClean topologies considered for cleanup
     * @param conf the nimbus conf
     * @return the set of topologies that have been detected for cleanup past the expiration time
     */
    static Set<String> getExpiredTopologyIds(Set<String> toposToClean, Map<String, Object> conf) {
        Set<String> idleTopologies = new HashSet<>();
        long topologyDeletionDelay = ObjectReader.getInt(
                conf.get(DaemonConfig.NIMBUS_TOPOLOGY_BLOBSTORE_DELETION_DELAY_MS), 5 * 60 * 1000);
        for (String topologyId : toposToClean) {
            if (Math.max(0, Time.currentTimeMillis() - getTopologyCleanupDetectedTime(topologyId)) >= topologyDeletionDelay) {
                idleTopologies.add(topologyId);
            }
        }

        rotateTopologyCleanupMap(topologyDeletionDelay);

        return idleTopologies;
    }

    @VisibleForTesting
    public static Set<String> topoIdsToClean(IStormClusterState state, BlobStore store, Map<String, Object> conf) {
        Set<String> ret = new HashSet<>();
        ret.addAll(Utils.OR(state.heartbeatStorms(), EMPTY_STRING_LIST));
        ret.addAll(Utils.OR(state.errorTopologies(), EMPTY_STRING_LIST));
        ret.addAll(Utils.OR(store.storedTopoIds(), EMPTY_STRING_SET));
        ret.addAll(Utils.OR(state.backpressureTopologies(), EMPTY_STRING_LIST));
        ret.addAll(Utils.OR(state.idsOfTopologiesWithPrivateWorkerKeys(), EMPTY_STRING_SET));
        ret = getExpiredTopologyIds(ret, conf);
        ret.removeAll(Utils.OR(state.activeStorms(), EMPTY_STRING_LIST));
        return ret;
    }

    static String extractStatusStr(StormBase base) {
        String ret = null;
        if (base != null) {
            TopologyStatus status = base.get_status();
            if (status != null) {
                ret = status.name().toUpperCase();
            }
        }
        return ret;
    }

    static StormTopology normalizeTopology(Map<String, Object> topoConf, StormTopology topology)
        throws InvalidTopologyException {
        StormTopology ret = topology.deepCopy();
        for (Object comp : StormCommon.allComponents(ret).values()) {
            Map<String, Object> mergedConf = StormCommon.componentConf(comp);
            mergedConf.put(Config.TOPOLOGY_TASKS, ServerUtils.getComponentParallelism(topoConf, comp));
            String jsonConf = JSONValue.toJSONString(mergedConf);
            StormCommon.getComponentCommon(comp).set_json_conf(jsonConf);
        }
        return ret;
    }

    static void addToDecorators(Set<String> decorators, List<String> conf) {
        if (conf != null) {
            decorators.addAll(conf);
        }
    }

    @SuppressWarnings("unchecked")
    static void addToSerializers(Map<String, String> ser, List<Object> conf) {
        if (conf != null) {
            for (Object o : conf) {
                if (o instanceof Map) {
                    ser.putAll((Map<String, String>) o);
                } else {
                    ser.put((String) o, null);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    /**
     * Create a normalized topology conf.
     *
     * @param conf  the nimbus conf
     * @param topoConf initial topology conf
     * @param topology  the Storm topology
     */
    static Map<String, Object> normalizeConf(Map<String, Object> conf, Map<String, Object> topoConf, StormTopology topology) {

        // clear any values from the topoConf that it should not be setting.
        topoConf.remove(Config.STORM_WORKERS_ARTIFACTS_DIR);

        //ensure that serializations are same for all tasks no matter what's on
        // the supervisors. this also allows you to declare the serializations as a sequence
        List<Map<String, Object>> allConfs = new ArrayList<>();
        for (Object comp : StormCommon.allComponents(topology).values()) {
            allConfs.add(StormCommon.componentConf(comp));
        }

        Set<String> decorators = new HashSet<>();
        //Yes we are putting in a config that is not the same type we pulled out.
        Map<String, String> serializers = new HashMap<>();
        for (Map<String, Object> c : allConfs) {
            addToDecorators(decorators, (List<String>) c.get(Config.TOPOLOGY_KRYO_DECORATORS));
            addToSerializers(serializers, (List<Object>) c.get(Config.TOPOLOGY_KRYO_REGISTER));
        }
        addToDecorators(decorators, (List<String>) topoConf.getOrDefault(Config.TOPOLOGY_KRYO_DECORATORS,
                                                                         conf.get(Config.TOPOLOGY_KRYO_DECORATORS)));
        addToSerializers(serializers, (List<Object>) topoConf.getOrDefault(Config.TOPOLOGY_KRYO_REGISTER,
                                                                           conf.get(Config.TOPOLOGY_KRYO_REGISTER)));

        Map<String, Object> mergedConf = Utils.merge(conf, topoConf);
        Map<String, Object> ret = new HashMap<>(topoConf);
        ret.put(Config.TOPOLOGY_KRYO_REGISTER, serializers);
        ret.put(Config.TOPOLOGY_KRYO_DECORATORS, new ArrayList<>(decorators));
        ret.put(Config.TOPOLOGY_ACKER_EXECUTORS, mergedConf.get(Config.TOPOLOGY_ACKER_EXECUTORS));
        ret.put(Config.TOPOLOGY_EVENTLOGGER_EXECUTORS, mergedConf.get(Config.TOPOLOGY_EVENTLOGGER_EXECUTORS));
        ret.put(Config.TOPOLOGY_MAX_TASK_PARALLELISM, mergedConf.get(Config.TOPOLOGY_MAX_TASK_PARALLELISM));

        // storm.messaging.netty.authentication is about inter-worker communication
        // enforce netty authentication when either topo or daemon set it to true
        boolean enforceNettyAuth = false;
        if (!topoConf.containsKey(Config.STORM_MESSAGING_NETTY_AUTHENTICATION)) {
            enforceNettyAuth = (Boolean) conf.get(Config.STORM_MESSAGING_NETTY_AUTHENTICATION);
        } else {
            enforceNettyAuth = (Boolean) topoConf.get(Config.STORM_MESSAGING_NETTY_AUTHENTICATION)
                                || (Boolean) conf.get(Config.STORM_MESSAGING_NETTY_AUTHENTICATION);
        }
        LOG.debug("For netty authentication, topo conf is: {}, cluster conf is: {}, Enforce netty auth: {}",
            topoConf.get(Config.STORM_MESSAGING_NETTY_AUTHENTICATION),
            conf.get(Config.STORM_MESSAGING_NETTY_AUTHENTICATION),
            enforceNettyAuth);
        ret.put(Config.STORM_MESSAGING_NETTY_AUTHENTICATION, enforceNettyAuth);

        if (!mergedConf.containsKey(Config.TOPOLOGY_METRICS_REPORTERS) && mergedConf.containsKey(Config.STORM_METRICS_REPORTERS)) {
            ret.put(Config.TOPOLOGY_METRICS_REPORTERS, mergedConf.get(Config.STORM_METRICS_REPORTERS));
        }

        // add any system metrics reporters to the topology metrics reporters
        if (conf.containsKey(Config.STORM_TOPOLOGY_METRICS_SYSTEM_REPORTERS)) {
            List<Map<String, Object>> reporters = (List<Map<String, Object>>)
                    ret.computeIfAbsent(Config.TOPOLOGY_METRICS_REPORTERS, (key) -> new ArrayList<>());
            List<Map<String, Object>> systemReporters = (List<Map<String, Object>>)
                    conf.get(Config.STORM_TOPOLOGY_METRICS_SYSTEM_REPORTERS);
            reporters.addAll(systemReporters);
        }

        // Don't allow topoConf to override various cluster-specific properties.
        // Specifically adding the cluster settings to the topoConf here will make sure these settings
        // also override the subsequently generated conf picked up locally on the classpath.
        //
        // We will be dealing with 3 confs:
        // 1) the submitted topoConf created here
        // 2) the combined classpath conf with the topoConf added on top
        // 3) the nimbus conf with conf 2 above added on top.
        //
        // By first forcing the topology conf to contain the nimbus settings, we guarantee all three confs
        // will have the correct settings that cannot be overriden by the submitter.
        ret.put(Config.STORM_CGROUP_HIERARCHY_DIR, conf.get(Config.STORM_CGROUP_HIERARCHY_DIR));
        ret.put(Config.WORKER_METRICS, conf.get(Config.WORKER_METRICS));

        if (mergedConf.containsKey(Config.TOPOLOGY_WORKER_TIMEOUT_SECS)) {
            int workerTimeoutSecs = (Integer) ObjectReader.getInt(mergedConf.get(Config.TOPOLOGY_WORKER_TIMEOUT_SECS));
            int workerMaxTimeoutSecs = (Integer) ObjectReader.getInt(mergedConf.get(Config.WORKER_MAX_TIMEOUT_SECS));
            if (workerTimeoutSecs > workerMaxTimeoutSecs) {
                ret.put(Config.TOPOLOGY_WORKER_TIMEOUT_SECS, workerMaxTimeoutSecs);
                String topoId = (String) mergedConf.get(Config.STORM_ID);
                LOG.warn("Topology {} topology.worker.timeout.secs is too large. Reducing from {} to {}",
                    topoId, workerTimeoutSecs, workerMaxTimeoutSecs);
            }
        }
        return ret;
    }

    static void rmBlobKey(BlobStore store, String key, IStormClusterState state) {
        try {
            store.deleteBlob(key, NIMBUS_SUBJECT);
        } catch (Exception e) {
            //Yes eat the exception
            LOG.info("Exception {}", e);
        }
    }

    /**
     * Deletes jar files in dirLoc older than seconds.
     *
     * @param dirLoc  the location to look in for file
     * @param seconds how old is too old and should be deleted
     */
    @VisibleForTesting
    public static void cleanInbox(String dirLoc, int seconds) {
        final long now = Time.currentTimeMillis();
        final long ms = Time.secsToMillis(seconds);
        File dir = new File(dirLoc);
        for (File f : dir.listFiles((file) -> file.isFile() && ((file.lastModified() + ms) <= now))) {
            if (f.delete()) {
                LOG.info("Cleaning inbox ... deleted: {}", f.getName());
            } else {
                LOG.error("Cleaning inbox ... error deleting: {}", f.getName());
            }
        }
    }

    static ExecutorInfo toExecInfo(List<Long> exec) {
        return new ExecutorInfo(exec.get(0).intValue(), exec.get(1).intValue());
    }

    static void validateTopologyName(String name) throws InvalidTopologyException {
        try {
            Utils.validateTopologyName(name);
        } catch (IllegalArgumentException e) {
            throw new WrappedInvalidTopologyException(e.getMessage());
        }
    }

    static StormTopology tryReadTopology(String topoId, TopoCache tc)
        throws NotAliveException, AuthorizationException, IOException {
        try {
            return readStormTopologyAsNimbus(topoId, tc);
        } catch (KeyNotFoundException e) {
            throw new WrappedNotAliveException(topoId);
        }
    }

    static void validateTopologySize(Map<String, Object> topoConf, Map<String, Object> nimbusConf,
        StormTopology topology) throws InvalidTopologyException {
        // check allowedWorkers only if the scheduler is not the Resource Aware Scheduler
        if (!ServerUtils.isRas(nimbusConf)) {
            int workerCount = ObjectReader.getInt(topoConf.get(Config.TOPOLOGY_WORKERS), 1);
            Integer allowedWorkers = ObjectReader.getInt(nimbusConf.get(DaemonConfig.NIMBUS_SLOTS_PER_TOPOLOGY), null);
            if (allowedWorkers != null && workerCount > allowedWorkers) {
                throw new WrappedInvalidTopologyException("Failed to submit topology. Topology requests more than "
                        + allowedWorkers + " workers.");
            }
        }
        int executorsCount = 0;
        for (Object comp : StormCommon.allComponents(topology).values()) {
            executorsCount += StormCommon.numStartExecutors(comp);
        }
        Integer allowedExecutors = ObjectReader.getInt(nimbusConf.get(DaemonConfig.NIMBUS_EXECUTORS_PER_TOPOLOGY), null);
        if (allowedExecutors != null && executorsCount > allowedExecutors) {
            throw new WrappedInvalidTopologyException("Failed to submit topology. Topology requests more than "
                    + allowedExecutors + " executors.");
        }
    }

    static void setLoggerTimeouts(LogLevel level) {
        int timeoutSecs = level.get_reset_log_level_timeout_secs();
        if (timeoutSecs > 0) {
            level.set_reset_log_level_timeout_epoch(Time.currentTimeMillis() + Time.secsToMillis(timeoutSecs));
        } else {
            level.unset_reset_log_level_timeout_epoch();
        }
    }

    @VisibleForTesting
    public static List<String> topologiesOnSupervisor(Map<String, Assignment> assignments, String supervisorId) {
        Set<String> ret = new HashSet<>();
        for (Entry<String, Assignment> entry : assignments.entrySet()) {
            Assignment assignment = entry.getValue();
            for (NodeInfo nodeInfo : assignment.get_executor_node_port().values()) {
                if (supervisorId.equals(nodeInfo.get_node())) {
                    ret.add(entry.getKey());
                    break;
                }
            }
        }

        return new ArrayList<>(ret);
    }

    static ClusterInfo mkClusterInfo() {
        return new ClusterInfo(Time.currentTimeSecs());
    }

    static List<DataPoint> extractClusterMetrics(ClusterSummary summ) {
        List<DataPoint> ret = new ArrayList<>();
        ret.add(new DataPoint("supervisors", summ.get_supervisors_size()));
        ret.add(new DataPoint("topologies", summ.get_topologies_size()));

        int totalSlots = 0;
        int usedSlots = 0;
        for (SupervisorSummary sup : summ.get_supervisors()) {
            usedSlots += sup.get_num_used_workers();
            totalSlots += sup.get_num_workers();
        }
        ret.add(new DataPoint("slotsTotal", totalSlots));
        ret.add(new DataPoint("slotsUsed", usedSlots));
        ret.add(new DataPoint("slotsFree", totalSlots - usedSlots));

        int totalExecutors = 0;
        int totalTasks = 0;
        for (TopologySummary topo : summ.get_topologies()) {
            totalExecutors += topo.get_num_executors();
            totalTasks += topo.get_num_tasks();
        }
        ret.add(new DataPoint("executorsTotal", totalExecutors));
        ret.add(new DataPoint("tasksTotal", totalTasks));
        return ret;
    }

    static Map<IClusterMetricsConsumer.SupervisorInfo, List<DataPoint>> extractSupervisorMetrics(ClusterSummary summ) {
        Map<IClusterMetricsConsumer.SupervisorInfo, List<DataPoint>> ret = new HashMap<>();
        for (SupervisorSummary sup : summ.get_supervisors()) {
            List<DataPoint> metrics = new ArrayList<>();
            metrics.add(new DataPoint("slotsTotal", sup.get_num_workers()));
            metrics.add(new DataPoint("slotsUsed", sup.get_num_used_workers()));
            metrics.add(new DataPoint("totalMem", sup.get_total_resources().get(Constants.COMMON_TOTAL_MEMORY_RESOURCE_NAME)));
            metrics.add(new DataPoint("totalCpu", sup.get_total_resources().get(Constants.COMMON_CPU_RESOURCE_NAME)));
            metrics.add(new DataPoint("usedMem", sup.get_used_mem()));
            metrics.add(new DataPoint("usedCpu", sup.get_used_cpu()));
            IClusterMetricsConsumer.SupervisorInfo info =
                    new IClusterMetricsConsumer.SupervisorInfo(sup.get_host(), sup.get_supervisor_id(), Time.currentTimeSecs());
            ret.put(info, metrics);
        }
        return ret;
    }

    static void setResourcesDefaultIfNotSet(Map<String, NormalizedResourceRequest> compResourcesMap, String compId,
                                                    Map<String, Object> topoConf) {
        NormalizedResourceRequest resources = compResourcesMap.get(compId);
        if (resources == null) {
            compResourcesMap.put(compId, new NormalizedResourceRequest(topoConf, compId));
        }
    }

    static void validatePortAvailable(Map<String, Object> conf) throws IOException {
        int port = ObjectReader.getInt(conf.get(Config.NIMBUS_THRIFT_PORT));
        try (ServerSocket socket = new ServerSocket(port)) {
            //Nothing
        } catch (BindException e) {
            LOG.error("{} is not available. Check if another process is already listening on {}", port, port);
            System.exit(0);
        }
    }

    static Nimbus launchServer(Map<String, Object> conf, INimbus inimbus) throws Exception {
        StormCommon.validateDistributedMode(conf);
        validatePortAvailable(conf);
        OciUtils.validateImageInDaemonConf(conf);
        StormMetricsRegistry metricsRegistry = new StormMetricsRegistry();
        final Nimbus nimbus = new Nimbus(conf, inimbus, metricsRegistry);
        nimbus.launchServer();
        final ThriftServer server = new ThriftServer(conf, new Processor<>(nimbus), ThriftConnectionType.NIMBUS);
        metricsRegistry.startMetricsReporters(conf);
        Utils.addShutdownHookWithDelayedForceKill(() -> {
            metricsRegistry.stopMetricsReporters();
            nimbus.shutdown();
            server.stop();
        }, 10);
        if (ClientAuthUtils.areWorkerTokensEnabledServer(server, conf)) {
            nimbus.initWorkerTokenManager();
        }
        LOG.info("Starting nimbus server for storm version '{}'", STORM_VERSION);
        server.serve();
        return nimbus;
    }

    public static NimbusUtils launch(INimbus inimbus) throws Exception {
        Map<String, Object> conf = Utils.merge(ConfigUtils.readStormConfig(),
                                               ConfigUtils.readYamlConfig("storm-cluster-auth.yaml", false));
        boolean fixupAcl = (boolean) conf.get(DaemonConfig.STORM_NIMBUS_ZOOKEEPER_ACLS_FIXUP);
        boolean checkAcl = fixupAcl || (boolean) conf.get(DaemonConfig.STORM_NIMBUS_ZOOKEEPER_ACLS_CHECK);
        if (checkAcl) {
            AclEnforcement.verifyAcls(conf, fixupAcl);
        }
        return launchServer(conf, inimbus);
    }

    public static void main(String[] args) throws Exception {
        Utils.setupDefaultUncaughtExceptionHandler();
        launch(new StandaloneINimbus());
    }

    @SuppressWarnings("checkstyle:AbbreviationAsWordInName")
    static CuratorFramework makeZKClient(Map<String, Object> conf) {
        List<String> servers = (List<String>) conf.get(Config.STORM_ZOOKEEPER_SERVERS);
        Object port = conf.get(Config.STORM_ZOOKEEPER_PORT);
        String root = (String) conf.get(Config.STORM_ZOOKEEPER_ROOT);
        CuratorFramework ret = null;
        if (servers != null && port != null) {
            ret = ClientZookeeper.mkClient(conf, servers, port, root, new DefaultWatcherCallBack(), conf, DaemonType.NIMBUS);
        }
        return ret;
    }

    static IStormClusterState makeStormClusterState(Map<String, Object> conf) throws Exception {
        return ClusterUtils.mkStormClusterState(conf, new ClusterStateContext(DaemonType.NIMBUS, conf));
    }

    static List<Integer> asIntExec(List<Long> exec) {
        List<Integer> ret = new ArrayList<>(2);
        ret.add(exec.get(0).intValue());
        ret.add(exec.get(1).intValue());
        return ret;
    }

    /**
     * Diff old/new assignment to find nodes which assigned assignments has changed.
     *
     * @param oldAss old assigned assignment
     * @param newAss new assigned assignment
     * @return nodeId -> host map of assignments changed nodes
     */
    static Map<String, String> assignmentChangedNodes(Assignment oldAss, Assignment newAss) {
        Map<List<Long>, NodeInfo> oldExecutorNodePort = null;
        Map<List<Long>, NodeInfo> newExecutorNodePort = null;
        Map<String, String> allNodeHost = new HashMap<>();
        if (oldAss != null) {
            oldExecutorNodePort = oldAss.get_executor_node_port();
            allNodeHost.putAll(oldAss.get_node_host());
        }
        if (newAss != null) {
            newExecutorNodePort = newAss.get_executor_node_port();
            allNodeHost.putAll(newAss.get_node_host());
        }
        //kill or newly submit
        if (oldAss == null || newAss == null) {
            return allNodeHost;
        } else {
            // rebalance
            Map<String, String> ret = new HashMap<>();
            for (Entry<List<Long>, NodeInfo> entry : newExecutorNodePort.entrySet()) {
                NodeInfo newNodeInfo = entry.getValue();
                NodeInfo oldNodeInfo = oldExecutorNodePort.get(entry.getKey());
                if (null != oldNodeInfo) {
                    if (!oldNodeInfo.equals(newNodeInfo)) {
                        ret.put(oldNodeInfo.get_node(), allNodeHost.get(oldNodeInfo.get_node()));
                        ret.put(newNodeInfo.get_node(), allNodeHost.get(newNodeInfo.get_node()));
                    }
                } else {
                    ret.put(newNodeInfo.get_node(), allNodeHost.get(newNodeInfo.get_node()));
                }
            }

            return ret;
        }
    }

    /**
     * Pick out assignments for a specific host from all assignments.  This could include multiple NUMA
     * supervisors on an individual host.
     * @param assignmentMap stormId -> assignment map
     * @param hostname        hostname
     * @return stormId -> assignment map for the node
     */
    static Map<String, Assignment> assignmentsForHost(Map<String, Assignment> assignmentMap, String hostname) {
        Map<String, Assignment> ret = new HashMap<>();

        assignmentMap.entrySet().stream().filter(assignmentEntry -> assignmentEntry.getValue().get_node_host().values()
                .contains(hostname))
                .forEach(assignmentEntry -> {
                    ret.put(assignmentEntry.getKey(), assignmentEntry.getValue());
                });

        return ret;
    }

    /**
     * Pick out assignments for specific NodeId from all assignments.
     *
     * @param assignmentMap stormId -> assignment map
     * @param nodeId        supervisor node id
     * @return stormId -> assignment map for the node
     */
    static Map<String, Assignment> assignmentsForNodeId(Map<String, Assignment> assignmentMap, String nodeId) {
        Map<String, Assignment> ret = new HashMap<>();

        assignmentMap.entrySet().stream().filter(assignmentEntry -> assignmentEntry.getValue().get_node_host().keySet()

                .contains(nodeId))
                .forEach(assignmentEntry -> {
                    ret.put(assignmentEntry.getKey(), assignmentEntry.getValue());
                });

        return ret;
    }


    /**
     * Notify supervisors/nodes assigned assignments.
     *
     * @param assignments       assignments map for nodes
     * @param service           {@link AssignmentDistributionService} for distributing assignments asynchronous
     * @param nodeHost          node -> host map
     * @param supervisorDetails nodeId -> {@link SupervisorDetails} map
     */
    static void notifySupervisorsAssignments(Map<String, Assignment> assignments,
                                                     AssignmentDistributionService service, Map<String, String> nodeHost,
                                                     Map<String, SupervisorDetails> supervisorDetails,
                                                     StormMetricsRegistry metricsRegistry) {
        for (Entry<String, String> nodeEntry : nodeHost.entrySet()) {
            try {
                String nodeId = nodeEntry.getKey();
                String hostname = nodeEntry.getValue();
                SupervisorAssignments supervisorAssignments = new SupervisorAssignments();
                supervisorAssignments.set_storm_assignment(assignmentsForHost(assignments, hostname));
                SupervisorDetails details = supervisorDetails.get(nodeId);
                Integer serverPort = details != null ? details.getServerPort() : null;
                service.addAssignmentsForNode(nodeId, nodeEntry.getValue(), serverPort, supervisorAssignments, metricsRegistry);
            } catch (Throwable tr1) {
                //just skip when any error happens wait for next round assignments reassign
                LOG.error("Exception when add assignments distribution task for node {}", nodeEntry.getKey());
            }
        }
    }

    static void notifySupervisorsAsKilled(IStormClusterState clusterState, Assignment oldAss,
                                                  AssignmentDistributionService service, StormMetricsRegistry metricsRegistry) {
        Map<String, String> nodeHost = assignmentChangedNodes(oldAss, null);
        notifySupervisorsAssignments(clusterState.assignmentsInfo(), service, nodeHost,
                                     basicSupervisorDetailsMap(clusterState), metricsRegistry);
    }
}
