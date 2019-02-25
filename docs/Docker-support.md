---
title: Docker Support
layout: documentation
documentation: true
---

# Docker Support

This page describes how storm supervisor launches the worker in a docker container. 

## Motivation

This is mostly about security and portability. With workers running inside of docker containers, we isolate running user code from each other and from the hosted machine so that the whole system is less vulnerable to attack. 
It also allows users to run their topologies on different os versions using different docker images.

## Implementation

The implementation is pretty easy to understand. Essentially, `DockerManager` composes a docker-run command and uses `worker-launcher` executable to execute the command 
to launch a container. The `storm-worker-script.sh` script is the actual command to launch the worker process and logviewer in the container.
One container ID is mapped to one worker ID conceptually. When the worker process dies, the container exits. 

For security, when the supervisor launches the docker container, it makes the whole container read-only except some explicit bind mount locations.
It also drops all the kernel capabilities and disables container processes from gaining new privileges. 

Because no new privileges are obtainable, `jstack` and other java debugging tools cannot be used directly in the container. 
We need to install `nscd` and have it running in the system. Storm will bind mount nscd directory when it launches the container. 
And `nsenter` will be used to enter the docker container to run the standard JVM debugging tools. This functionality is also implemented in `worker-launcher` executable.

The command that will be run by `worker-launcher` executable to launch a container will be something like:

```bash
run --name=06f8ddbd-88d8-454a-acf1-f9d1f3e6baec \
--user=1001:1003 \
--net=host \
--read-only \
-v /sys/fs/cgroup:/sys/fs/cgroup:ro \
-v /home/y:/home/y:ro \
-v /usr/share/apache-storm-2.0.0:/usr/share/apache-storm-2.0.0:ro \
-v /home/y/var/storm/supervisor:/home/y/var/storm/supervisor:ro \
-v /home/y/var/storm/workers/06f8ddbd-88d8-454a-acf1-f9d1f3e6baec:/home/y/var/storm/workers/06f8ddbd-88d8-454a-acf1-f9d1f3e6baec \
-v /home/y/var/storm/workers-artifacts/wc-1-1539979318/6700:/home/y/var/storm/workers-artifacts/wc-1-1539979318/6700 \
-v /home/y/var/storm/workers-users/06f8ddbd-88d8-454a-acf1-f9d1f3e6baec:/home/y/var/storm/workers-users/06f8ddbd-88d8-454a-acf1-f9d1f3e6baec \
-v /var/run/nscd:/var/run/nscd \
-v /etc:/etc:ro \
-v /home/y/var/storm/supervisor/stormdist/wc-1-1539979318/shared_by_topology/tmp:/tmp \
--cgroup-parent=/storm \
--group-add 1003 \
--workdir=/home/y/var/storm/workers/06f8ddbd-88d8-454a-acf1-f9d1f3e6baec \
--cidfile=/home/y/var/storm/workers/06f8ddbd-88d8-454a-acf1-f9d1f3e6baec/container.cid \
--cap-drop=ALL \
--security-opt no-new-privileges \
--cpus=1.0 \
xxx.xxx.com:8080/storm/docker_images/rhel6:latest \
bash /home/y/var/storm/workers/06f8ddbd-88d8-454a-acf1-f9d1f3e6baec/storm-worker-script.sh
```


## Setup

To make supervisor work with docker, you need to configure related settings correctly following the instructions below.

### Settings Related To Docker Support in Storm

| Setting                                   | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
|-------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `storm.resource.isolation.plugin.enable`  | set to `true` to enable isolation plugin. `storm.resource.isolation.plugin` determines which plugin to use. If this is set to `false`, `org.apache.storm.container.DefaultResourceIsolationManager` will be used.                                                                                                                                                                                                                                           |
| `storm.resource.isolation.plugin`         | set to `"org.apache.storm.container.docker.DockerManager"` to enable docker support                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `storm.docker.allowed.images`             | A whitelist of docker images that can be used. Users can only choose a docker image from the list.
| `storm.docker.image`                      | The default docker image to be used if user doesn't specify which image to use. And it must belong to the `storm.docker.allowed.images` 
| `storm.docker.cgroup.root`                | The root path of cgroup for docker to use. On RHEL7, it should be "/sys/fs/cgroup".
| `storm.docker.cgroup.parent`              | --cgroup-parent config for docker command. It must follow the constraints of docker commands. The path will be made as absolute path if it's a relative path because we saw some weird bugs ((the cgroup memory directory disappears after a while) when a relative path is used.
| `storm.docker.readonly.bindmounts`        | A list of read only bind mounted directories.
| `storm.docker.nscd.dir`                   | The directory of nscd (name service cache daemon), e.g. "/var/run/nscd/". nscd must be running so that profiling can work properly.
| `storm.docker.seccomp.profile`            | White listed syscalls seccomp Json file to be used as a seccomp filter
| `supervisor.worker.launcher`              | Full path to the worker-launcher executable. Details explained at [How to set up worker-launcher](#how-to-set-up-worker-launcher)

Note that we only support cgroupfs cgroup driver because of some issues with `systemd` cgroup driver; restricting to `cgroupfs` also makes cgroup paths simpler. Please make sure to use `cgroupfs` before setting up docker support.

#### Example

Below is a simple configuration example for storm on Rhel7. In this example, storm is deployed at `/usr/share/apache-storm-2.0.0`.

```bash
storm.resource.isolation.plugin.enable: true
storm.resource.isolation.plugin: "org.apache.storm.container.docker.DockerManager"
storm.docker.allowed.images: ["xxx.xxx.com:8080/storm/docker_images/rhel6:latest"]
storm.docker.image: "xxx.xxx.com:8080/storm/docker_images/rhel6:latest"
storm.docker.cgroup.root: "/storm"
storm.docker.cgroup.parent: "/sys/fs/cgroup"
storm.docker.readonly.bindmounts:
    - "/etc"
    - "/home/y"
storm.docker.nscd.dir: "/var/run/nscd"
supervisor.worker.launcher: "/usr/share/apache-storm-2.0.0/bin/worker-launcher"
```

### How to set up worker-launcher

The `worker-launcher` executable is a special program that is used to launch docker containers, run `docker` and `nsenter` commands.
For this to work, `worker-launcher` needs to be owned by root, but with the group set to be a group that only the supervisor headless user is a part of. 
`worker-launcher` also needs to have `6550 octal permissions. There is also a `worker-launcher`.cfg file, usually located under `/etc/`, that should look something like the following:
```
storm.worker-launcher.group=$(worker_launcher_group)
min.user.id=$(min_user_id)
worker.profiler.script.path=$(profiler_script_path)
```
where `storm.worker-launcher.group` is the same group the supervisor user is a part of, and `min.user.id` is set to the first real user id on the system. This config file also needs to be owned by root and not have world nor group write permissions. 
`worker.profiler.script.path` points to the profiler script. For security, the script should be only writable by root. Note that it's the only profiler script that will be used and `DaemonConfig.WORKER_PROFILER_COMMAND` will be ignored.

There are two optional configs that will be used by docker support: `docker.binary` and `nsenter.binary`. By default, they are set to
```
docker.binary=/usr/bin/docker
nsenter.binary=/usr/bin/nsenter
```
and you don't need to set them in the worker-launcher.cfg unless you need to change them.

## Monitoring

You can use docker commands like `docker inspect`, `docker ps`, etc to monitor the docker containers. 

Also in the `storm.yaml` file, you can add the following configs

```bash
worker.metrics:
   "CGroupMemory": "org.apache.storm.metric.docker.DockerMemoryUsage"
   "CGroupMemoryLimit": "org.apache.storm.metric.docker.DockerMemoryLimit"
   "CGroupCpu": "org.apache.storm.metric.docker.DockerCpu"
   "CGroupCpuGuarantee": "org.apache.storm.metric.docker.DockerCpuGuarantee"
```

so that the cpu/memory metrics per worker will be reported.