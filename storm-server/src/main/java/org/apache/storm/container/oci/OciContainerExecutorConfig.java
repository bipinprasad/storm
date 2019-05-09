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

import java.util.List;
import java.util.Map;
import org.codehaus.jackson.annotate.JsonRawValue;
import org.codehaus.jackson.map.annotate.JsonSerialize;

@JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
public class OciContainerExecutorConfig {
    private final String version;
    private final String username;
    private final String containerId;
    private final String applicationId;
    private final String pidFile;
    private final String containerScriptPath;
    private final List<OciLayer> layers;
    private final int reapLayerKeepCount;
    private final OciRuntimeConfig ociRuntimeConfig;

    public OciContainerExecutorConfig() {
        this(null, null, null, null, null, null, null, 0, null);
    }

    public OciContainerExecutorConfig(String username,
                                      String containerId, String applicationId,
                                      String pidFile, String containerScriptPath, String containerCredentialsPath,
                                      List<String> localDirs,
                                      List<String> logDirs, List<OciLayer> layers, int reapLayerKeepCount,
                                      OciRuntimeConfig ociRuntimeConfig) {
        this("0.1", username, containerId, applicationId, pidFile,
            containerScriptPath, layers, reapLayerKeepCount, ociRuntimeConfig);
    }

    public OciContainerExecutorConfig(String version, String username,
                                      String containerId, String applicationId,
                                      String pidFile, String containerScriptPath,
                                      List<OciLayer> layers, int reapLayerKeepCount,
                                      OciRuntimeConfig ociRuntimeConfig) {
        this.version = version;
        this.username = username;
        this.containerId = containerId;
        this.applicationId = applicationId;
        this.pidFile = pidFile;
        this.containerScriptPath = containerScriptPath;
        this.layers = layers;
        this.reapLayerKeepCount = reapLayerKeepCount;
        this.ociRuntimeConfig = ociRuntimeConfig;
    }

    public String getVersion() {
        return version;
    }

    public String getUsername() {
        return username;
    }

    public String getContainerId() {
        return containerId;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public String getPidFile() {
        return pidFile;
    }

    public String getContainerScriptPath() {
        return containerScriptPath;
    }

    public List<OciLayer> getLayers() {
        return layers;
    }

    public int getReapLayerKeepCount() {
        return reapLayerKeepCount;
    }

    public OciRuntimeConfig getOciRuntimeConfig() {
        return ociRuntimeConfig;
    }

    @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
    public static class OciLayer {
        private final String mediaType;
        private final String path;

        public OciLayer(String mediaType, String path) {
            this.mediaType = mediaType;
            this.path = path;
        }

        public OciLayer() {
            this(null, null);
        }

        public String getMediaType() {
            return mediaType;
        }

        public String getPath() {
            return path;
        }

        @Override
        public String toString() {
            return "OciLayer{"
                + "mediaType='" + mediaType + '\''
                + ", path='" + path + '\''
                + '}';
        }
    }

    @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
    public static class OciRuntimeConfig {
        private final OciRootConfig root;
        private final List<OciMount> mounts;
        private final OciProcessConfig process;
        private final OciHooksConfig hooks;
        private final OciAnnotationsConfig annotations;
        private final OciLinuxConfig linux;
        private final String hostname;

        public OciRuntimeConfig() {
            this(null, null, null, null, null, null, null);
        }

        public OciRuntimeConfig(OciRootConfig root, List<OciMount> mounts,
                                OciProcessConfig process, String hostname, OciHooksConfig hooks, OciAnnotationsConfig annotations,
                                OciLinuxConfig linux) {
            this.root = root;
            this.mounts = mounts;
            this.process = process;
            this.hostname = hostname;
            this.hooks = hooks;
            this.annotations = annotations;
            this.linux = linux;
        }

        public OciRootConfig getRoot() {
            return root;
        }

        public List<OciMount> getMounts() {
            return mounts;
        }

        public OciProcessConfig getProcess() {
            return process;
        }

        public String getHostname() {
            return hostname;
        }

        public OciHooksConfig getHooks() {
            return hooks;
        }

        public OciAnnotationsConfig getAnnotations() {
            return annotations;
        }

        public OciLinuxConfig getLinux() {
            return linux;
        }

        @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
        static class OciRootConfig {
            private final String path;
            private final boolean readonly;

            public OciRootConfig(String path, boolean readonly) {
                this.path = path;
                this.readonly = readonly;
            }

            public OciRootConfig() {
                this(null, false);
            }

            public String getPath() {
                return path;
            }

            public boolean isReadonly() {
                return readonly;
            }
        }

        @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
        static class OciMount {
            private final String destination;
            private final String type;
            private final String source;
            private final List<String> options;

            public OciMount(String destination, String type, String source, List<String> options) {
                this.destination = destination;
                this.type = type;
                this.source = source;
                this.options = options;
            }

            public OciMount(String destination, String source, List<String> options) {
                this.destination = destination;
                this.type = null;
                this.source = source;
                this.options = options;
            }

            public OciMount() {
                this(null, null, null, null);
            }

            public String getDestination() {
                return destination;
            }

            public String getType() {
                return type;
            }

            public String getSource() {
                return source;
            }

            public List<String> getOptions() {
                return options;
            }

            @Override
            public String toString() {
                return "OciMount{"
                    + "destination='" + destination + '\''
                    + ", type='" + type + '\''
                    + ", source='" + source + '\''
                    + ", options=" + options + '}';
            }
        }


        @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
        static class OciProcessConfig {
            private final boolean terminal;
            private final ConsoleSize consoleSize;
            private final String cwd;
            private final List<String> env;
            private final List<String> args;
            private final RLimits rlimits;
            private final String apparmorProfile;
            private final Capabilities capabilities;
            private final boolean noNewPrivileges;
            private final int oomScoreAdj;
            private final String selinuxLabel;
            private final User user;

            public OciProcessConfig(boolean terminal, ConsoleSize consoleSize, String cwd,
                                    List<String> env, List<String> args, RLimits rlimits,
                                    String apparmorProfile, Capabilities capabilities, boolean noNewPrivileges,
                                    int oomScoreAdj, String selinuxLabel, User user) {
                this.terminal = terminal;
                this.consoleSize = consoleSize;
                this.cwd = cwd;
                this.env = env;
                this.args = args;
                this.rlimits = rlimits;
                this.apparmorProfile = apparmorProfile;
                this.capabilities = capabilities;
                this.noNewPrivileges = noNewPrivileges;
                this.oomScoreAdj = oomScoreAdj;
                this.selinuxLabel = selinuxLabel;
                this.user = user;
            }

            public OciProcessConfig() {
                this(false, null, null, null, null, null, null, null, true, 0, null, null);
            }

            public boolean isTerminal() {
                return terminal;
            }

            public ConsoleSize getConsoleSize() {
                return consoleSize;
            }

            public String getCwd() {
                return cwd;
            }

            public List<String> getEnv() {
                return env;
            }

            public List<String> getArgs() {
                return args;
            }

            public RLimits getRlimits() {
                return rlimits;
            }

            public String getApparmorProfile() {
                return apparmorProfile;
            }

            public Capabilities getCapabilities() {
                return capabilities;
            }

            public boolean isNoNewPrivileges() {
                return noNewPrivileges;
            }

            public int getOomScoreAdj() {
                return oomScoreAdj;
            }

            public String getSelinuxLabel() {
                return selinuxLabel;
            }

            public User getUser() {
                return user;
            }

            @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
            static class ConsoleSize {
                private final int height;
                private final int width;

                public ConsoleSize(int height, int width) {
                    this.height = height;
                    this.width = width;
                }

                public ConsoleSize() {
                    this(0, 0);
                }

                public int getHeight() {
                    return height;
                }

                public int getWidth() {
                    return width;
                }
            }

            @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
            static class RLimits {
                private final String type;
                private final long soft;
                private final long hard;

                public RLimits(String type, long soft, long hard) {
                    this.type = type;
                    this.soft = soft;
                    this.hard = hard;
                }

                public RLimits() {
                    this(null, 0, 0);
                }

                public String getType() {
                    return type;
                }

                public long getSoft() {
                    return soft;
                }

                public long getHard() {
                    return hard;
                }
            }

            @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
            static class Capabilities {
                private final List<String> effective;
                private final List<String> bounding;
                private final List<String> inheritable;
                private final List<String> permitted;
                private final List<String> ambient;

                public Capabilities(List<String> effective, List<String> bounding,
                                    List<String> inheritable, List<String> permitted,
                                    List<String> ambient) {
                    this.effective = effective;
                    this.bounding = bounding;
                    this.inheritable = inheritable;
                    this.permitted = permitted;
                    this.ambient = ambient;
                }

                public Capabilities() {
                    this(null, null, null, null, null);
                }

                public List<String> getEffective() {
                    return effective;
                }

                public List<String> getBounding() {
                    return bounding;
                }

                public List<String> getInheritable() {
                    return inheritable;
                }

                public List<String> getPermitted() {
                    return permitted;
                }

                public List<String> getAmbient() {
                    return ambient;
                }

            }

            static class User {
                private final int uid;
                private final int gid;
                private final int[] additionalGids;

                public User(int uid, int gid, int[] additionalGids) {
                    this.uid = uid;
                    this.gid = gid;
                    this.additionalGids = additionalGids;
                }

                public User() {
                    this(0, 0, null);
                }
            }
        }

        @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
        static class OciHooksConfig {
            private final List<HookType> prestart;
            private final List<HookType> poststart;
            private final List<HookType> poststop;

            public OciHooksConfig(List<HookType> prestart, List<HookType> poststart, List<HookType> poststop) {
                this.prestart = prestart;
                this.poststart = poststart;
                this.poststop = poststop;
            }

            public OciHooksConfig() {
                this(null, null, null);
            }

            public List<HookType> getPrestart() {
                return prestart;
            }

            public List<HookType> getPoststart() {
                return poststart;
            }

            public List<HookType> getPoststop() {
                return poststop;
            }

            @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
            static class HookType {
                private final String path;
                private final List<String> args;
                private final List<String> env;
                private final int timeout;

                public HookType(String path, List<String> args, List<String> env, int timeout) {
                    this.path = path;
                    this.args = args;
                    this.env = env;
                    this.timeout = timeout;
                }

                public HookType() {
                    this(null, null, null, 0);
                }

                public String getPath() {
                    return path;
                }

                public List<String> getArgs() {
                    return args;
                }

                public List<String> getEnv() {
                    return env;
                }

                public int getTimeout() {
                    return timeout;
                }

            }
        }

        static class OciAnnotationsConfig {
            Map<String, String> annotations;

            public OciAnnotationsConfig(Map<String, String> annotations) {
                this.annotations = annotations;
            }

            public OciAnnotationsConfig() {
                this(null);
            }

        }

        @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
        static class OciLinuxConfig {
            private final List<Namespace> namespaces;
            private final List<IdMapping> uidMappings;
            private final List<IdMapping> gidMappings;
            private final List<Device> devices;
            private final String cgroupsPath;
            private final Resources resources;
            private final IntelRdt intelRdt;
            private final Sysctl sysctl;
            @JsonRawValue
            private final String seccomp;
            private final String rootfsPropagation;
            private final List<String> maskedPaths;
            private final List<String> readonlyPaths;
            private final String mountLabel;

            public OciLinuxConfig(List<Namespace> namespaces, List<IdMapping> uidMappings,
                                  List<IdMapping> gidMappings, List<Device> devices,
                                  String cgroupsPath, Resources resources, IntelRdt intelRdt,
                                  Sysctl sysctl, String seccomp, String rootfsPropagation,
                                  List<String> maskedPaths, List<String> readonlyPaths,
                                  String mountLabel) {
                this.namespaces = namespaces;
                this.uidMappings = uidMappings;
                this.gidMappings = gidMappings;
                this.devices = devices;
                this.cgroupsPath = cgroupsPath;
                this.resources = resources;
                this.intelRdt = intelRdt;
                this.sysctl = sysctl;
                this.seccomp = seccomp;
                this.rootfsPropagation = rootfsPropagation;
                this.maskedPaths = maskedPaths;
                this.readonlyPaths = readonlyPaths;
                this.mountLabel = mountLabel;
            }

            public OciLinuxConfig() {
                this(null, null, null, null, null, null, null, null, null, null, null, null, null);
            }

            public List<Namespace> getNamespaces() {
                return namespaces;
            }

            public List<IdMapping> getUidMappings() {
                return uidMappings;
            }

            public List<IdMapping> getGidMappings() {
                return gidMappings;
            }

            public List<Device> getDevices() {
                return devices;
            }

            public String getCgroupsPath() {
                return cgroupsPath;
            }

            public Resources getResources() {
                return resources;
            }

            public IntelRdt getIntelRdt() {
                return intelRdt;
            }

            public Sysctl getSysctl() {
                return sysctl;
            }

            public String getSeccomp() {
                return seccomp;
            }

            public String getRootfsPropagation() {
                return rootfsPropagation;
            }

            public List<String> getMaskedPaths() {
                return maskedPaths;
            }

            public List<String> getReadonlyPaths() {
                return readonlyPaths;
            }

            public String getMountLabel() {
                return mountLabel;
            }

            static class Namespace {
                private final String type;
                private final String path;

                public Namespace(String type, String path) {
                    this.type = type;
                    this.path = path;
                }

                public Namespace() {
                    this(null, null);
                }
            }

            @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
            static class IdMapping {
                private final int containerId;
                private final int hostId;
                private final int size;

                public IdMapping(int containerId, int hostId, int size) {
                    this.containerId = containerId;
                    this.hostId = hostId;
                    this.size = size;
                }

                public IdMapping() {
                    this(0, 0, 0);
                }

                public int getContainerId() {
                    return containerId;
                }

                public int getHostId() {
                    return hostId;
                }

                public int getSize() {
                    return size;
                }

            }

            @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
            static class Device {
                private final String type;
                private final String path;
                private final long major;
                private final long minor;
                private final int fileMode;
                private final int uid;
                private final int gid;

                public Device(String type, String path, long major, long minor,
                              int fileMode, int uid, int gid) {
                    this.type = type;
                    this.path = path;
                    this.major = major;
                    this.minor = minor;
                    this.fileMode = fileMode;
                    this.uid = uid;
                    this.gid = gid;
                }

                public Device() {
                    this(null, null, 0, 0, 0, 0, 0);
                }

                public String getType() {
                    return type;
                }

                public String getPath() {
                    return path;
                }

                public long getMajor() {
                    return major;
                }

                public long getMinor() {
                    return minor;
                }

                public int getFileMode() {
                    return fileMode;
                }

                public int getUid() {
                    return uid;
                }

                public int getGid() {
                    return gid;
                }

            }

            @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
            static class Resources {
                private final List<Device> device;
                private final Memory memory;
                private final Cpu cpu;
                private final BlockIo blockIo;
                private final List<HugePageLimits> hugePageLimits;
                private final Network network;
                private final Pid pid;
                private final Rdma rdma;

                public Resources(List<Device> device,
                                 Memory memory, Cpu cpu,
                                 BlockIo blockIo, List<HugePageLimits> hugePageLimits,
                                 Network network, Pid pid,
                                 Rdma rdma) {
                    this.device = device;
                    this.memory = memory;
                    this.cpu = cpu;
                    this.blockIo = blockIo;
                    this.hugePageLimits = hugePageLimits;
                    this.network = network;
                    this.pid = pid;
                    this.rdma = rdma;
                }

                public Resources() {
                    this(null, null, null, null, null, null, null, null);
                }

                public List<Device> getDevice() {
                    return device;
                }

                public Memory getMemory() {
                    return memory;
                }

                public Cpu getCpu() {
                    return cpu;
                }

                public BlockIo getBlockIo() {
                    return blockIo;
                }

                public List<HugePageLimits> getHugePageLimits() {
                    return hugePageLimits;
                }

                public Network getNetwork() {
                    return network;
                }

                public Pid getPid() {
                    return pid;
                }

                public Rdma getRdma() {
                    return rdma;
                }

                @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
                static class Device {
                    private final boolean allow;
                    private final String type;
                    private final long major;
                    private final long minor;
                    private final String access;

                    public Device(boolean allow, String type, long major, long minor, String access) {
                        this.allow = allow;
                        this.type = type;
                        this.major = major;
                        this.minor = minor;
                        this.access = access;
                    }

                    public Device() {
                        this(false, null, 0, 0, null);
                    }

                    public boolean isAllow() {
                        return allow;
                    }

                    public String getType() {
                        return type;
                    }

                    public long getMajor() {
                        return major;
                    }

                    public long getMinor() {
                        return minor;
                    }

                    public String getAccess() {
                        return access;
                    }
                }

                @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
                static class Memory {
                    private final long limit;
                    private final long reservation;
                    private final long swap;
                    private final long kernel;
                    private final long kernelTcp;
                    private final long swappiness;
                    private final boolean disableOomKiller;

                    public Memory(long limit, long reservation, long swap,
                                  long kernel, long kernelTcp, long swappiness,
                                  boolean disableOomKiller) {
                        this.limit = limit;
                        this.reservation = reservation;
                        this.swap = swap;
                        this.kernel = kernel;
                        this.kernelTcp = kernelTcp;
                        this.swappiness = swappiness;
                        this.disableOomKiller = disableOomKiller;
                    }

                    public Memory() {
                        this(0, 0, 0, 0, 0, 0, false);
                    }

                    public long getLimit() {
                        return limit;
                    }

                    public long getReservation() {
                        return reservation;
                    }

                    public long getSwap() {
                        return swap;
                    }

                    public long getKernel() {
                        return kernel;
                    }

                    public long getKernelTcp() {
                        return kernelTcp;
                    }

                    public long getSwappiness() {
                        return swappiness;
                    }

                    public boolean isDisableOomKiller() {
                        return disableOomKiller;
                    }
                }

                @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
                static class Cpu {
                    private final long quota;
                    private final long period;
                    private final long realtimeRuntime;
                    private final long realtimePeriod;
                    private final String cpus;
                    private final String mems;
                    private final long shares;

                    public Cpu(long shares, long quota, long period,
                               long realtimeRuntime, long realtimePeriod,
                               String cpus, String mems) {
                        this.shares = shares;
                        this.quota = quota;
                        this.period = period;
                        this.realtimeRuntime = realtimeRuntime;
                        this.realtimePeriod = realtimePeriod;
                        this.cpus = cpus;
                        this.mems = mems;
                    }

                    public Cpu() {
                        this(0, 0, 0, 0, 0, null, null);
                    }

                    public long getShares() {
                        return shares;
                    }

                    public long getQuota() {
                        return quota;
                    }

                    public long getPeriod() {
                        return period;
                    }

                    public long getRealtimeRuntime() {
                        return realtimeRuntime;
                    }

                    public long getRealtimePeriod() {
                        return realtimePeriod;
                    }

                    public String getCpus() {
                        return cpus;
                    }

                    public String getMems() {
                        return mems;
                    }
                }

                @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
                static class BlockIo {
                    private final int weight;
                    private final int leafWeight;
                    private final List<WeightDevice> weightDevices;
                    private final List<ThrottleDevice> throttleReadBpsDevice;
                    private final List<ThrottleDevice> throttleWriteBpsDevice;
                    private final List<ThrottleDevice> throttleReadIopsDevice;
                    private final List<ThrottleDevice> throttleWriteIopsDevice;

                    public BlockIo(int weight, int leafWeight, List<WeightDevice> weightDevices,
                                   List<ThrottleDevice> throttleReadBpsDevice,
                                   List<ThrottleDevice> throttleWriteBpsDevice,
                                   List<ThrottleDevice> throttleReadIopsDevice,
                                   List<ThrottleDevice> throttleWriteIopsDevice) {
                        this.weight = weight;
                        this.leafWeight = leafWeight;
                        this.weightDevices = weightDevices;
                        this.throttleReadBpsDevice = throttleReadBpsDevice;
                        this.throttleWriteBpsDevice = throttleWriteBpsDevice;
                        this.throttleReadIopsDevice = throttleReadIopsDevice;
                        this.throttleWriteIopsDevice = throttleWriteIopsDevice;
                    }

                    public BlockIo() {
                        this(0, 0, null, null, null, null, null);
                    }

                    public int getWeight() {
                        return weight;
                    }

                    public int getLeafWeight() {
                        return leafWeight;
                    }

                    public List<WeightDevice> getWeightDevices() {
                        return weightDevices;
                    }

                    public List<ThrottleDevice> getThrottleReadBpsDevice() {
                        return throttleReadBpsDevice;
                    }

                    public List<ThrottleDevice> getThrottleWriteBpsDevice() {
                        return throttleWriteBpsDevice;
                    }

                    public List<ThrottleDevice> getThrottleReadIopsDevice() {
                        return throttleReadIopsDevice;
                    }

                    public List<ThrottleDevice> getThrottleWriteIopsDevice() {
                        return throttleWriteIopsDevice;
                    }

                    @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
                    static class WeightDevice {
                        private final long major;
                        private final long minor;
                        private final int weight;
                        private final int leafWeight;

                        public WeightDevice(long major, long minor, int weight, int leafWeight) {
                            this.major = major;
                            this.minor = minor;
                            this.weight = weight;
                            this.leafWeight = leafWeight;
                        }

                        public WeightDevice() {
                            this(0, 0, 0, 0);
                        }

                        public long getMajor() {
                            return major;
                        }

                        public long getMinor() {
                            return minor;
                        }

                        public int getWeight() {
                            return weight;
                        }

                        public int getLeafWeight() {
                            return leafWeight;
                        }
                    }

                    @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
                    static class ThrottleDevice {
                        private final long major;
                        private final long minor;
                        private final long rate;

                        public ThrottleDevice(long major, long minor, long rate) {
                            this.major = major;
                            this.minor = minor;
                            this.rate = rate;
                        }

                        public ThrottleDevice() {
                            this(0, 0, 0);
                        }

                        public long getMajor() {
                            return major;
                        }

                        public long getMinor() {
                            return minor;
                        }

                        public long getRate() {
                            return rate;
                        }
                    }
                }

                @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
                static class HugePageLimits {
                    private final String pageSize;
                    private final long limit;

                    public HugePageLimits(String pageSize, long limit) {
                        this.pageSize = pageSize;
                        this.limit = limit;
                    }

                    public HugePageLimits() {
                        this(null, 0);
                    }

                    public String getPageSize() {
                        return pageSize;
                    }

                    public long getLimit() {
                        return limit;
                    }
                }

                @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
                static class Network {
                    private final int classId;
                    private final List<NetworkPriority> priorities;

                    public Network(int classId, List<NetworkPriority> priorities) {
                        this.classId = classId;
                        this.priorities = priorities;
                    }

                    public Network() {
                        this(0, null);
                    }

                    public int getClassId() {
                        return classId;
                    }

                    public List<NetworkPriority> getPriorities() {
                        return priorities;
                    }

                    @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
                    static class NetworkPriority {
                        private final String name;
                        private final int priority;

                        public NetworkPriority(String name, int priority) {
                            this.name = name;
                            this.priority = priority;
                        }

                        public NetworkPriority() {
                            this(null, 0);
                        }

                        public String getName() {
                            return name;
                        }

                        public int getPriority() {
                            return priority;
                        }
                    }
                }

                @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
                static class Pid {
                    private final long limit;

                    public Pid(long limit) {
                        this.limit = limit;
                    }

                    public Pid() {
                        this(0);
                    }

                    public long getLimit() {
                        return limit;
                    }
                }

                @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
                static class Rdma {
                    private final int hcaHandles;
                    private final int hcaObjects;

                    public Rdma(int hcaHandles, int hcaObjects) {
                        this.hcaHandles = hcaHandles;
                        this.hcaObjects = hcaObjects;
                    }

                    public Rdma() {
                        this(0, 0);
                    }

                    public int getHcaHandles() {
                        return hcaHandles;
                    }

                    public int getHcaObjects() {
                        return hcaObjects;
                    }
                }
            }

            @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
            static class IntelRdt {
                private final String closId;
                private final String l3CacheSchema;
                private final String memBwSchema;

                public IntelRdt(String closId, String l3CacheSchema, String memBwSchema) {
                    this.closId = closId;
                    this.l3CacheSchema = l3CacheSchema;
                    this.memBwSchema = memBwSchema;
                }

                public IntelRdt() {
                    this(null, null, null);
                }

                public String getClosId() {
                    return closId;
                }

                public String getL3CacheSchema() {
                    return l3CacheSchema;
                }

                public String getMemBwSchema() {
                    return memBwSchema;
                }
            }

            @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
            static class Sysctl {
                // for kernel params
            }

            @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
            static class Seccomp {
                private final String defaultAction;
                private final List<String> architectures;
                private final List<Syscall> syscalls;

                public Seccomp(String defaultAction, List<String> architectures, List<Syscall> syscalls) {
                    this.defaultAction = defaultAction;
                    this.architectures = architectures;
                    this.syscalls = syscalls;
                }

                public Seccomp() {
                    this(null, null, null);
                }

                public String getDefaultAction() {
                    return defaultAction;
                }

                public List<String> getArchitectures() {
                    return architectures;
                }

                public List<Syscall> getSyscalls() {
                    return syscalls;
                }

                @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
                static class Syscall {
                    private final List<String> names;
                    private final String action;
                    private final List<SeccompArg> args;

                    public Syscall(List<String> names, String action, List<SeccompArg> args) {
                        this.names = names;
                        this.action = action;
                        this.args = args;
                    }

                    public Syscall() {
                        this(null, null, null);
                    }

                    public List<String> getNames() {
                        return names;
                    }

                    public String getAction() {
                        return action;
                    }

                    public List<SeccompArg> getArgs() {
                        return args;
                    }

                    @JsonSerialize(include = JsonSerialize.Inclusion.NON_DEFAULT)
                    static class SeccompArg {
                        private final int index;
                        private final long value;
                        private final long valueTwo;
                        private final String op;

                        public SeccompArg(int index, long value, long valueTwo, String op) {
                            this.index = index;
                            this.value = value;
                            this.valueTwo = valueTwo;
                            this.op = op;
                        }

                        public SeccompArg() {
                            this(0, 0, 0, null);
                        }

                        public int getIndex() {
                            return index;
                        }

                        public long getValue() {
                            return value;
                        }

                        public long getValueTwo() {
                            return valueTwo;
                        }

                        public String getOp() {
                            return op;
                        }
                    }
                }
            }
        }
    }
}
