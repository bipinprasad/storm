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

package org.apache.storm.container.oci;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.storm.DaemonConfig;
import org.apache.storm.utils.ConfigUtils;
import org.apache.storm.utils.HdfsLoginUtil;
import org.apache.storm.utils.ObjectReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HdfsOciResourcesLocalizer implements OciResourcesLocalizerInterface {
    private static final Logger LOG = LoggerFactory.getLogger(HdfsOciResourcesLocalizer.class);
    private String layersLocalDir;
    private String configLocalDir;
    private FileSystem fs;

    /**
     * Initialization.
     * @param conf the storm conf.
     * @throws IOException on I/O exception
     */
    public void init(Map<String, Object> conf) throws IOException {
        //login to hdfs
        HdfsLoginUtil.getInstance().logintoHdfs(conf);

        String resourcesLocalDir = ObjectReader.getString(conf.get(DaemonConfig.STORM_OCI_RESOURCES_LOCAL_DIR),
            ConfigUtils.supervisorLocalDir(conf) + "/oci-resources");
        FileUtils.forceMkdir(new File(resourcesLocalDir));
        this.layersLocalDir = resourcesLocalDir + "/layers/";
        this.configLocalDir = resourcesLocalDir + "/config/";
        String topLevelDir = ObjectReader.getString(conf.get(DaemonConfig.STORM_OCI_IMAGE_HDFS_TOPLEVEL_DIR));
        this.fs = new Path(topLevelDir).getFileSystem(new Configuration());
    }

    /**
     * Download the resources from HDFS to local dir.
     * @param ociResource The oci resource to download
     * @return the destination of the oci resource
     * @throws IOException on I/O exception
     */
    public synchronized String localize(OciResource ociResource) throws IOException {
        if (ociResource == null) {
            return null;
        }
        File dst;
        switch (ociResource.getType()) {
            case CONFIG:
                dst = new File(this.configLocalDir, ociResource.getFileName());
                break;
            case LAYER:
                dst = new File(layersLocalDir, ociResource.getFileName());
                break;
            default:
                throw new IOException("unknown OciResourceType " + ociResource.getType());
        }

        if (dst.exists()) {
            LOG.info("{} already exists. Skip", dst);
        } else {
            LOG.info("Starting to copy {} from hdfs to {}", ociResource.getPath(), dst.toString());
            fs.copyToLocalFile(new Path(ociResource.getPath()), new Path(dst.toString()));
            LOG.info("Finished copying {} from hdfs to {}", ociResource.getPath(), dst.toString());
            //set to readable by anyone
            boolean setReadable = dst.setReadable(true, false);
            if (!setReadable) {
                throw new IOException("Couldn't set " + dst + " to be world-readable");
            }
        }
        return dst.toString();
    }
}
