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

package org.apache.storm.container.docker;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.storm.Config;
import org.apache.storm.DaemonConfig;
import org.apache.storm.container.ResourceIsolationInterface;
import org.apache.storm.container.cgroup.core.MemoryCore;
import org.apache.storm.daemon.supervisor.ClientSupervisorUtils;
import org.apache.storm.daemon.supervisor.ExitCodeCallback;
import org.apache.storm.shade.com.google.common.io.Files;
import org.apache.storm.utils.ConfigUtils;
import org.apache.storm.utils.ObjectReader;
import org.apache.storm.utils.ServerUtils;
import org.apache.storm.utils.ShellCommandRunnerImpl;
import org.apache.storm.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * For security, we can launch worker processes inside the docker container.
 * This class manages the interaction with docker containers including launching, stopping, profiling and etc.
 */
public class DockerManager implements ResourceIsolationInterface {
    private static final Logger LOG = LoggerFactory.getLogger(DockerManager.class);
    private static final String TOPOLOGY_ENV_DOCKER_IMAGE = "DOCKER_IMAGE";
    private static final String DOCKER_IMAGE_PATTERN =
        "^(([a-zA-Z0-9.-]+)(:\\d+)?/)?([a-z0-9_./-]+)(:[\\w.-]+)?$";
    private static final Pattern dockerImagePattern =
        Pattern.compile(DOCKER_IMAGE_PATTERN);
    private String defaultDockerImage;
    private List<String> allowedDockerImages;
    private final String networkType = "host";
    private String cgroupParent;
    private String memoryCgroupRootPath;
    private String cgroupRootPath;
    private String nscdPath;
    private Map<String, Object> conf;
    private Map<String, Integer> workerToCpu = new HashMap<>();
    private Map<String, Integer> workerToMemoryMb = new HashMap<>();
    private Map<String, String> workerToCid = new HashMap<>();
    private MemoryCore memoryCoreAtRoot;
    private String seccompJsonFile;
    private String stormHome;
    private static final String TMP_DIR = File.separator + "tmp";
    private List<String> readonlyBindmounts;

    @Override
    public void prepare(Map<String, Object> conf) throws IOException {
        this.conf = conf;
        //allowed docker images can't be null or empty
        allowedDockerImages = ObjectReader.getStrings(conf.get(DaemonConfig.STORM_DOCKER_ALLOWED_IMAGES));
        if (allowedDockerImages == null || allowedDockerImages.isEmpty()) {
            throw new IllegalArgumentException(DaemonConfig.STORM_DOCKER_ALLOWED_IMAGES
                + " is empty or not configured. No docker images are allowed. Please check the configuration.");
        }

        //every image in the whitelist must be valid
        for (String image: allowedDockerImages) {
            if (!dockerImagePattern.matcher(image).matches()) {
                throw new IllegalArgumentException(image + " in the list of "
                    + DaemonConfig.STORM_DOCKER_ALLOWED_IMAGES
                    + " doesn't match " + DOCKER_IMAGE_PATTERN);
            }
        }

        //default docker image must be in the whitelist.
        defaultDockerImage = (String) conf.get(DaemonConfig.STORM_DOCKER_IMAGE);
        if (defaultDockerImage == null || !allowedDockerImages.contains(defaultDockerImage)) {
            throw new IllegalArgumentException(DaemonConfig.STORM_DOCKER_IMAGE
                + ": " + defaultDockerImage
                + " is not in the list of " + DaemonConfig.STORM_DOCKER_ALLOWED_IMAGES
                + ": " + allowedDockerImages
                + ". Please check the configuration.");
        }

        seccompJsonFile = (String) conf.get(DaemonConfig.STORM_DOCKER_SECCOMP_PROFILE);
        cgroupParent = ObjectReader.getString(conf.get(DaemonConfig.STORM_DOCKER_CGROUP_PARENT));

        if (!cgroupParent.startsWith(File.separator)) {
            cgroupParent = File.separator + cgroupParent;
            LOG.warn("{} is not an absolute path. Changing it to be absolute: {}", DaemonConfig.STORM_DOCKER_CGROUP_PARENT, cgroupParent);
        }

        cgroupRootPath = ObjectReader.getString(conf.get(Config.STORM_DOCKER_CGROUP_ROOT));
        memoryCgroupRootPath = cgroupRootPath + File.separator + "memory" + File.separator + cgroupParent;
        memoryCoreAtRoot = new MemoryCore(memoryCgroupRootPath);

        nscdPath = ObjectReader.getString(conf.get(DaemonConfig.STORM_DOCKER_NSCD_DIR));
        readonlyBindmounts = ObjectReader.getStrings(conf.get(DaemonConfig.STORM_DOCKER_READONLY_BINDMOUNTS));

        stormHome = System.getProperty(ConfigUtils.STORM_HOME);
    }

    @Override
    public void reserveResourcesForWorker(String workerId, Integer workerMemory, Integer workerCpu) {
        // The manually set STORM_WORKER_CGROUP_CPU_LIMIT config on supervisor will overwrite resources assigned by
        // RAS (Resource Aware Scheduler)
        if (conf.get(DaemonConfig.STORM_WORKER_CGROUP_CPU_LIMIT) != null) {
            workerCpu = ((Number) conf.get(DaemonConfig.STORM_WORKER_CGROUP_CPU_LIMIT)).intValue();
        }
        workerToCpu.put(workerId, workerCpu);

        if ((boolean) this.conf.get(DaemonConfig.STORM_CGROUP_MEMORY_ENFORCEMENT_ENABLE)) {
            workerToMemoryMb.put(workerId, workerMemory);
        }
    }

    @Override
    public void releaseResourcesForWorker(String workerId) {
        workerToCpu.remove(workerId);
        workerToMemoryMb.remove(workerId);
        workerToCid.remove(workerId);
    }

    private String[] getGroupIdInfo(String userName)
        throws IOException {
        String[] groupIds;
        try {
            String output = new ShellCommandRunnerImpl().execCommand("id", "--groups", userName);
            groupIds = output.trim().split(" ");
        } catch (IOException e) {
            LOG.error("Can't get group IDs of the user {}", userName);
            throw new IOException(e);
        }
        return groupIds;
    }

    private String getUserIdInfo(String userName) throws IOException {
        String uid = "";
        try {
            uid = new ShellCommandRunnerImpl().execCommand("id", "--user", userName).trim();
        } catch (IOException e) {
            LOG.error("Can't get uid of the user {}", userName);
            throw e;
        }
        return uid;
    }

    @Override
    public void launchWorkerProcess(String user, String topologyId, int port, String workerId, List<String> command, Map<String, String> env,
                                    String logPrefix, ExitCodeCallback processExitCallback, File targetDir) throws IOException {
        String dockerImage = env.get(TOPOLOGY_ENV_DOCKER_IMAGE);
        if (dockerImage == null || dockerImage.isEmpty()) {
            dockerImage = defaultDockerImage;
        } else {
            if (!allowedDockerImages.contains(dockerImage)) {
                throw new IllegalArgumentException(TOPOLOGY_ENV_DOCKER_IMAGE
                    + ": " + dockerImage
                    + " specified in " + Config.TOPOLOGY_ENVIRONMENT
                    + " is not in the list of " + DaemonConfig.STORM_DOCKER_ALLOWED_IMAGES
                    + ": " + allowedDockerImages
                    + ". Please check your configuration.");
            }
        }

        String workerDir = targetDir.getAbsolutePath();

        String uid = getUserIdInfo(user);
        String[] groups = getGroupIdInfo(user);
        String gid = groups[0];
        String dockerUser = uid + ":" + gid;

        DockerRunCommand dockerRunCommand = new DockerRunCommand(workerId, dockerUser, dockerImage);

        //set of locations to be bind mounted
        String workerRootDir = ConfigUtils.workerRoot(conf, workerId);
        String workerArtifactsRoot = ConfigUtils.workerArtifactsRoot(conf, topologyId, port);
        String workerUserFile = ConfigUtils.workerUserFile(conf, workerId);
        String sharedByTopologyTmpDir = ConfigUtils.sharedByTopologyTmpDir(conf, topologyId);

        // Theoretically we only need to mount ConfigUtils.supervisorStormDistRoot directory.
        // But if supervisorLocalDir is not mounted, the worker will try to create it and fail.
        String supervisorLocalDir = ConfigUtils.supervisorLocalDir(conf);

        dockerRunCommand.detachOnRun()
            .setNetworkType(networkType)
            //The whole file system of the container will be read-only except specific read-write bind mounts
            .setReadonly()
            .addReadOnlyMountLocation(cgroupRootPath, cgroupRootPath, false)
            .addReadOnlyMountLocation(stormHome, stormHome, false)
            .addReadOnlyMountLocation(supervisorLocalDir, supervisorLocalDir, false)
            .addMountLocation(workerRootDir, workerRootDir, false)
            .addMountLocation(workerArtifactsRoot, workerArtifactsRoot, false)
            .addMountLocation(workerUserFile, workerUserFile, false)
            //nscd must be running so that profiling can work properly
            .addMountLocation(nscdPath, nscdPath, false)
            //This is to make /tmp directory in container writable. This is very important.
            // For example
            // 1. jvm needs to write to /tmp/hsperfdata_<user> directory so that jps can work
            // 2. jstack needs to create a socket under /tmp directory.
            //Otherwise profiling will not work properly.
            .addMountLocation(sharedByTopologyTmpDir, TMP_DIR, false)
            //a list of read-only bind mount locations
            .addAllReadOnlyMountLocations(readonlyBindmounts, false);

        dockerRunCommand.setCGroupParent(cgroupParent)
            .groupAdd(groups)
            .setContainerWorkDir(workerDir)
            .setCidFile(dockerCidFilePath(workerId))
            .setCapabilities(Collections.emptySet())
            .setNoNewPrivileges();

        if (seccompJsonFile != null) {
            dockerRunCommand.setSeccompProfile(seccompJsonFile);
        }

        if (workerToCpu.containsKey(workerId)) {
            dockerRunCommand.setCpus(workerToCpu.get(workerId) / 100.0);
        }

        if (workerToMemoryMb.containsKey(workerId)) {
            dockerRunCommand.setMemoryMb(workerToMemoryMb.get(workerId));
        }

        dockerRunCommand.setOverrideCommandWithArgs(Arrays.asList("bash", ServerUtils.writeScript(workerDir, command, env, "0027")));

        //run docker-run command and launch container in background (-d option).
        runDockerCommandWaitFor(conf, user, CmdType.LAUNCH_DOCKER_CONTAINER,
            dockerRunCommand.getCommandWithArguments(), null, logPrefix, null, targetDir);

        //docker-wait for the container in another thread. processExitCallback will get the container's exit code.
        Utils.asyncLoop(new Callable<Long>() {
            public Long call() throws IOException {
                DockerWaitCommand dockerWaitCommand = new DockerWaitCommand(workerId);
                try {
                    runDockerCommandWaitFor(conf, user, CmdType.RUN_DOCKER_CMD,
                        dockerWaitCommand.getCommandWithArguments(), null, logPrefix, processExitCallback, targetDir);
                } catch (IOException e) {
                    LOG.error("IOException on running docker wait command:", e);
                    throw e;
                }
                return null; // Run only once.
            }
        });

    }

    //Get the container ID of the worker
    private String getCID(String workerId) throws IOException {
        String cid = workerToCid.get(workerId);
        if (cid == null) {
            File cidFile = new File(dockerCidFilePath(workerId));
            if (cidFile.exists()) {
                List<String> lines = Files.readLines(cidFile, Charset.defaultCharset());
                if (lines.isEmpty()) {
                    LOG.error("cid file {} is empty.", cidFile);
                } else {
                    cid = lines.get(0);
                }
            } else {
                LOG.error("cid file {} doesn't exist.", cidFile);
            }

            if (cid == null) {
                LOG.error("Couldn't get container id of the worker {}", workerId);
                throw new IOException("Couldn't get container id of the worker " + workerId);
            } else {
                workerToCid.put(workerId, cid);
            }
        }
        return cid;
    }

    @Override
    public long getMemoryUsage(String user, String workerId) throws IOException {
        String memoryCgroupPath = memoryCgroupRootPath + File.separator + getCID(workerId);
        MemoryCore memoryCore = new MemoryCore(memoryCgroupPath);
        return memoryCore.getPhysicalUsage();
    }

    @Override
    public long getSystemFreeMemoryMb() throws IOException {
        long rootCgroupLimitFree = Long.MAX_VALUE;

        try {
            //For cgroups no limit is max long.
            long limit = memoryCoreAtRoot.getPhysicalUsageLimit();
            long used = memoryCoreAtRoot.getMaxPhysicalUsage();
            rootCgroupLimitFree = (limit - used) / 1024 / 1024;
        } catch (FileNotFoundException e) {
            //Ignored if cgroups is not setup don't do anything with it
        }

        return Long.min(rootCgroupLimitFree, ServerUtils.getMemInfoFreeMb());
    }

    @Override
    public void kill(String user, String workerId) throws IOException {
        String workerDir = ConfigUtils.workerRoot(conf, workerId);
        DockerStopCommand dockerStopCommand = new DockerStopCommand(workerId);
        runDockerCommandWaitFor(conf, user, CmdType.RUN_DOCKER_CMD, dockerStopCommand.getCommandWithArguments(),
            null, null, null, new File(workerDir));

        DockerRmCommand dockerRmCommand = new DockerRmCommand(workerId);
        runDockerCommandWaitFor(conf, user, CmdType.RUN_DOCKER_CMD, dockerRmCommand.getCommandWithArguments(),
            null, null, null, new File(workerDir));
    }

    @Override
    public void forceKill(String user, String workerId) throws IOException {
        String workerDir = ConfigUtils.workerRoot(conf, workerId);
        DockerRmCommand dockerRmCommand = new DockerRmCommand(workerId);
        dockerRmCommand.withForce();
        runDockerCommandWaitFor(conf, user, CmdType.RUN_DOCKER_CMD, dockerRmCommand.getCommandWithArguments(),
            null, null, null, new File(workerDir));
    }

    /**
     * Currently it only checks if the container is alive.
     * If the worker process inside the container dies, the container will exit.
     * So we only need to check if the container is running to know if the worker process is still alive.
     *
     * @param user     the user of the processes
     * @param workerId the id of the worker to kill
     * @return true if all processes are dead
     * @throws IOException on I/O exception
     */
    @Override
    public boolean areAllProcessesDead(String user, String workerId) throws IOException {
        String workerDir = ConfigUtils.workerRoot(conf, workerId);
        DockerPsCommand dockerPsCommand = new DockerPsCommand();
        dockerPsCommand.withNameFilter(workerId);
        dockerPsCommand.withQuietOption();

        String command = dockerPsCommand.getCommandWithArguments();

        Process p = runDockerCommand(conf, user, CmdType.RUN_DOCKER_CMD, command,
            null, null, null, new File(workerDir));

        try {
            p.waitFor();
        } catch (InterruptedException e) {
            LOG.error("running docker command is interrupted", e);
        }

        if (p.exitValue() != 0) {
            String errorMessage = "The exitValue of the docker command [" + command + "] is non-zero: " + p.exitValue();
            LOG.error(errorMessage);
            throw new IOException(errorMessage);
        }

        String output = IOUtils.toString(p.getInputStream(), Charset.forName("UTF-8"));
        LOG.debug("The output of the docker command [{}] is: [{}]; the exitValue is {}", command, output, p.exitValue());
        //The output might include some things else
        //The real output of the docker-ps command is either empty or the container's short ID
        output = output.trim();
        String[] lines = output.split("\n");
        if (lines.length == 0) {
            //output is empty, the container is not running
            return true;
        }
        String lastLine = lines[lines.length - 1].trim();
        if (lastLine.isEmpty()) {
            return true;
        }

        try {
            String containerId = getCID(workerId);
            return !containerId.startsWith(lastLine);
        } catch (IOException e) {
            LOG.error("Failed to find Container ID for {}, assuming dead", workerId, e);
            return true;
        }
    }

    @Override
    public boolean isResourceManaged() {
        return true;
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
        String nsenterScriptPath = writeToCommandFile(workerDir, profilingArgs);

        List<String> args = Arrays.asList(CmdType.RUN_NSENTER.toString(), workerId, workerDir, nsenterScriptPath);

        Process process = ClientSupervisorUtils.processLauncher(conf, user, null, args,
            env, logPrefix, null, targetDir);

        process.waitFor();

        int exitCode = process.exitValue();
        LOG.debug("exitCode from nsenter: {}", exitCode);

        return exitCode == 0;
    }

    private String dockerCidFilePath(String workerId) {
        return ConfigUtils.workerRoot(conf, workerId) + File.separator + "container.cid";
    }

    private String commandFilePath(String dir) {
        return dir + File.separator + "command-to-run.sh";
    }

    private String writeToCommandFile(String workerDir, String command) throws IOException {
        String scriptPath = commandFilePath(workerDir);
        try (BufferedWriter out = new BufferedWriter(new FileWriter(scriptPath))) {
            out.write(command);
        }
        LOG.debug("command : {}; location: {}", command, scriptPath);
        return scriptPath;
    }

    /**
     * Run docker command using {@link Config#SUPERVISOR_WORKER_LAUNCHER}.
     *
     * @param conf             the storm conf
     * @param dockerCommand    the docker command to run
     * @param environment      the environment
     * @param logPrefix        the prefix of logs
     * @param exitCodeCallback the exit call back
     * @param targetDir        the working directory
     * @return the Process
     * @throws IOException on I/O exception
     */
    private Process runDockerCommand(Map<String, Object> conf, String user, CmdType cmdType, String dockerCommand,
                                     Map<String, String> environment, final String logPrefix,
                                     final ExitCodeCallback exitCodeCallback, File targetDir) throws IOException {
        String workerDir = targetDir.getAbsolutePath();
        String dockerScriptPath = writeToCommandFile(workerDir, dockerCommand);

        List<String> args = Arrays.asList(cmdType.toString(), workerDir, dockerScriptPath);

        return ClientSupervisorUtils.processLauncher(conf, user, null, args, environment,
            logPrefix, exitCodeCallback, targetDir);
    }

    /**
     * Run docker command using {@link Config#SUPERVISOR_WORKER_LAUNCHER}.
     *
     * @param conf             the storm conf
     * @param dockerCommand    the docker command to run
     * @param environment      the environment
     * @param logPrefix        the prefix of logs
     * @param exitCodeCallback the exit call back
     * @param targetDir        the working directory
     * @return the Process
     * @throws IOException on I/O exception
     */
    private int runDockerCommandWaitFor(Map<String, Object> conf, String user, CmdType cmdType, String dockerCommand,
                                        Map<String, String> environment, final String logPrefix,
                                        final ExitCodeCallback exitCodeCallback, File targetDir) throws IOException {
        Process p = runDockerCommand(conf, user, cmdType, dockerCommand, environment, logPrefix, exitCodeCallback, targetDir);
        try {
            p.waitFor();
        } catch (InterruptedException e) {
            LOG.error("running docker command is interrupted", e);
        }
        return p.exitValue();
    }

    enum CmdType {
        LAUNCH_DOCKER_CONTAINER("launch-docker-container"),
        RUN_DOCKER_CMD("run-docker-cmd"),
        RUN_NSENTER("run-nsenter");

        private final String name;

        CmdType(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }
}