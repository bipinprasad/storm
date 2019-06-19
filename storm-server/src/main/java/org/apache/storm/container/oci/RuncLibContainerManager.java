/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.storm.container.oci;

import static org.apache.storm.utils.ConfigUtils.FILE_SEPARATOR;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang.StringUtils;
import org.apache.storm.DaemonConfig;
import org.apache.storm.container.cgroup.CgroupUtils;
import org.apache.storm.container.cgroup.core.MemoryCore;
import org.apache.storm.container.oci.OciContainerExecutorConfig.OciLayer;
import org.apache.storm.container.oci.OciContainerExecutorConfig.OciRuntimeConfig.OciLinuxConfig;
import org.apache.storm.container.oci.OciContainerExecutorConfig.OciRuntimeConfig.OciMount;
import org.apache.storm.container.oci.OciContainerExecutorConfig.OciRuntimeConfig.OciProcessConfig;
import org.apache.storm.container.oci.OciContainerExecutorConfig.OciRuntimeConfig;
import org.apache.storm.daemon.supervisor.ClientSupervisorUtils;
import org.apache.storm.daemon.supervisor.ExitCodeCallback;
import org.apache.storm.utils.ConfigUtils;
import org.apache.storm.utils.ObjectReader;
import org.apache.storm.utils.ReflectionUtils;
import org.apache.storm.utils.ServerUtils;
import org.apache.storm.utils.Utils;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

public class RuncLibContainerManager extends OciContainerManager {
    private static final Logger LOG = LoggerFactory.getLogger(RuncLibContainerManager.class);

    private OciImageTagToManifestPluginInterface imageTagToManifestPlugin;
    private OciManifestToResourcesPluginInterface manifestToResourcesPlugin;
    private OciResourcesLocalizerInterface ociResourcesLocalizer;
    private ObjectMapper mapper;
    private int layersToKeep;
    private String seccomp;

    private static final String RESOLV_CONF = "/etc/resolv.conf";
    private static final String HOSTNAME = "/etc/hostname";
    private static final String HOSTS = "/etc/hosts";

    private static final String SQUASHFS_MEDIA_TYPE = "application/vnd.squashfs";

    //CPU CFS (Completely Fair Scheduler) period
    private static final long CPU_CFS_PERIOD_US = 100000;

    private Map<String, Long> workerToContainerPid = new ConcurrentHashMap<>();
    private List<String> watchedWorkers = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void prepare(Map<String, Object> conf) throws IOException {
        super.prepare(conf);

        imageTagToManifestPlugin = chooseImageTagToManifestPlugin();
        imageTagToManifestPlugin.init(conf);

        manifestToResourcesPlugin = chooseManifestToResourcesPlugin();
        manifestToResourcesPlugin.init(conf);

        ociResourcesLocalizer = chooseOciResourcesLocalizer();
        ociResourcesLocalizer.init(conf);

        layersToKeep = ObjectReader.getInt(
                conf.get(DaemonConfig.STORM_OCI_LAYER_MOUNTS_TO_KEEP),
                100
        );

        mapper = new ObjectMapper();

        if (seccompJsonFile != null) {
            seccomp = new String(Files.readAllBytes(Paths.get(seccompJsonFile)));
        }
    }

    private OciImageTagToManifestPluginInterface chooseImageTagToManifestPlugin()
        throws IllegalArgumentException {
        String pluginName = ObjectReader.getString(
                conf.get(DaemonConfig.STORM_OCI_IMAGE_TAG_TO_MANIFEST_PLUGIN)
        );
        LOG.info("imageTag-to-manifest Plugin is: {}", pluginName);
        return ReflectionUtils.newInstance(pluginName);
    }

    private OciManifestToResourcesPluginInterface chooseManifestToResourcesPlugin()
        throws IllegalArgumentException {
        String pluginName = ObjectReader.getString(
                conf.get(DaemonConfig.STORM_OCI_MANIFEST_TO_RESOURCES_PLUGIN)
        );
        LOG.info("manifest to resource Plugin is: {}", pluginName);
        return ReflectionUtils.newInstance(pluginName);
    }

    private OciResourcesLocalizerInterface chooseOciResourcesLocalizer()
        throws IllegalArgumentException {
        String pluginName = ObjectReader.getString(
                conf.get(DaemonConfig.STORM_OCI_RESOURCES_LOCALIZER)
        );
        LOG.info("oci resource localizer is: {}", pluginName);
        return ReflectionUtils.newInstance(pluginName);
    }

    //the container process ID in the process namespace of the host.
    private String containerPidFile(String workerId) {
        return ConfigUtils.workerRoot(conf, workerId) + FILE_SEPARATOR + "container.pid";
    }

    @Override
    public void launchWorkerProcess(String user, String topologyId, int port, String workerId,
                                    List<String> command, Map<String, String> env, String logPrefix,
                                    ExitCodeCallback processExitCallback, File targetDir) throws IOException {
        // get image name and validate
        String imageName = getImageName(env);
        checkImageInWhitelist(imageName);

        //get manifest
        ImageManifest manifest = imageTagToManifestPlugin.getManifestFromImageTag(imageName);
        LOG.debug("workerId {}: Got manifest: {}", workerId, manifest.toString());

        //get layers metadata
        OciResource configResource = manifestToResourcesPlugin.getConfigResource(manifest);
        LOG.info("workerId {}: Got config metadata: {}", workerId, configResource.toString());

        saveRuncYaml(topologyId, port, imageName, configResource);

        List<OciResource> layersResource = manifestToResourcesPlugin.getLayerResources(manifest);
        LOG.info("workerId {}: Got layers metadata: {}", workerId, layersResource.toString());

        //localize resource
        String configLocalPath = ociResourcesLocalizer.localize(configResource);

        List<String> ociEnv = new ArrayList<>();
        List<String> args = new ArrayList<>();

        ArrayList<OciLayer> layers = new ArrayList<>();

        File file = new File(configLocalPath);
        //extract env
        List<String> imageEnv = extractImageEnv(file);
        if (imageEnv != null && !imageEnv.isEmpty()) {
            ociEnv.addAll(imageEnv);
        }
        for (Map.Entry<String,String> entry: env.entrySet()) {
            ociEnv.add(entry.getKey() + "=" + entry.getValue());
        }
        LOG.debug("workerId {}: ociEnv: {}", workerId, ociEnv);

        //extract entrypoint
        List<String> entrypoint = extractImageEntrypoint(file);
        if (entrypoint != null && !entrypoint.isEmpty()) {
            args.addAll(entrypoint);
        }
        LOG.debug("workerId {}: args: {}", workerId, args);

        //localize layers
        List<String> layersLocalPath = ociResourcesLocalizer.localize((layersResource));
        //compose layers
        for (String layerLocalPath : layersLocalPath) {
            OciLayer layer = new OciLayer(SQUASHFS_MEDIA_TYPE, layerLocalPath);
            layers.add(layer);
        }
        LOG.debug("workerId {}: layers: {}", workerId, layers);
        ArrayList<OciMount> mounts = new ArrayList<>();
        setContainerMounts(mounts, topologyId, workerId, port);
        LOG.debug("workerId {}: mounts: {}", workerId, mounts);

        //calculate the cpusQuotas based on CPU_CFS_PERIOD and assigned CPU
        Long cpusQuotas = null;
        if (workerToCpu.containsKey(workerId)) {
            cpusQuotas = workerToCpu.get(workerId) * CPU_CFS_PERIOD_US;
        }

        Long memoryInBytes = null;
        if (workerToMemoryMB.containsKey(workerId)) {
            memoryInBytes =  workerToMemoryMB.get(workerId) * 1024 * 1024L;
        }
        LOG.info("workerId {}: memoryInBytes set to {}; cpusQuotas set to {}", workerId, memoryInBytes, cpusQuotas);

        //<workerRoot>/<workerId>
        String workerDir = targetDir.getAbsolutePath();
        String workerScriptPath = ServerUtils.writeScript(workerDir, command, env, "0027");

        args.add("bash");
        args.add(workerScriptPath);

        //set container ID to worker ID
        String containerId = workerId;
        //The container PID (on the host) will be written to this file.
        String containerPidFilePath = containerPidFile(containerId);

        OciProcessConfig processConfig = createOciProcessConfig(workerDir, ociEnv, args);

        OciLinuxConfig linuxConfig = createOciLinuxConfig(
                        cpusQuotas, memoryInBytes,
                        cgroupParent + "/" + containerId, seccomp, workerId
                );

        OciRuntimeConfig ociRuntimeConfig = new OciRuntimeConfig(null, mounts,
            processConfig, null, null, null, linuxConfig);

        OciContainerExecutorConfig ociContainerExecutorConfig =
            createOciContainerExecutorConfig(user, containerId, workerId, containerPidFilePath,
                workerScriptPath, null,
                null, null, layers, ociRuntimeConfig);

        //launch the container using worker-launcher
        String executorConfigToJsonFile = writeOciExecutorConfigToJsonFile(mapper, ociContainerExecutorConfig, workerDir);
        LOG.info("workerId {}: oci-config.json file path: {}", workerId, executorConfigToJsonFile);

        List<String> cmdArgs = Arrays.asList(CmdType.RUN_OCI_CONTAINER.toString(), workerDir, executorConfigToJsonFile);

        ClientSupervisorUtils.processLauncher(conf, user, null, cmdArgs, env,
            logPrefix, null, targetDir);

        //check if this process exits.
        Long monitorFreqMs = ObjectReader.getInt(conf.get(DaemonConfig.SUPERVISOR_MONITOR_FREQUENCY_SECS)) * 1000L;

        //Add to the watched list
        LOG.debug("Adding {} to the watchedWorkers list", workerId);
        watchedWorkers.add(workerId);

        Utils.asyncLoop(new Callable<Long>() {
            public Long call()  {
                //Check if this worker is still being watched
                if (!watchedWorkers.contains(workerId)) {
                    LOG.info("{} is not in the watchedWorkers list. Stop watching it", workerId);
                    return null; //stop here.
                }
                Long pid = getContainerPid(workerId);
                LOG.debug("checking container {}, pid {}", workerId, pid);
                //do nothing if pid is null.
                if (pid != null) {
                    try {
                        if (!ServerUtils.isProcessAlive(pid, user)) {
                            LOG.debug("processExitCallback returned for container {}", workerId);
                            processExitCallback.call(0);
                            return null; //stop here.
                        }
                    } catch (IOException e) {
                        //ignore
                        LOG.debug("{}", e);
                    }
                }
                return monitorFreqMs; // sleep, then re-run.
                }
            }, "CheckContainerAlive_SLOT_" + port, null);
    }

    // save runc.yaml in artifacts dir so we can track which image the worker was launched with
    private void saveRuncYaml(String topologyId, int port, String imageName, OciResource configResource) {
        String fname = ConfigUtils.workerArtifactsRoot(conf, topologyId, port) + FILE_SEPARATOR + "runc.yaml";
        File file = new File(fname);
        DumperOptions options = new DumperOptions();
        options.setIndent(2);
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);
        Map<String, Object> data = new HashMap<>();
        data.put("imageName", imageName);
        data.put("manifest", configResource.getFileName());
        data.put("configPath", configResource.getPath());
        try (Writer writer = new FileWriter(file)) {
            yaml.dump(data, writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String writeOciExecutorConfigToJsonFile(ObjectMapper mapper, OciContainerExecutorConfig ociContainerExecutorConfig,
                                     String workerDir) throws IOException {
        File cmdDir = new File(workerDir);
        if (!cmdDir.exists()) {
            throw new IOException(workerDir + " doesn't exist");
        }

        File commandFile = new File(cmdDir + "/oci-config.json");
        mapper.writeValue(commandFile, ociContainerExecutorConfig);

        return commandFile.getAbsolutePath();
    }

    //TODO: to add more
    private void setContainerMounts(ArrayList<OciMount> mounts, String topologyId, String workerId, Integer port) throws IOException {
        //read-only bindmounts need to be added before read-write bindmounts otherwise read-write bindmounts may be overridden.
        for (String readonlyMount : readonlyBindmounts) {
            addOciMountLocation(mounts, readonlyMount, readonlyMount, false, false);
        }

        for (String readwriteMount : readwriteBindmounts) {
            addOciMountLocation(mounts, readwriteMount, readwriteMount, false, true);
        }

        addOciMountLocation(mounts, RESOLV_CONF, RESOLV_CONF, false, false);
        addOciMountLocation(mounts, HOSTNAME, HOSTNAME, false, false);
        addOciMountLocation(mounts, HOSTS, HOSTS, false, false);
        addOciMountLocation(mounts, nscdPath, nscdPath, false, false);
        addOciMountLocation(mounts, stormHome, stormHome, false, false);
        addOciMountLocation(mounts, cgroupRootPath, cgroupRootPath, false, false);

        //set of locations to be bind mounted
        String supervisorLocalDir = ConfigUtils.supervisorLocalDir(conf);
        addOciMountLocation(mounts, supervisorLocalDir, supervisorLocalDir, false, false);

        String workerRootDir = ConfigUtils.workerRoot(conf, workerId);
        addOciMountLocation(mounts, workerRootDir, workerRootDir, false, true);

        String workerArtifactsRoot = ConfigUtils.workerArtifactsRoot(conf, topologyId, port);
        addOciMountLocation(mounts, workerArtifactsRoot, workerArtifactsRoot, false, true);

        String workerUserFile = ConfigUtils.workerUserFile(conf, workerId);
        addOciMountLocation(mounts, workerUserFile, workerUserFile, false, true);

        String sharedByTopologyTmpDir = ConfigUtils.sharedByTopologyTmpDir(conf, topologyId);
        addOciMountLocation(mounts, sharedByTopologyTmpDir, TMP_DIR, false, true);
    }

    private List<String> extractImageEnv(File config) throws IOException {
        JsonNode node = mapper.readTree(config);
        JsonNode envNode = node.path("config").path("Env");
        if (envNode.isMissingNode()) {
            return null;
        }
        return mapper.readValue(envNode, List.class);
    }

    private List<String> extractImageEntrypoint(File config) throws IOException {
        JsonNode node = mapper.readTree(config);
        JsonNode entrypointNode = node.path("config").path("Entrypoint");
        if (entrypointNode.isMissingNode()) {
            return null;
        }
        return mapper.readValue(entrypointNode, List.class);
    }

    private OciContainerExecutorConfig createOciContainerExecutorConfig(
        String username, String containerId, String applicationId, String pidFile,
        String containerScriptPath, String containerCredentialsPath,
        List<String> localDirs, List<String> logDirs,
        List<OciLayer> layers, OciRuntimeConfig ociRuntimeConfig) {

        return new OciContainerExecutorConfig(username, containerId, applicationId,
            pidFile, containerScriptPath, containerCredentialsPath,
            localDirs, logDirs, layers, layersToKeep, ociRuntimeConfig);
    }

    private OciProcessConfig createOciProcessConfig(String cwd,
                                                    List<String> env, List<String> args) {
        return new OciProcessConfig(false, null, cwd, env,
            args, null, null, null, true, 0, null, null);
    }

    private OciLinuxConfig createOciLinuxConfig(Long cpusQuotas, Long memInBytes,
                                                String cgroupsPath, String seccomp, String workerId) {
        OciLinuxConfig.Resources.Cpu cgroupCpu = null;

        if (cpusQuotas != null) {
            cgroupCpu = new OciLinuxConfig.Resources.Cpu(0, cpusQuotas, CPU_CFS_PERIOD_US, 0, 0,
                null, null);

            if (workerToCores.containsKey(workerId)) {
                cgroupCpu.setCpus(StringUtils.join(workerToCores.get(workerId), ","));
                cgroupCpu.setMems(workerToMemoryZone.get(workerId));
            }
        }

        OciLinuxConfig.Resources.Memory cgroupMem = null;
        if (memInBytes != null) {
            cgroupMem = new OciLinuxConfig.Resources.Memory(memInBytes, 0, 0, 0, 0, 0, false);
        }

        OciLinuxConfig.Resources cgroupResources =
            new OciLinuxConfig.Resources(null, cgroupMem, cgroupCpu, null, null, null,
                null, null);

        return new OciLinuxConfig(null, null, null, null,
            cgroupsPath, cgroupResources, null, null, seccomp, null, null,
            null, null);
    }

    private void addOciMountLocation(List<OciMount> mounts, String srcPath,
                                     String dstPath, boolean createSource, boolean isReadWrite) throws IOException {
        if (!createSource) {
            boolean sourceExists = new File(srcPath).exists();
            if (!sourceExists) {
                throw new IOException("SourcePath " + srcPath + " doesn't exit");
            }
        }

        ArrayList<String> options = new ArrayList<>();
        if (isReadWrite) {
            options.add("rw");
        } else {
            options.add("ro");
        }
        options.add("rbind");
        options.add("rprivate");
        mounts.add(new OciMount(dstPath, "bind", srcPath, options));
    }

    @Override
    public long getMemoryUsage(String user, String workerId) throws IOException {
        // "/sys/fs/cgroup/memory/storm/workerId/"
        String memoryCgroupPath = memoryCgroupRootPath + File.separator  + workerId;
        MemoryCore memoryCore = new MemoryCore(memoryCgroupPath);
        LOG.debug("WorkerId {} : Got memory getPhysicalUsage {} from {}", workerId, memoryCore.getPhysicalUsage(), memoryCgroupPath);
        return memoryCore.getPhysicalUsage();
    }

    @Override
    public void kill(String user, String workerId) throws IOException {
        LOG.info("Killing {}", workerId);
        Long pid = getContainerPid(workerId);
        if (pid != null) {
            signal(pid, 15, user);
        } else {
            LOG.warn("Trying to kill container {} but pidfile is not found", workerId);
        }
    }

    private void signal(long pid, int signal, String user) throws IOException {
        List<String> commands = Arrays.asList("signal", String.valueOf(pid), String.valueOf(signal));
        String logPrefix = "kill -" + signal + " " + pid;
        ClientSupervisorUtils.processLauncherAndWait(conf, user, commands, null, logPrefix);
    }

    @Override
    public void forceKill(String user, String workerId) throws IOException {
        LOG.debug("ForceKilling {}", workerId);
        Long pid = getContainerPid(workerId);
        if (pid != null) {
            signal(pid, 9, user);
        } else {
            LOG.warn("Trying to forceKill container {} but pidfile is not found", workerId);
        }
    }

    // return null if not found.
    private Long getContainerPid(String workerId) {
        Long pid = workerToContainerPid.get(workerId);
        if (pid == null) {
            String containerPidFilePath = containerPidFile(workerId);
            if (!new File(containerPidFilePath).exists()) {
                LOG.warn("{} doesn't exist", containerPidFilePath);
            } else {
                try {
                    pid = Long.parseLong(CgroupUtils.readFileByLine(containerPidFilePath).get(0));
                    workerToContainerPid.put(workerId, pid);
                } catch (IOException e) {
                    LOG.warn("failed to read {}", containerPidFilePath);
                }
            }
        }
        return pid;
    }

    @Override
    public void releaseResourcesForWorker(String workerId) {
        super.releaseResourcesForWorker(workerId);
        workerToContainerPid.remove(workerId);
    }

    /**
     * The container terminates if any process inside the container dies.
     * So we only need to check if the initial process is alive or not.
     * @param user the user that the processes are running as
     * @param workerId the id of the worker to kill
     * @return true if all processes are dead; false otherwise
     * @throws IOException on I/O exception
     */
    @Override
    public boolean areAllProcessesDead(String user, String workerId) throws IOException {
        boolean areAllDead = true;
        Long pid = getContainerPid(workerId);
        if (pid != null) {
            if (!ServerUtils.isProcessAlive(pid, user)) {
                LOG.debug("WorkerId {}: PID {} is dead", workerId, pid);
            } else {
                areAllDead = false;
            }
        }
        LOG.debug("WorkerId {}: Checking areAllProcessesDead: {}", workerId, areAllDead);
        return areAllDead;
    }

    @Override
    public void cleanup(String user, String workerId) throws IOException {
        LOG.debug("clean up worker {}", workerId);
        List<String> commands = Arrays.asList(CmdType.REAP_OCI_CONTAINER.toString(), workerId, String.valueOf(layersToKeep));
        String logPrefix = "Worker Process " + workerId;
        ClientSupervisorUtils.processLauncherAndWait(conf, user, commands, null, logPrefix);

        //remove from the watched list
        LOG.debug("Removing {} from the watchedWorkers list", workerId);
        watchedWorkers.remove(workerId);
    }

    /**
     * Run profiling command in the container.
     * @param user the user that the worker is running as
     * @param workerId the id of the worker
     * @param command the command to run.
     *                The profiler to be used is configured in worker-launcher.cfg.
     * @param env the environment to run the command
     * @param logPrefix the prefix to include in the logs
     * @param targetDir the working directory to run the command in
     * @return true if the command succeeds, false otherwise.
     * @throws IOException on I/O exception
     * @throws InterruptedException if interrupted
     */
    @Override
    public boolean runProfilingCommand(String user, String workerId, List<String> command, Map<String, String> env,
                                       String logPrefix, File targetDir) throws IOException, InterruptedException {
        String workerDir = targetDir.getAbsolutePath();

        String profilingArgs = StringUtils.join(command, " ");

        //run nsenter
        String nsenterScriptPath = writeToCommandFile(workerDir, profilingArgs, "profile");

        Long containerPid = getContainerPid(workerId);
        if (containerPid == null) {
            LOG.error("Counldn't get container PID for the worker {}. Skip profiling", workerId);
            return false;
        }

        List<String> args = Arrays.asList(CmdType.PROFILE_OCI_CONTAINER.toString(), containerPid.toString(), nsenterScriptPath);

        Process process = ClientSupervisorUtils.processLauncher(conf, user, null, args,
            env, logPrefix, null, targetDir);

        process.waitFor();

        int exitCode = process.exitValue();
        LOG.debug("WorkerId {} : exitCode from {}: {}", workerId, CmdType.PROFILE_OCI_CONTAINER.toString(), exitCode);

        return exitCode == 0;
    }

    @Override
    public boolean isResourceManaged() {
        return true;
    }
}
