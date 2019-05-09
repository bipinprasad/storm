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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.storm.Config;
import org.apache.storm.DaemonConfig;
import org.apache.storm.container.ResourceIsolationInterface;
import org.apache.storm.container.cgroup.core.MemoryCore;
import org.apache.storm.utils.ConfigUtils;
import org.apache.storm.utils.ObjectReader;
import org.apache.storm.utils.ServerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class OciContainerManager implements ResourceIsolationInterface {
    private static final Logger LOG = LoggerFactory.getLogger(OciContainerManager.class);

    protected static final String TOPOLOGY_ENV_OCI_IMAGE = "OCI_IMAGE";
    protected static final String OCI_IMAGE_PATTERN =
        "^(([a-zA-Z0-9.-]+)(:\\d+)?/)?([a-z0-9_./-]+)(:[\\w.-]+)?$";
    protected static final Pattern ociImagePattern =
        Pattern.compile(OCI_IMAGE_PATTERN);
    protected String defaultImage;
    protected List<String> allowedImages;
    protected Map<String, Object> conf;
    protected List<String> readonlyBindmounts;
    protected String seccompJsonFile;
    protected String nscdPath;
    protected static final String TMP_DIR = File.separator + "tmp";
    protected String stormHome;
    protected String cgroupRootPath;
    protected String cgroupParent;

    protected String memoryCgroupRootPath;
    protected MemoryCore memoryCoreAtRoot;

    protected Map<String, Integer> workerToCpu = new ConcurrentHashMap<>();
    protected Map<String, Integer> workerToMemoryMB = new ConcurrentHashMap<>();

    @Override
    public void prepare(Map<String, Object> conf) throws IOException {
        this.conf = conf;

        //allowed images can't be null or empty
        allowedImages = ObjectReader.getStrings(conf.get(DaemonConfig.STORM_OCI_ALLOWED_IMAGES));
        if (allowedImages == null || allowedImages.isEmpty()) {
            throw new IllegalArgumentException(DaemonConfig.STORM_OCI_ALLOWED_IMAGES
                + " is empty or not configured. No OCI images are allowed. Please check the configuration.");
        }

        //every image in the whitelist must be valid
        for (String image: allowedImages) {
            if (!ociImagePattern.matcher(image).matches()) {
                throw new IllegalArgumentException(image + " in the list of "
                    + DaemonConfig.STORM_OCI_ALLOWED_IMAGES
                    + " doesn't match " + OCI_IMAGE_PATTERN);
            }
        }

        //default image must be in the whitelist.
        defaultImage = (String) conf.get(DaemonConfig.STORM_OCI_IMAGE);
        if (defaultImage == null || !allowedImages.contains(defaultImage)) {
            throw new IllegalArgumentException(DaemonConfig.STORM_OCI_IMAGE
                + ": " + defaultImage
                + " is not in the list of " + DaemonConfig.STORM_OCI_ALLOWED_IMAGES
                + ": " + allowedImages
                + ". Please check the configuration.");
        }

        readonlyBindmounts = ObjectReader.getStrings(conf.get(DaemonConfig.STORM_OCI_READONLY_BINDMOUNTS));

        seccompJsonFile = (String) conf.get(DaemonConfig.STORM_OCI_SECCOMP_PROFILE);

        nscdPath = ObjectReader.getString(conf.get(DaemonConfig.STORM_OCI_NSCD_DIR));

        stormHome = System.getProperty(ConfigUtils.STORM_HOME);

        cgroupRootPath = ObjectReader.getString(conf.get(Config.STORM_OCI_CGROUP_ROOT));

        cgroupParent = ObjectReader.getString(conf.get(DaemonConfig.STORM_OCI_CGROUP_PARENT));

        if (!cgroupParent.startsWith(File.separator)) {
            cgroupParent = File.separator + cgroupParent;
            LOG.warn("{} is not an absolute path. Changing it to be absolute: {}", DaemonConfig.STORM_OCI_CGROUP_PARENT, cgroupParent);
        }

        memoryCgroupRootPath = cgroupRootPath + File.separator + "memory" + File.separator + cgroupParent;
        memoryCoreAtRoot = new MemoryCore(memoryCgroupRootPath);
    }

    @Override
    public void reserveResourcesForWorker(String workerId, Integer workerMemoryMB, Integer workerCpu) {
        // The manually set STORM_WORKER_CGROUP_CPU_LIMIT config on supervisor will overwrite resources assigned by
        // RAS (Resource Aware Scheduler)
        if (conf.get(DaemonConfig.STORM_WORKER_CGROUP_CPU_LIMIT) != null) {
            workerCpu = ((Number) conf.get(DaemonConfig.STORM_WORKER_CGROUP_CPU_LIMIT)).intValue();
        }
        workerToCpu.put(workerId, workerCpu);

        if ((boolean) this.conf.get(DaemonConfig.STORM_CGROUP_MEMORY_ENFORCEMENT_ENABLE)) {
            workerToMemoryMB.put(workerId, workerMemoryMB);
        }
    }

    @Override
    public void releaseResourcesForWorker(String workerId) {
        workerToCpu.remove(workerId);
        workerToMemoryMB.remove(workerId);
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

    /**
     * Get image name from the topology env. If it's not set, get default image.
     * @param env topology env
     * @return the image name
     */
    protected String getImageName(Map<String, String> env) {
        String image = env.get(TOPOLOGY_ENV_OCI_IMAGE);
        if (image == null || image.isEmpty()) {
            image = defaultImage;
        }
        return image;
    }

    /**
     * Check if the image is in the whitelist.
     * @param image the image to check
     */
    protected void checkImageInWhitelist(String image) {
        if (!allowedImages.contains(image)) {
            throw new IllegalArgumentException(TOPOLOGY_ENV_OCI_IMAGE
                + ": " + image
                + " specified in " + Config.TOPOLOGY_ENVIRONMENT
                + " is not in the list of " + DaemonConfig.STORM_OCI_ALLOWED_IMAGES
                + ": " + allowedImages
                + ". Please check your configuration.");
        }
    }


    protected String commandFilePath(String dir, String commandTag) {
        return dir + File.separator + commandTag + ".sh";
    }

    protected String writeToCommandFile(String workerDir, String command, String commandTag) throws IOException {
        String scriptPath = commandFilePath(workerDir, commandTag);
        try (BufferedWriter out = new BufferedWriter(new FileWriter(scriptPath))) {
            out.write(command);
        }
        LOG.debug("command : {}; location: {}", command, scriptPath);
        return scriptPath;
    }

    protected enum CmdType {
        LAUNCH_DOCKER_CONTAINER("launch-docker-container"),
        RUN_DOCKER_CMD("run-docker-cmd"),
        PROFILE_DOCKER_CONTAINER("profile-docker-container"),
        RUN_OCI_CONTAINER("run-oci-container"),
        REAP_OCI_CONTAINER("reap-oci-container"),
        PROFILE_OCI_CONTAINER("profile-oci-container");

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
