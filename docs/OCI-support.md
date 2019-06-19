---
title: OCI/Squashfs Runtime
layout: documentation
documentation: true
---

# OCI/Squashfs Runtime

OCI/Squashfs is a container runtime that allows topologies to run inside docker containers. However, unlike the existing
Docker runtime, the images are fetched from HDFS rather than from the Docker registry or requiring images to be pre-loaded
into Docker on each node. Docker does not need to be installed on the nodes in order for this runtime to work.

Note: This is only tested on RHEL7.

## Motivation

#### Docker runtime drawbacks
Using the current Docker runtime has some drawbacks:

##### Docker Daemons Dependency

The Docker daemons `dockerd` and `containerd` must be running on the system in order for the Docker runtime to function. 
And these daemons can get out of sync which could cause nontrivial issues to the containers.

##### Docker Registry Issues at Scale

Using the Docker runtime on a large scale Storm cluster can overwhelm the Docker registry. In practice this requires
admins to pre-load a Docker image on all the cluster nodes in a controlled fashion before a large job requesting 
the image can run.

##### Image Costs in Time and Space

Docker stores each image layer as a tar.gz archive. In order to use the layer, the compressed archive must be unpacked
into the node's filesystem. This can consume significant disk space, especially when the reliable image store location
capacity is relatively small. In addition, unpacking an image layer takes time, especially when the layer is large or 
contains thousands of files. This additional time for unpacking delays container launch beyond the time needed to transfer
the layer data over the network.

#### OCI/Squashfs Runtime advantages

The OCI/Squashfs runtime avoids the drawback listed above in the following ways.

##### No Docker dependencies on The Node

Docker does not need to be installed on each node, nor is there a dependency on a daemon or service that needs to be started
by an admin before containers can be launched. All that is required to be present on each node is an OCI-compatible runtime like
`runc`.

##### Leverages Distributed File Sytems For Scale

Image can be fetched via HDFS or other distributed file systems instead of the Docker registry. This prevents a large cluster from
overwhelming a Docker registry when a big topology causes all of the nodes to request an image at once. This also allows large clusters
to run topologies more dynamically, as images would not need to be pre-loaded by admins on each node to prevent a large Docker registry
image request storm.

##### Smaller, Faster images on The Node

The new runtime handles layer localization directly, so layer formats other than tar archive can be supported. For example, each image layer
can be converted to squashfs images as part of copying the layers to HDFS. squashfs is a file system optimized for running directly on a
compressed image. With squashfs layers the layer data can remain compressed on the node saving disk space. Container launch after layer
localization is also faster, as the layers no longer need to be unpacked into a directory to become usable.


## Prerequisite 

First you need to use the`docker-to-squash.py` script to download docker images and configs, convert layers to squashfs files and put them to a directory in HDFS, for example

```bash
python2 docker-to-squash.py pull-build-push-update --hdfs-root hdfs://hostname:port/containers  \
                      --image-tag-to-hash image-tag-to-manifest-file \         
                      docker.xxx.com:4443/storm/docker_configs/rhel6:20180918-215129,docker.xxx.com:4443/storm/docker_configs/rhel6:20180918-215129,storm/rhel6:current
```

With this command, all the layers belong to this image will be converted to squashfs file and be placed under `./layers` directory; 
the manifest of this image will be placed under `./manifests` directory with the name as the sha256 value of the manifest content;
the config of this image will be placed under `./config` directory with the name as the sha256 value of the config content;
the mapping from the image tag to the sha256 value of the manifest  will be written to the "./image-tag-to-manifest-file".

##### Example

For example, the directory structure is like this:

```bash
-bash-4.2$ hdfs dfs -ls /containers/*
Found 1 items
-r--r--r--  30 gstorm hdfs       7223 2019-03-03 19:18 /containers/config/26d70273e90e4e6888a780d49c2284575ab8874ef4100ee60886405eab30cf3b
-r--r--r--   3 gstorm hdfs        133 2019-03-03 19:19 /containers/image-tag-to-manifest-file
Found 9 items
-r--r--r--  30 gstorm hdfs  360402944 2019-03-03 19:17 /containers/layers/221362370ae4442598522b6182206d33e28072705e66c9d5e98f0df91c6befdc.sqsh
-r--r--r--  30 gstorm hdfs   47603712 2019-03-03 19:18 /containers/layers/3e938c349a837f193a1f7b04f4d9270e22192341493b5dd6b5a7e971deb4ea7c.sqsh
-r--r--r--  30 gstorm hdfs 1143877632 2019-03-03 19:15 /containers/layers/59c61b46c313d9e689379f62a4618dc354a2828d7b8de4583cbbc47eef491793.sqsh
-r--r--r--  30 gstorm hdfs      28672 2019-03-03 19:15 /containers/layers/6b5b9a69d88be7e51b836988b52b4ba56309124d09c71428e6ea792ecd623777.sqsh
-r--r--r--  30 gstorm hdfs    1748992 2019-03-03 19:15 /containers/layers/b5128b303fffefe461ac51f8d24cad2cc1ab2de4db627d65248fe35d4484036f.sqsh
-r--r--r--  30 gstorm hdfs       4096 2019-03-03 19:17 /containers/layers/bcc2164e950b78e92c42ce8ca424c1acee939d81e7948a6e1ec540fdc33af0ad.sqsh
-r--r--r--  30 gstorm hdfs  106119168 2019-03-03 19:16 /containers/layers/d67f97909db159e91b347e3e76e84490ede3d3c8caa5d046b9a0731a81f4e6c4.sqsh
-r--r--r--  30 gstorm hdfs       4096 2019-03-03 19:18 /containers/layers/de4fa442d9ff9e3dc34e57ca86eabae97fdc9d41cad35c57f8a64bef66cf7385.sqsh
-r--r--r--  30 gstorm hdfs       4096 2019-03-03 19:16 /containers/layers/e73736612a37e46e7f4f5a188ea4721baf647121a135505bde53233df2704caf.sqsh
Found 1 items
-r--r--r--  30 gstorm hdfs       2210 2019-03-03 19:19 /containers/manifests/c681ee9834682b5c76e138b546502dbae1f920da229daa24794c678d87535242
```

The `image-tag-to-manifest-file`:
```bash
docker.xxx.com:4443/storm/docker_configs/rhel6:20180918-215129,storm/rhel6:current:c681ee9834682b5c76e138b546502dbae1f920da229daa24794c678d87535242
```

The manifest file `c681ee9834682b5c76e138b546502dbae1f920da229daa24794c678d87535242`:
```json
{
   "schemaVersion": 2,
   "mediaType": "application/vnd.docker.distribution.manifest.v2+json",
   "config": {
      "mediaType": "application/vnd.docker.container.image.v1+json",
      "size": 7223,
      "digest": "sha256:26d70273e90e4e6888a780d49c2284575ab8874ef4100ee60886405eab30cf3b"
   },
   "layers": [
      {
         "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
         "size": 1186962133,
         "digest": "sha256:59c61b46c313d9e689379f62a4618dc354a2828d7b8de4583cbbc47eef491793"
      },
      {
         "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
         "size": 1762771,
         "digest": "sha256:b5128b303fffefe461ac51f8d24cad2cc1ab2de4db627d65248fe35d4484036f"
      },
      {
         "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
         "size": 26684,
         "digest": "sha256:6b5b9a69d88be7e51b836988b52b4ba56309124d09c71428e6ea792ecd623777"
      },
      {
         "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
         "size": 441,
         "digest": "sha256:e73736612a37e46e7f4f5a188ea4721baf647121a135505bde53233df2704caf"
      },
      {
         "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
         "size": 105123605,
         "digest": "sha256:d67f97909db159e91b347e3e76e84490ede3d3c8caa5d046b9a0731a81f4e6c4"
      },
      {
         "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
         "size": 360732690,
         "digest": "sha256:221362370ae4442598522b6182206d33e28072705e66c9d5e98f0df91c6befdc"
      },
      {
         "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
         "size": 296,
         "digest": "sha256:bcc2164e950b78e92c42ce8ca424c1acee939d81e7948a6e1ec540fdc33af0ad"
      },
      {
         "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
         "size": 46900019,
         "digest": "sha256:3e938c349a837f193a1f7b04f4d9270e22192341493b5dd6b5a7e971deb4ea7c"
      },
      {
         "mediaType": "application/vnd.docker.image.rootfs.diff.tar.gzip",
         "size": 186,
         "digest": "sha256:de4fa442d9ff9e3dc34e57ca86eabae97fdc9d41cad35c57f8a64bef66cf7385"
      }
   ]
}
```

And the config file `26d70273e90e4e6888a780d49c2284575ab8874ef4100ee60886405eab30cf3b`:
```json
{
  "architecture": "amd64",
  "config": {
    "Hostname": "9d2477f6e4f9",
    "Domainname": "",
    "User": "",
    "AttachStdin": false,
    "AttachStdout": false,
    "AttachStderr": false,
    "Tty": false,
    "OpenStdin": false,
    "StdinOnce": false,
    "Env": [
      "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/home/gs/java/current/bin:/home/gs/hadoop/current/bin:/gridtools/generic/bin",
      "HADOOP_PREFIX=/home/gs/hadoop/current",
      "HADOOP_COMMON_HOME=/home/gs/hadoop/current",
      "HADOOP_HDFS_HOME=/home/gs/hadoop/current",
      "HADOOP_HEAPSIZE=128",
      "HADOOP_YARN_HOME=/home/gs/hadoop/current",
      "HADOOP_MAPRED_HOME=/home/gs/hadoop/current",
      "HADOOP_CONF_DIR=/home/gs/conf/current",
      "JAVA_HOME=/home/gs/java/current",
      "JAVA_LIBRARY_PATH=/home/gs/hadoop/current/lib/native/Linux-amd64-64",
      "LANG=en_US.UTF-8",
      "WORKDIR=/grid/0/tmp,/grid/1/tmp,/grid/2/tmp,/grid/3/tmp",
      "YARN_CONF_DIR=/home/gs/conf/hadoop/nodemanager"
    ],
    "Cmd": null,
    "ArgsEscaped": true,
    "Image": "sha256:3876c6c6ff7bb719558676fd44261cb0255caa44ec92983edcb5c646f96d31f6",
    "Volumes": null,
    "WorkingDir": "",
    "Entrypoint": null,
    "OnBuild": [
      
    ],
    "Labels": {
      
    }
  },
  "container": "29f778ba44f8aa452d86bb31190b6651c58d5458ac86f814361048a768f10c16",
  "container_config": {
    "Hostname": "9d2477f6e4f9",
    "Domainname": "",
    "User": "",
    "AttachStdin": false,
    "AttachStdout": false,
    "AttachStderr": false,
    "Tty": false,
    "OpenStdin": false,
    "StdinOnce": false,
    "Env": [
      "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/home/gs/java/current/bin:/home/gs/hadoop/current/bin:/gridtools/generic/bin",
      "HADOOP_PREFIX=/home/gs/hadoop/current",
      "HADOOP_COMMON_HOME=/home/gs/hadoop/current",
      "HADOOP_HDFS_HOME=/home/gs/hadoop/current",
      "HADOOP_HEAPSIZE=128",
      "HADOOP_YARN_HOME=/home/gs/hadoop/current",
      "HADOOP_MAPRED_HOME=/home/gs/hadoop/current",
      "HADOOP_CONF_DIR=/home/gs/conf/current",
      "JAVA_HOME=/home/gs/java/current",
      "JAVA_LIBRARY_PATH=/home/gs/hadoop/current/lib/native/Linux-amd64-64",
      "LANG=en_US.UTF-8",
      "WORKDIR=/grid/0/tmp,/grid/1/tmp,/grid/2/tmp,/grid/3/tmp",
      "YARN_CONF_DIR=/home/gs/conf/hadoop/nodemanager"
    ],
    "Cmd": [
      "/bin/sh",
      "-c",
      "#(nop) COPY file:9a7fc16d65df0e8aa4276dd3832dca288dc01b7a321a76c6c88cda2581a935ed in /etc/krb5.conf "
    ],
    "ArgsEscaped": true,
    "Image": "sha256:3876c6c6ff7bb719558676fd44261cb0255caa44ec92983edcb5c646f96d31f6",
    "Volumes": null,
    "WorkingDir": "",
    "Entrypoint": null,
    "OnBuild": [
      
    ],
    "Labels": {
      
    }
  },
  "created": "2018-09-18T21:56:56.411274253Z",
  "docker_version": "1.13.1",
  "history": [
    {
      "created": "2017-07-20T18:33:24.10233255Z",
      "author": "xxx",
      "created_by": "/bin/sh -c #(nop)  MAINTAINER Linux Team ylinux-team@yahoo-inc.com",
      "empty_layer": true
    },
    {
      "created": "2017-07-20T18:35:55.632715979Z",
      "author": "xxx",
      "created_by": "/bin/sh -c #(nop) ADD file:68ac712e5ef6929a26c828070d052d0864d2ff1f932044c6ef53f2f81d7956cc in / "
    },
    {
      "created": "2017-07-20T18:36:25.624724118Z",
      "author": "xxx",
      "created_by": "/bin/sh -c /usr/bin/curl -s -S -o /tmp/yinst http://edge.dist.corp.yahoo.com:8000/bin/yinst.rhel6stable \u0026\u0026 chmod 0755 /tmp/yinst \u0026\u0026 /tmp/yinst self-install \u0026\u0026 rm -f /tmp/yinst"
    },
    {
      "created": "2017-07-20T18:36:45.948622969Z",
      "author": "xxx",
      "created_by": "/bin/sh -c /usr/local/bin/yinst install -yes ylock_free"
    },
    {
      "created": "2018-09-18T21:51:57.361399776Z",
      "created_by": "/bin/sh -c #(nop)  ENV HADOOP_PREFIX=/home/gs/hadoop/current HADOOP_COMMON_HOME=/home/gs/hadoop/current HADOOP_HDFS_HOME=/home/gs/hadoop/current HADOOP_HEAPSIZE=128 HADOOP_YARN_HOME=/home/gs/hadoop/current HADOOP_MAPRED_HOME=/home/gs/hadoop/current HADOOP_CONF_DIR=/home/gs/conf/current JAVA_HOME=/home/gs/java/current JAVA_LIBRARY_PATH=/home/gs/hadoop/current/lib/native/Linux-amd64-64 LANG=en_US.UTF-8 WORKDIR=/grid/0/tmp,/grid/1/tmp,/grid/2/tmp,/grid/3/tmp YARN_CONF_DIR=/home/gs/conf/hadoop/nodemanager",
      "empty_layer": true
    },
    {
      "created": "2018-09-18T21:52:26.259545606Z",
      "created_by": "/bin/sh -c #(nop)  ENV PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/home/gs/java/current/bin:/home/gs/hadoop/current/bin:/gridtools/generic/bin",
      "empty_layer": true
    },
    {
      "created": "2018-09-18T21:52:54.651188697Z",
      "created_by": "/bin/sh -c mkdir -p /home/Releases/hadoop/hadoop \u0026\u0026   mkdir -p /home/gs/hadoop \u0026\u0026   mkdir -p /home/gs/conf/hadoop \u0026\u0026   mkdir -p /home/gs/sink \u0026\u0026   mkdir -p /grid/0/gs/hadoop \u0026\u0026   ln -s /home/Releases/hadoop/hadoop /home/gs/hadoop/current \u0026\u0026   ln -s /home/Releases/hadoop/hadoop /home/gs/hadoop/yarn \u0026\u0026   ln -s /home/Releases/hadoop/hadoop /home/gs/hadoop/hdfs  \u0026\u0026   ln -s /home/Releases/hadoop/hadoop /grid/0/gs/hadoop/current  \u0026\u0026   ln -s /home/Releases/hadoop/hadoop /grid/0/gs/hadoop/yarn  \u0026\u0026   ln -s /home/Releases/hadoop/hadoop /grid/0/gs/hadoop/hdfs  \u0026\u0026   ln -s /home/gs/conf/current /home/gs/conf/hadoop/nodemanager \u0026\u0026   ln -s /home/gs/conf/current /home/gs/conf/hadoop/datanode \u0026\u0026   ln -s /home/gs/sink /grid/0/sink"
    },
    {
      "created": "2018-09-18T21:53:52.26277129Z",
      "created_by": "/bin/sh -c yum install -y yum-plugin-ovl"
    },
    {
      "created": "2018-09-18T21:54:49.058648466Z",
      "created_by": "/bin/sh -c yinst install yjava_jdk-1.8.0_162.284 \u0026\u0026   mkdir -p /home/gs/java/jdk64 \u0026\u0026 mkdir -p /home/gs/java8 \u0026\u0026   ln -s /home/y/libexec64/jdk64-1.8.0 /home/gs/java/current \u0026\u0026   ln -s /home/y/libexec64/jdk64-1.8.0 /home/gs/java8/jdk64 \u0026\u0026   ln -s /home/y/libexec64/jdk64-1.8.0 /home/gs/java/jdk \u0026\u0026   ln -s /home/y/libexec64/jdk64-1.8.0 /home/gs/java/jdk64/current"
    },
    {
      "created": "2018-09-18T21:55:14.09272936Z",
      "created_by": "/bin/sh -c #(nop) COPY file:3d1d16fa2ab69f4f862ebae44e315364a1c5a84e6c465f78dd67d16be8e38bf3 in /etc/yum.repos.d/artifactory-ygrid.repo "
    },
    {
      "created": "2018-09-18T21:56:28.910745326Z",
      "created_by": "/bin/sh -c yum install -y --enablerepo=artifactory-ygrid   --enablerepo=artifactory-ygrid-el6   libzstd   lzo   lzo-devel   openssl097a   perl-Authen-SASL   perl-Convert-ASN1   perl-Crypt-DES   perl-DateTime   perl-Digest-HMAC   perl-Digest-SHA1   perl-File-Slurp   perl-GSSAPI   perl-IO-Socket-SSL   perl-JSON   perl-LDAP   perl-Net-LibIDN   perl-Net-SNMP   perl-Net-SSLeay   perl-Text-Iconv   perl-XML-Filter-BufferText   perl-XML-LibXML   perl-XML-NamespaceSupport   perl-XML-SAX   perl-XML-SAX-Writer   perl-XML-Simple"
    },
    {
      "created": "2018-09-18T21:56:56.411274253Z",
      "created_by": "/bin/sh -c #(nop) COPY file:9a7fc16d65df0e8aa4276dd3832dca288dc01b7a321a76c6c88cda2581a935ed in /etc/krb5.conf "
    }
  ],
  "os": "linux",
  "rootfs": {
    "type": "layers",
    "diff_ids": [
      "sha256:e19df8c6746139211327c8df7576eec2030a7be12043f4fa15f75ab261a05f5d",
      "sha256:1b614ff6c7438ba339e9226d8cdbac0a3555c0d4ccbbebe553513548d654a657",
      "sha256:9f0a7a088d55a329413af691528dec19846591901c7250129c7a53d6145ae494",
      "sha256:b935dc532e5a32d413895fb275037e480efa0959e244a0b48bc9aede8a3cc633",
      "sha256:9167c72118400ce906bfe54eb2df70c1139e28738126c3043087af4fb36451a7",
      "sha256:680884e3108b8c1610eb601daaf1742876cbd2ecb0f5e6bf219f5c9d119b22f8",
      "sha256:e621cec8dcf8f5a3f9619fdb232f49f6c04e8023284c863fecf9a460b7350029",
      "sha256:afdd4b00175b69a6b8acc8a0ac6e638a72237b88e07479a0c1e4fc3c40120f6e",
      "sha256:c7f2b7e29da2bb8e007b2a88beccfd4e43e2037710d46cb41a1871a84d0b33c3"
    ]
  }
}
```

Note: To use the `docker-to-squash.py`, you need to install [skopeo](https://github.com/containers/skopeo), [jq](https://stedolan.github.io/jq/) and squashfs-tools.


## Configurations

Then you need to set up storm with the following configs:

| Setting                                   | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
|-------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `storm.resource.isolation.plugin.enable`  | set to `true` to enable isolation plugin. `storm.resource.isolation.plugin` determines which plugin to use. If this is set to `false`, `org.apache.storm.container.DefaultResourceIsolationManager` will be used.                                                                                                                                                                                                                                           |
| `storm.resource.isolation.plugin`         | set to `"org.apache.storm.container.oci.RuncLibContainerManager"` to enable OCI/Squash runtime support                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `storm.oci.allowed.images`             | A whitelist of docker images that can be used. Users can only choose a docker image from the list.
| `storm.oci.image`                      | The default docker image to be used if user doesn't specify which image to use. And it must belong to the `storm.oci.allowed.images` 
| `storm.oci.cgroup.root`                | The root path of cgroup for docker to use. On RHEL7, it should be "/sys/fs/cgroup".
| `storm.oci.cgroup.parent`              | --cgroup-parent config for docker command. It must follow the constraints of docker commands. The path will be made as absolute path if it's a relative path because we saw some weird bugs ((the cgroup memory directory disappears after a while) when a relative path is used.
| `storm.oci.readonly.bindmounts`        | A list of read only bind mounted directories.
| `storm.oci.readwrite.bindmounts`       | A list of read-write bind mounted directories.
| `storm.oci.nscd.dir`                   | The directory of nscd (name service cache daemon), e.g. "/var/run/nscd/". nscd must be running so that profiling can work properly.
| `storm.oci.seccomp.profile`            | White listed syscalls seccomp Json file to be used as a seccomp filter
| `supervisor.worker.launcher`              | Full path to the worker-launcher executable.
| `storm.oci.image.hdfs.toplevel.dir`      |  The HDFS location under which the oci image manifests, layers and configs directories exist.
| `storm.oci.image.tag.to.manifest.plugin` |  The plugin to be used to get the image-tag to manifest mappings.
| `storm.oci.localorhdfs.image.tag.to.manifest.plugin.hdfs.hash.file`   |   The hdfs location of image-tag to manifest mapping file. You need to set it if `org.apache.storm.container.oci.LocalOrHdfsImageTagToManifestPlugin` is used as `storm.oci.image.tag.to.manifest.plugin`.
| `storm.oci.manifest.to.resources.plugin` | The plugin to be used to get oci resource according to the manifest.
| `storm.oci.resources.localizer`   | The plugin to use for oci resources localization.

For example, 
```bash
storm.resource.isolation.plugin: "org.apache.storm.container.oci.RuncLibContainerManager"

storm.oci.image.hdfs.toplevel.dir: "hdfs://host:port/containers"
storm.oci.local.or.hdfs.image.tag.to.manifest.plugin.hdfs.hash.file: "hdfs://host:port/containers/image-tag-to-manifest-file"
storm.oci.image.tag.to.manifest.plugin: "org.apache.storm.container.oci.LocalOrHdfsImageTagToManifestPlugin"
storm.oci.manifest.to.resources.plugin: "org.apache.storm.container.oci.HdfsManifestToResourcesPlugin"
storm.oci.resources.localizer: "org.apache.storm.container.oci.HdfsOciResourcesLocalizer"

storm.oci.readonly.bindmounts:
  - "/home/y"
  - "/etc"

storm.oci.allowed.images: ["docker.xxx.com:4443/hadoop/docker_configs/rhel6:20180918-215129"]
storm.oci.image: "docker.xxx.com:4443/hadoop/docker_configs/rhel6:20180918-215129"

storm.oci.cgroup.root: "/sys/fs/cgroup"
storm.oci.cgroup.parent: "/storm"
storm.oci.nscd.dir: "/var/run/nscd"
```

To use built-in plugins from `external/storm-hdfs-oci`, you need to build `external/storm-hdfs-oci` and copy `storm-hdfs-oci.jar` to `extlib-daemon` directory.

`storm.hdfs.login.plugin` are required for `storm-hdfs-oci`. You can use `org.apache.storm.hdfs.login.HdfsSingleLogin` from `external/storm-hdfs-login` 
or you can implement your own.  

To use `org.apache.storm.hdfs.login.HdfsSingleLogin`, you need to build `external/storm-hdfs-login` and copy `target/storm-hdfs-login.jar` to `extlib-daemon` directory. Then set
```
storm.hdfs.login.plugin: org.apache.storm.hdfs.login.HdfsSingleLogin
```

Additionally, if you want to access to secure hdfs, you also need to set the following configs.  
```
storm.hdfs.login.keytab
storm.hdfs.login.principal
```

For example,
```
storm.hdfs.login.keytab: /etc/keytab
storm.hdfs.login.principal: primary/instance@REALM
```

## Implementation

##### Launch a container


The supervisor calls RuncLibContainerManager to launch the container and the worker inside the container. It will first call the `storm.oci.image.tag.to.manifest.plugin`
to fetch the mapping of image tag to manifest. Then it calls `storm.oci.manifest.to.resources.plugin` to get the list of resources to be downloaded and invokes 
`storm.oci.resources.localizer` to download the config of the image and the layers of the image to a local directory. It then composes a `oci-config.json` (see example in Appendix) and 
invoke worker-launcher to launch the container.

The worker-launcher parses the `oci-config.json` file and do some necessary initialization and set up. It then creates /run/worker-launcher/layers/xxx/mnt directories 
and associate them with loopback devices, for example:

```bash
-bash-4.2$ cat /proc/mounts
...
/dev/loop9 /run/worker-launcher/layers/d4980487e65b35ab4ad80fc0ac9d7bd696fe77ce9844445c1009429bd677ec44/mnt squashfs ro,relatime 0 0
/dev/loop10 /run/worker-launcher/layers/7848a6ddc56dd082fd990a9a1e850bac443ea35eabf4e66077813d723fbf83c3/mnt squashfs ro,relatime 0 0
/dev/loop11 /run/worker-launcher/layers/c71371174547d889ca8ee2cd49b93a43024e64309dd945aa7615c65bb7eabf00/mnt squashfs ro,relatime 0 0
/dev/loop12 /run/worker-launcher/layers/31acab59b91b4fdc3d9d86c90329cd96ff605bb1d8e92a75488bd930e02a34c5/mnt squashfs ro,relatime 0 0
/dev/loop13 /run/worker-launcher/layers/6036a360340bf6adc9d3d796defc79c9745b8410ebfbb3cd88a82dc9652627ba/mnt squashfs ro,relatime 0 0
/dev/loop14 /run/worker-launcher/layers/8a3d225a41613b5e53f74c9a39a2e396f07c36757d77e54f59fa601053e66591/mnt squashfs ro,relatime 0 0
/dev/loop15 /run/worker-launcher/layers/c5f40f88ee689c6ad517555c5e7e6aebaeea9d130a78fd32631ada9a2f050d13/mnt squashfs ro,relatime 0 0
/dev/loop16 /run/worker-launcher/layers/d54d78f5645672836372c3d7fd6d768b693b96c894bc77aaaa69ea5b097d050a/mnt squashfs ro,relatime 0 0
/dev/loop17 /run/worker-launcher/layers/6c14305ee18281ec13e5764a627e8d9c5e7df51f4c13c571e387dcc209c21c5c/mnt squashfs ro,relatime 0 0
...

```

Then it mounts the layers, for example:
```bash
-bash-4.2$ mount
...
/home/y/var/storm/supervisor/oci-resources/layers/59c61b46c313d9e689379f62a4618dc354a2828d7b8de4583cbbc47eef491793.sqsh on /run/worker-launcher/layers/d4980487e65b35ab4ad80fc0ac9d7bd696fe77ce9844445c1009429bd677ec44/mnt type squashfs (ro,relatime)
/home/y/var/storm/supervisor/oci-resources/layers/b5128b303fffefe461ac51f8d24cad2cc1ab2de4db627d65248fe35d4484036f.sqsh on /run/worker-launcher/layers/7848a6ddc56dd082fd990a9a1e850bac443ea35eabf4e66077813d723fbf83c3/mnt type squashfs (ro,relatime)
/home/y/var/storm/supervisor/oci-resources/layers/6b5b9a69d88be7e51b836988b52b4ba56309124d09c71428e6ea792ecd623777.sqsh on /run/worker-launcher/layers/c71371174547d889ca8ee2cd49b93a43024e64309dd945aa7615c65bb7eabf00/mnt type squashfs (ro,relatime)
/home/y/var/storm/supervisor/oci-resources/layers/e73736612a37e46e7f4f5a188ea4721baf647121a135505bde53233df2704caf.sqsh on /run/worker-launcher/layers/31acab59b91b4fdc3d9d86c90329cd96ff605bb1d8e92a75488bd930e02a34c5/mnt type squashfs (ro,relatime)
/home/y/var/storm/supervisor/oci-resources/layers/d67f97909db159e91b347e3e76e84490ede3d3c8caa5d046b9a0731a81f4e6c4.sqsh on /run/worker-launcher/layers/6036a360340bf6adc9d3d796defc79c9745b8410ebfbb3cd88a82dc9652627ba/mnt type squashfs (ro,relatime)
/home/y/var/storm/supervisor/oci-resources/layers/221362370ae4442598522b6182206d33e28072705e66c9d5e98f0df91c6befdc.sqsh on /run/worker-launcher/layers/8a3d225a41613b5e53f74c9a39a2e396f07c36757d77e54f59fa601053e66591/mnt type squashfs (ro,relatime)
/home/y/var/storm/supervisor/oci-resources/layers/bcc2164e950b78e92c42ce8ca424c1acee939d81e7948a6e1ec540fdc33af0ad.sqsh on /run/worker-launcher/layers/c5f40f88ee689c6ad517555c5e7e6aebaeea9d130a78fd32631ada9a2f050d13/mnt type squashfs (ro,relatime)
/home/y/var/storm/supervisor/oci-resources/layers/3e938c349a837f193a1f7b04f4d9270e22192341493b5dd6b5a7e971deb4ea7c.sqsh on /run/worker-launcher/layers/d54d78f5645672836372c3d7fd6d768b693b96c894bc77aaaa69ea5b097d050a/mnt type squashfs (ro,relatime)
/home/y/var/storm/supervisor/oci-resources/layers/de4fa442d9ff9e3dc34e57ca86eabae97fdc9d41cad35c57f8a64bef66cf7385.sqsh on /run/worker-launcher/layers/6c14305ee18281ec13e5764a627e8d9c5e7df51f4c13c571e387dcc209c21c5c/mnt type squashfs (ro,relatime)
...
```

It creates the rootfs and mount the overlay filesystem (with lowerdir,upperdir,workdir) for the worker with the command 
```bash
mount -t overlay overlay -o lowerdir=/lower1:/lower2:/lower3,upperdir=/upper,workdir=/work /merged
```

```bash
-bash-4.2$ mount
...
overlay on /run/worker-launcher/48be2ee4-4bf7-46c0-b756-2092d7e0168d/rootfs type overlay (rw,relatime,lowerdir=/run/worker-launcher/layers/6c14305ee18281ec13e5764a627e8d9c5e7df51f4c13c571e387dcc209c21c5c/mnt:/run/worker-launcher/layers/d54d78f5645672836372c3d7fd6d768b693b96c894bc77aaaa69ea5b097d050a/mnt:/run/worker-launcher/layers/c5f40f88ee689c6ad517555c5e7e6aebaeea9d130a78fd32631ada9a2f050d13/mnt:/run/worker-launcher/layers/8a3d225a41613b5e53f74c9a39a2e396f07c36757d77e54f59fa601053e66591/mnt:/run/worker-launcher/layers/6036a360340bf6adc9d3d796defc79c9745b8410ebfbb3cd88a82dc9652627ba/mnt:/run/worker-launcher/layers/31acab59b91b4fdc3d9d86c90329cd96ff605bb1d8e92a75488bd930e02a34c5/mnt:/run/worker-launcher/layers/c71371174547d889ca8ee2cd49b93a43024e64309dd945aa7615c65bb7eabf00/mnt:/run/worker-launcher/layers/7848a6ddc56dd082fd990a9a1e850bac443ea35eabf4e66077813d723fbf83c3/mnt:/run/worker-launcher/layers/d4980487e65b35ab4ad80fc0ac9d7bd696fe77ce9844445c1009429bd677ec44/mnt,upperdir=/run/worker-launcher/48be2ee4-4bf7-46c0-b756-2092d7e0168d/upper,workdir=/run/worker-launcher/48be2ee4-4bf7-46c0-b756-2092d7e0168d/work)
...
```

It then produce a `config.json` (see example at Appendix) under /home/y/var/storm/workers/48be2ee4-4bf7-46c0-b756-2092d7e0168d directory and launch the container with
the command
```bash
/usr/bin/runc run -d \
              --pid-file /home/y/var/storm/workers/48be2ee4-4bf7-46c0-b756-2092d7e0168d/container.pid \
              -b  /home/y/var/storm/workers/48be2ee4-4bf7-46c0-b756-2092d7e0168d \
              48be2ee4-4bf7-46c0-b756-2092d7e0168d
```

##### Kill a container

To kill a container, RuncLibContainerManager sends the `SIGTERM or SIGKILL` signal to the container process. It then invokes worker-launcher to to umount the mounts and clean up the directories. 
The worker-launcher will invoke `runc delete container-id` to delete the container at the end.


## Profile the processes inside the container
If you have sudo permission, you can also run `sudo nsenter --target <container-pid> --pid --mount` to enter the container. 
Then you can run `jstack`, `jmap` etc inside the container. `<container-pid>` is the pid of the container process on the host.
`<container-pid>` can be obtained by running`runc list` command.

## Seccomp security profiles

You can set `storm.oci.seccomp.profile` to restrict the actions available within the container. If it's not set, the container runs without
restrictions. You can use `conf/seccomp.json.example` provided or you can specify our own `seccomp.json` file. 


## Appendix

##### Example oci-config.json file
```bash
{
  "version": "0.1",
  "username": "hadoopqa",
  "containerId": "48be2ee4-4bf7-46c0-b756-2092d7e0168d",
  "applicationId": "48be2ee4-4bf7-46c0-b756-2092d7e0168d",
  "pidFile": "/home/y/var/storm/workers/48be2ee4-4bf7-46c0-b756-2092d7e0168d/container.pid",
  "containerScriptPath": "/home/y/var/storm/workers/48be2ee4-4bf7-46c0-b756-2092d7e0168d/storm-worker-script.sh",
  "layers": [
    {
      "mediaType": "application/vnd.squashfs",
      "path": "/home/y/var/storm/supervisor/containers/layers/59c61b46c313d9e689379f62a4618dc354a2828d7b8de4583cbbc47eef491793.sqsh"
    },
    {
      "mediaType": "application/vnd.squashfs",
      "path": "/home/y/var/storm/supervisor/containers/layers/b5128b303fffefe461ac51f8d24cad2cc1ab2de4db627d65248fe35d4484036f.sqsh"
    },
    {
      "mediaType": "application/vnd.squashfs",
      "path": "/home/y/var/storm/supervisor/containers/layers/6b5b9a69d88be7e51b836988b52b4ba56309124d09c71428e6ea792ecd623777.sqsh"
    },
    {
      "mediaType": "application/vnd.squashfs",
      "path": "/home/y/var/storm/supervisor/containers/layers/e73736612a37e46e7f4f5a188ea4721baf647121a135505bde53233df2704caf.sqsh"
    },
    {
      "mediaType": "application/vnd.squashfs",
      "path": "/home/y/var/storm/supervisor/containers/layers/d67f97909db159e91b347e3e76e84490ede3d3c8caa5d046b9a0731a81f4e6c4.sqsh"
    },
    {
      "mediaType": "application/vnd.squashfs",
      "path": "/home/y/var/storm/supervisor/containers/layers/221362370ae4442598522b6182206d33e28072705e66c9d5e98f0df91c6befdc.sqsh"
    },
    {
      "mediaType": "application/vnd.squashfs",
      "path": "/home/y/var/storm/supervisor/containers/layers/bcc2164e950b78e92c42ce8ca424c1acee939d81e7948a6e1ec540fdc33af0ad.sqsh"
    },
    {
      "mediaType": "application/vnd.squashfs",
      "path": "/home/y/var/storm/supervisor/containers/layers/3e938c349a837f193a1f7b04f4d9270e22192341493b5dd6b5a7e971deb4ea7c.sqsh"
    },
    {
      "mediaType": "application/vnd.squashfs",
      "path": "/home/y/var/storm/supervisor/containers/layers/de4fa442d9ff9e3dc34e57ca86eabae97fdc9d41cad35c57f8a64bef66cf7385.sqsh"
    }
  ],
  "reapLayerKeepCount": 100,
  "ociRuntimeConfig": {
    "mounts": [
      {
        "destination": "/home/y",
        "type": "bind",
        "source": "/home/y",
        "options": [
          "ro",
          "rbind",
          "rprivate"
        ]
      },
      {
        "destination": "/etc",
        "type": "bind",
        "source": "/etc",
        "options": [
          "ro",
          "rbind",
          "rprivate"
        ]
      },
      {
        "destination": "/etc/resolv.conf",
        "type": "bind",
        "source": "/etc/resolv.conf",
        "options": [
          "ro",
          "rbind",
          "rprivate"
        ]
      },
      {
        "destination": "/etc/hostname",
        "type": "bind",
        "source": "/etc/hostname",
        "options": [
          "ro",
          "rbind",
          "rprivate"
        ]
      },
      {
        "destination": "/etc/hosts",
        "type": "bind",
        "source": "/etc/hosts",
        "options": [
          "ro",
          "rbind",
          "rprivate"
        ]
      },
      {
        "destination": "/var/run/nscd",
        "type": "bind",
        "source": "/var/run/nscd",
        "options": [
          "ro",
          "rbind",
          "rprivate"
        ]
      },
      {
        "destination": "/home/y/lib64/storm/2.0.1.y",
        "type": "bind",
        "source": "/home/y/lib64/storm/2.0.1.y",
        "options": [
          "ro",
          "rbind",
          "rprivate"
        ]
      },
      {
        "destination": "/sys/fs/cgroup",
        "type": "bind",
        "source": "/sys/fs/cgroup",
        "options": [
          "ro",
          "rbind",
          "rprivate"
        ]
      },
      {
        "destination": "/home/y/var/storm/supervisor",
        "type": "bind",
        "source": "/home/y/var/storm/supervisor",
        "options": [
          "ro",
          "rbind",
          "rprivate"
        ]
      },
      {
        "destination": "/home/y/var/storm/workers/48be2ee4-4bf7-46c0-b756-2092d7e0168d",
        "type": "bind",
        "source": "/home/y/var/storm/workers/48be2ee4-4bf7-46c0-b756-2092d7e0168d",
        "options": [
          "rw",
          "rbind",
          "rprivate"
        ]
      },
      {
        "destination": "/home/y/var/storm/workers-artifacts/wc-7-1554413868/6701",
        "type": "bind",
        "source": "/home/y/var/storm/workers-artifacts/wc-7-1554413868/6701",
        "options": [
          "rw",
          "rbind",
          "rprivate"
        ]
      },
      {
        "destination": "/home/y/var/storm/workers-users/48be2ee4-4bf7-46c0-b756-2092d7e0168d",
        "type": "bind",
        "source": "/home/y/var/storm/workers-users/48be2ee4-4bf7-46c0-b756-2092d7e0168d",
        "options": [
          "rw",
          "rbind",
          "rprivate"
        ]
      },
      {
        "destination": "/tmp",
        "type": "bind",
        "source": "/home/y/var/storm/supervisor/stormdist/wc-7-1554413868/shared_by_topology/tmp",
        "options": [
          "rw",
          "rbind",
          "rprivate"
        ]
      }
    ],
    "process": {
      "cwd": "/home/y/var/storm/workers/48be2ee4-4bf7-46c0-b756-2092d7e0168d",
      "env": [
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/home/gs/java/current/bin:/home/gs/hadoop/current/bin:/gridtools/generic/bin",
        "HADOOP_PREFIX=/home/gs/hadoop/current",
        "HADOOP_COMMON_HOME=/home/gs/hadoop/current",
        "HADOOP_HDFS_HOME=/home/gs/hadoop/current",
        "HADOOP_HEAPSIZE=128",
        "HADOOP_YARN_HOME=/home/gs/hadoop/current",
        "HADOOP_MAPRED_HOME=/home/gs/hadoop/current",
        "HADOOP_CONF_DIR=/home/gs/conf/current",
        "JAVA_HOME=/home/gs/java/current",
        "JAVA_LIBRARY_PATH=/home/gs/hadoop/current/lib/native/Linux-amd64-64",
        "LANG=en_US.UTF-8",
        "WORKDIR=/grid/0/tmp,/grid/1/tmp,/grid/2/tmp,/grid/3/tmp",
        "YARN_CONF_DIR=/home/gs/conf/hadoop/nodemanager",
        "LD_LIBRARY_PATH=/home/y/var/storm/supervisor/stormdist/wc-7-1554413868/resources/Linux-amd64:/home/y/var/storm/supervisor/stormdist/wc-7-1554413868/resources:/home/y/lib64:/usr/local/lib64:/usr/lib64:/lib64:"
      ],
      "args": [
        "bash",
        "/home/y/var/storm/workers/48be2ee4-4bf7-46c0-b756-2092d7e0168d/storm-worker-script.sh"
      ]
    },
    "linux": {
      "cgroupsPath": "/storm/48be2ee4-4bf7-46c0-b756-2092d7e0168d",
      "resources": {
        "cpu": {
          "quota": 26000000,
          "period": 100000
        }
      }
    }
  }
}
```

##### Example config.json file
```json
{
  "ociVersion": "1.0.0",
  "hostname": "xxxxx",
  "root": {
    "path": "/run/worker-launcher/48be2ee4-4bf7-46c0-b756-2092d7e0168d/rootfs",
    "readonly": true
  },
  "process": {
    "args": [
      "bash",
      "/home/y/var/storm/workers/48be2ee4-4bf7-46c0-b756-2092d7e0168d/storm-worker-script.sh"
    ],
    "cwd": "/home/y/var/storm/workers/48be2ee4-4bf7-46c0-b756-2092d7e0168d",
    "env": [
      "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/home/gs/java/current/bin:/home/gs/hadoop/current/bin:/gridtools/generic/bin",
      "HADOOP_PREFIX=/home/gs/hadoop/current",
      "HADOOP_COMMON_HOME=/home/gs/hadoop/current",
      "HADOOP_HDFS_HOME=/home/gs/hadoop/current",
      "HADOOP_HEAPSIZE=128",
      "HADOOP_YARN_HOME=/home/gs/hadoop/current",
      "HADOOP_MAPRED_HOME=/home/gs/hadoop/current",
      "HADOOP_CONF_DIR=/home/gs/conf/current",
      "JAVA_HOME=/home/gs/java/current",
      "JAVA_LIBRARY_PATH=/home/gs/hadoop/current/lib/native/Linux-amd64-64",
      "LANG=en_US.UTF-8",
      "WORKDIR=/grid/0/tmp,/grid/1/tmp,/grid/2/tmp,/grid/3/tmp",
      "YARN_CONF_DIR=/home/gs/conf/hadoop/nodemanager",
      "LD_LIBRARY_PATH=/home/y/var/storm/supervisor/stormdist/wc-7-1554413868/resources/Linux-amd64:/home/y/var/storm/supervisor/stormdist/wc-7-1554413868/resources:/home/y/lib64:/usr/local/lib64:/usr/lib64:/lib64:"
    ],
    "noNewPrivileges": true,
    "user": {
      "uid": 31315,
      "gid": 100,
      "additionalGids": [
        5548,
        11289,
        11303,
        65582
      ]
    }
  },
  "mounts": [
    {
      "source": "proc",
      "destination": "/proc",
      "type": "proc"
    },
    {
      "source": "tmpfs",
      "destination": "/dev",
      "type": "tmpfs",
      "options": [
        "nosuid",
        "strictatime",
        "mode=755",
        "size=65536k"
      ]
    },
    {
      "source": "devpts",
      "destination": "/dev/pts",
      "type": "devpts",
      "options": [
        "nosuid",
        "noexec",
        "newinstance",
        "ptmxmode=0666",
        "mode=0620",
        "gid=5"
      ]
    },
    {
      "source": "shm",
      "destination": "/dev/shm",
      "type": "tmpfs",
      "options": [
        "nosuid",
        "noexec",
        "nodev",
        "mode=1777",
        "size=65536k"
      ]
    },
    {
      "source": "mqueue",
      "destination": "/dev/mqueue",
      "type": "mqueue",
      "options": [
        "nosuid",
        "noexec",
        "nodev"
      ]
    },
    {
      "source": "sysfs",
      "destination": "/sys",
      "type": "sysfs",
      "options": [
        "nosuid",
        "noexec",
        "nodev",
        "ro"
      ]
    },
    {
      "source": "cgroup",
      "destination": "/sys/fs/cgroup",
      "type": "cgroup",
      "options": [
        "nosuid",
        "noexec",
        "nodev",
        "relatime",
        "ro"
      ]
    },
    {
      "destination": "/home/y",
      "type": "bind",
      "source": "/home/y",
      "options": [
        "ro",
        "rbind",
        "rprivate"
      ]
    },
    {
      "destination": "/etc",
      "type": "bind",
      "source": "/etc",
      "options": [
        "ro",
        "rbind",
        "rprivate"
      ]
    },
    {
      "destination": "/etc/resolv.conf",
      "type": "bind",
      "source": "/etc/resolv.conf",
      "options": [
        "ro",
        "rbind",
        "rprivate"
      ]
    },
    {
      "destination": "/etc/hostname",
      "type": "bind",
      "source": "/etc/hostname",
      "options": [
        "ro",
        "rbind",
        "rprivate"
      ]
    },
    {
      "destination": "/etc/hosts",
      "type": "bind",
      "source": "/etc/hosts",
      "options": [
        "ro",
        "rbind",
        "rprivate"
      ]
    },
    {
      "destination": "/var/run/nscd",
      "type": "bind",
      "source": "/var/run/nscd",
      "options": [
        "ro",
        "rbind",
        "rprivate"
      ]
    },
    {
      "destination": "/home/y/lib64/storm/2.0.1.y",
      "type": "bind",
      "source": "/home/y/lib64/storm/2.0.1.y",
      "options": [
        "ro",
        "rbind",
        "rprivate"
      ]
    },
    {
      "destination": "/sys/fs/cgroup",
      "type": "bind",
      "source": "/sys/fs/cgroup",
      "options": [
        "ro",
        "rbind",
        "rprivate"
      ]
    },
    {
      "destination": "/home/y/var/storm/supervisor",
      "type": "bind",
      "source": "/home/y/var/storm/supervisor",
      "options": [
        "ro",
        "rbind",
        "rprivate"
      ]
    },
    {
      "destination": "/home/y/var/storm/workers/48be2ee4-4bf7-46c0-b756-2092d7e0168d",
      "type": "bind",
      "source": "/home/y/var/storm/workers/48be2ee4-4bf7-46c0-b756-2092d7e0168d",
      "options": [
        "rw",
        "rbind",
        "rprivate"
      ]
    },
    {
      "destination": "/home/y/var/storm/workers-artifacts/wc-7-1554413868/6701",
      "type": "bind",
      "source": "/home/y/var/storm/workers-artifacts/wc-7-1554413868/6701",
      "options": [
        "rw",
        "rbind",
        "rprivate"
      ]
    },
    {
      "destination": "/home/y/var/storm/workers-users/48be2ee4-4bf7-46c0-b756-2092d7e0168d",
      "type": "bind",
      "source": "/home/y/var/storm/workers-users/48be2ee4-4bf7-46c0-b756-2092d7e0168d",
      "options": [
        "rw",
        "rbind",
        "rprivate"
      ]
    },
    {
      "destination": "/tmp",
      "type": "bind",
      "source": "/home/y/var/storm/supervisor/stormdist/wc-7-1554413868/shared_by_topology/tmp",
      "options": [
        "rw",
        "rbind",
        "rprivate"
      ]
    }
  ],
  "linux": {
    "cgroupsPath": "/storm/48be2ee4-4bf7-46c0-b756-2092d7e0168d",
    "resources": {
      "devices": [
        {
          "access": "rwm",
          "allow": false
        }
      ],
      "cpu": {
        "quota": 26000000,
        "period": 100000
      }
    },
    "namespaces": [
      {
        "type": "pid"
      },
      {
        "type": "ipc"
      },
      {
        "type": "uts"
      },
      {
        "type": "mount"
      }
    ],
    "maskedPaths": [
      "/proc/kcore",
      "/proc/latency_stats",
      "/proc/timer_list",
      "/proc/timer_stats",
      "/proc/sched_debug",
      "/proc/scsi",
      "/sys/firmware"
    ],
    "readonlyPaths": [
      "/proc/asound",
      "/proc/bus",
      "/proc/fs",
      "/proc/irq",
      "/proc/sys",
      "/proc/sysrq-trigger"
    ]
  }
}
```