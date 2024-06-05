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

package org.apache.storm.daemon.logviewer;

/**
 * Constants which are used across logviewer related classes.
 */
public final class LogviewerConstant {
    private LogviewerConstant() {
    }

    public static final int DEFAULT_BYTES_PER_PAGE = 51200;
}
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

package org.apache.storm.daemon.logviewer;

import com.codahale.metrics.Meter;
import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.storm.DaemonConfig;
import org.apache.storm.daemon.logviewer.utils.DirectoryCleaner;
import org.apache.storm.daemon.logviewer.utils.ExceptionMeterNames;
import org.apache.storm.daemon.logviewer.utils.LogCleaner;
import org.apache.storm.daemon.logviewer.utils.WorkerLogs;
import org.apache.storm.daemon.logviewer.webapp.LogviewerApplication;
import org.apache.storm.daemon.ui.FilterConfiguration;
import org.apache.storm.daemon.ui.UIHelpers;
import org.apache.storm.metric.StormMetricsRegistry;
import org.apache.storm.utils.ConfigUtils;
import org.apache.storm.utils.ObjectReader;
import org.apache.storm.utils.Utils;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.Resource;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The main entry of Logviewer.
 */
public class LogviewerServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(LogviewerServer.class);
    private static final String stormHome = System.getProperty(ConfigUtils.STORM_HOME);
    public static final String STATIC_RESOURCE_DIRECTORY_PATH = stormHome + "/public";
    private final Meter meterShutdownCalls;

    private static Server mkHttpServer(StormMetricsRegistry metricsRegistry, Map<String, Object> conf) {
        Integer logviewerHttpPort = (Integer) conf.get(DaemonConfig.LOGVIEWER_PORT);
        Server ret = null;
        if (logviewerHttpPort != null && logviewerHttpPort >= 0) {
            LOG.info("Starting Logviewer HTTP servers...");
            String filterParamKey = DaemonConfig.LOGVIEWER_FILTER_PARAMS;
            String filterClass = (String) (conf.get(DaemonConfig.LOGVIEWER_FILTER));
            if (StringUtils.isBlank(filterClass)) {
                filterClass = (String) (conf.get(DaemonConfig.UI_FILTER));
                filterParamKey = DaemonConfig.UI_FILTER_PARAMS;
            }
            @SuppressWarnings("unchecked")
            Map<String, String> filterParams = (Map<String, String>) (conf.get(filterParamKey));
            FilterConfiguration filterConfiguration = new FilterConfiguration(filterClass, filterParams);
            final List<FilterConfiguration> filterConfigurations = Arrays.asList(filterConfiguration);

            final Integer httpsPort = ObjectReader.getInt(conf.get(DaemonConfig.LOGVIEWER_HTTPS_PORT), 0);
            final String httpsKsPath = (String) (conf.get(DaemonConfig.LOGVIEWER_HTTPS_KEYSTORE_PATH));
            final String httpsKsPassword = (String) (conf.get(DaemonConfig.LOGVIEWER_HTTPS_KEYSTORE_PASSWORD));
            final String httpsKsType = (String) (conf.get(DaemonConfig.LOGVIEWER_HTTPS_KEYSTORE_TYPE));
            final String httpsKeyPassword = (String) (conf.get(DaemonConfig.LOGVIEWER_HTTPS_KEY_PASSWORD));
            final String httpsTsPath = (String) (conf.get(DaemonConfig.LOGVIEWER_HTTPS_TRUSTSTORE_PATH));
            final String httpsTsPassword = (String) (conf.get(DaemonConfig.LOGVIEWER_HTTPS_TRUSTSTORE_PASSWORD));
            final String httpsTsType = (String) (conf.get(DaemonConfig.LOGVIEWER_HTTPS_TRUSTSTORE_TYPE));
            final Boolean httpsWantClientAuth = (Boolean) (conf.get(DaemonConfig.LOGVIEWER_HTTPS_WANT_CLIENT_AUTH));
            final Boolean httpsNeedClientAuth = (Boolean) (conf.get(DaemonConfig.LOGVIEWER_HTTPS_NEED_CLIENT_AUTH));
            final Boolean disableHttpBinding = (Boolean) (conf.get(DaemonConfig.LOGVIEWER_DISABLE_HTTP_BINDING));
            final boolean enableSslReload = ObjectReader.getBoolean(conf.get(DaemonConfig.LOGVIEWER_HTTPS_ENABLE_SSL_RELOAD), false);


            LogviewerApplication.setup(conf, metricsRegistry);
            ret = UIHelpers.jettyCreateServer(logviewerHttpPort, null, httpsPort, disableHttpBinding);

            UIHelpers.configSsl(ret, httpsPort, httpsKsPath, httpsKsPassword, httpsKsType, httpsKeyPassword,
                    httpsTsPath, httpsTsPassword, httpsTsType, httpsNeedClientAuth, httpsWantClientAuth, enableSslReload);

            ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
            try {
                context.setBaseResource(Resource.newResource(STATIC_RESOURCE_DIRECTORY_PATH));
            } catch (IOException e) {
                throw new RuntimeException("Can't locate static resource directory " + STATIC_RESOURCE_DIRECTORY_PATH);
            }

            context.setWelcomeFiles(new String[]{"logviewer.html"});
            context.setContextPath("/");
            ret.setHandler(context);

            ServletHolder holderPwd = new ServletHolder("default", DefaultServlet.class);
            holderPwd.setInitOrder(1);
            context.addServlet(holderPwd, "/");

            ServletHolder jerseyServlet = context.addServlet(ServletContainer.class, "/api/v1/*");
            jerseyServlet.setInitOrder(2);
            jerseyServlet.setInitParameter("javax.ws.rs.Application", LogviewerApplication.class.getName());

            UIHelpers.configFilters(context, filterConfigurations);
        }
        return ret;
    }

    private final Server httpServer;
    private boolean closed = false;

    /**
     * Constructor.
     * @param conf Logviewer conf for the servers
     * @param metricsRegistry The metrics registry
     */
    public LogviewerServer(Map<String, Object> conf, StormMetricsRegistry metricsRegistry) {
        httpServer = mkHttpServer(metricsRegistry, conf);
        meterShutdownCalls = metricsRegistry.registerMeter("logviewer:num-shutdown-calls");
        ExceptionMeterNames.registerMeters(metricsRegistry);
    }

    @VisibleForTesting
    void start() throws Exception {
        LOG.info("Starting Logviewer...");
        if (httpServer != null) {
            httpServer.start();
        }
    }

    @VisibleForTesting
    void awaitTermination() throws InterruptedException {
        httpServer.join();
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            //TODO this is causing issues...
            //if (httpServer != null) {
            //    httpServer.destroy();
            //}

            closed = true;
        }
    }

    /**
     * Main method to start the server.
     */
    public static void main(String [] args) throws Exception {
        Utils.setupDefaultUncaughtExceptionHandler();
        Map<String, Object> conf = ConfigUtils.readStormConfig();

        StormMetricsRegistry metricsRegistry = new StormMetricsRegistry();
        String logRoot = ConfigUtils.workerArtifactsRoot(conf);
        File logRootDir = new File(logRoot);
        logRootDir.mkdirs();
        WorkerLogs workerLogs = new WorkerLogs(conf, logRootDir.toPath(), metricsRegistry);
        DirectoryCleaner directoryCleaner = new DirectoryCleaner(metricsRegistry);

        try (LogviewerServer server = new LogviewerServer(conf, metricsRegistry);
             LogCleaner logCleaner = new LogCleaner(conf, workerLogs, directoryCleaner, logRootDir.toPath(), metricsRegistry)) {
            metricsRegistry.startMetricsReporters(conf);
            Utils.addShutdownHookWithForceKillIn1Sec(() -> {
                server.meterShutdownCalls.mark();
                metricsRegistry.stopMetricsReporters();
                server.close();
            });
            logCleaner.start();

            server.start();
            server.awaitTermination();
        }
    }
}
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

package org.apache.storm.daemon.logviewer.handler;

import java.io.IOException;
import javax.ws.rs.core.Response;

import org.apache.storm.daemon.logviewer.utils.LogFileDownloader;
import org.apache.storm.daemon.logviewer.utils.ResourceAuthorizer;
import org.apache.storm.daemon.logviewer.utils.WorkerLogs;
import org.apache.storm.metric.StormMetricsRegistry;

public class LogviewerLogDownloadHandler {

    private WorkerLogs workerLogs;
    private final LogFileDownloader logFileDownloadHelper;

    /**
     * Constructor.
     *
     * @param logRoot root worker log directory
     * @param daemonLogRoot root daemon log directory
     * @param workerLogs {@link WorkerLogs}
     * @param resourceAuthorizer {@link ResourceAuthorizer}
     * @param metricsRegistry The logviewer metrics registry
     */
    public LogviewerLogDownloadHandler(String logRoot, String daemonLogRoot, WorkerLogs workerLogs,
        ResourceAuthorizer resourceAuthorizer, StormMetricsRegistry metricsRegistry) {
        this.workerLogs = workerLogs;
        this.logFileDownloadHelper = new LogFileDownloader(logRoot, daemonLogRoot, resourceAuthorizer, metricsRegistry);
    }

    /**
     * Download a worker log.
     *
     * @param host host address
     * @param fileName file to download
     * @param user username
     * @return a Response which lets browsers download that file.
     *
     */
    public Response downloadLogFile(String host, String fileName, String user) throws IOException {
        workerLogs.setLogFilePermission(fileName);
        return logFileDownloadHelper.downloadFile(host, fileName, user, false);
    }

    /**
     * Download a daemon log.
     *
     * @param host host address
     * @param fileName file to download
     * @param user username
     * @return a Response which lets browsers download that file.
     */
    public Response downloadDaemonLogFile(String host, String fileName, String user) throws IOException {
        return logFileDownloadHelper.downloadFile(host, fileName, user, true);
    }

}
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

package org.apache.storm.daemon.logviewer.handler;

import static j2html.TagCreator.a;
import static j2html.TagCreator.body;
import static j2html.TagCreator.div;
import static j2html.TagCreator.form;
import static j2html.TagCreator.h3;
import static j2html.TagCreator.head;
import static j2html.TagCreator.html;
import static j2html.TagCreator.input;
import static j2html.TagCreator.link;
import static j2html.TagCreator.option;
import static j2html.TagCreator.p;
import static j2html.TagCreator.pre;
import static j2html.TagCreator.select;
import static j2html.TagCreator.text;
import static j2html.TagCreator.title;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang.StringEscapeUtils.escapeHtml;

import com.codahale.metrics.Meter;
import j2html.tags.DomContent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.ws.rs.core.Response;

import org.apache.commons.lang.StringUtils;
import org.apache.storm.daemon.logviewer.LogviewerConstant;
import org.apache.storm.daemon.logviewer.utils.DirectoryCleaner;
import org.apache.storm.daemon.logviewer.utils.ExceptionMeterNames;
import org.apache.storm.daemon.logviewer.utils.LogviewerResponseBuilder;
import org.apache.storm.daemon.logviewer.utils.ResourceAuthorizer;
import org.apache.storm.daemon.logviewer.utils.WorkerLogs;
import org.apache.storm.daemon.ui.InvalidRequestException;
import org.apache.storm.daemon.ui.UIHelpers;
import org.apache.storm.daemon.utils.StreamUtil;
import org.apache.storm.daemon.utils.UrlBuilder;
import org.apache.storm.metric.StormMetricsRegistry;
import org.apache.storm.utils.ConfigUtils;
import org.apache.storm.utils.ServerUtils;
import org.jooq.lambda.Unchecked;

public class LogviewerLogPageHandler {
    private final Meter numPageRead;
    private final Meter numFileOpenExceptions;
    private final Meter numFileReadExceptions;
    private final Path logRoot;
    private final Path daemonLogRoot;
    private final WorkerLogs workerLogs;
    private final ResourceAuthorizer resourceAuthorizer;
    private final DirectoryCleaner directoryCleaner;

    /**
     * Constructor.
     *
     * @param logRoot root worker log directory
     * @param daemonLogRoot root daemon log directory
     * @param workerLogs {@link WorkerLogs}
     * @param resourceAuthorizer {@link ResourceAuthorizer}
     * @param metricsRegistry The logviewer metrics registry
     */
    public LogviewerLogPageHandler(String logRoot, String daemonLogRoot,
                                   WorkerLogs workerLogs,
                                   ResourceAuthorizer resourceAuthorizer,
                                   StormMetricsRegistry metricsRegistry) {
        this.logRoot = Paths.get(logRoot).toAbsolutePath().normalize();
        this.daemonLogRoot = Paths.get(daemonLogRoot).toAbsolutePath().normalize();
        this.workerLogs = workerLogs;
        this.resourceAuthorizer = resourceAuthorizer;
        this.numPageRead = metricsRegistry.registerMeter("logviewer:num-page-read");
        this.numFileOpenExceptions = metricsRegistry.registerMeter(ExceptionMeterNames.NUM_FILE_OPEN_EXCEPTIONS);
        this.numFileReadExceptions = metricsRegistry.registerMeter(ExceptionMeterNames.NUM_FILE_READ_EXCEPTIONS);
        this.directoryCleaner = new DirectoryCleaner(metricsRegistry);
    }

    /**
     * Enumerate worker log files for given criteria.
     *
     * @param user username
     * @param port worker's port, null for all workers
     * @param topologyId topology ID, null for all topologies
     * @param callback callbackParameterName for JSONP
     * @param origin origin
     * @return list of worker logs for given criteria
     */
    public Response listLogFiles(String user, Integer port, String topologyId, String callback, String origin) throws IOException {
        List<Path> fileResults = null;
        if (topologyId == null) {
            if (port == null) {
                fileResults = workerLogs.getAllLogsForRootDir();
            } else {
                fileResults = new ArrayList<>();

                File[] logRootFiles = logRoot.toFile().listFiles();
                if (logRootFiles != null) {
                    for (File topoDir : logRootFiles) {
                        File[] topoDirFiles = topoDir.listFiles();
                        if (topoDirFiles != null) {
                            for (File portDir : topoDirFiles) {
                                if (portDir.getName().equals(port.toString())) {
                                    fileResults.addAll(directoryCleaner.getFilesForDir(portDir.toPath()));
                                }
                            }
                        }
                    }
                }
            }
        } else {
            if (port == null) {
                fileResults = new ArrayList<>();

                Path topoDir = logRoot.resolve(topologyId).toAbsolutePath().normalize();
                if (!topoDir.startsWith(logRoot)) {
                    return LogviewerResponseBuilder.buildSuccessJsonResponse(Collections.emptyList(), callback, origin);
                }
                if (topoDir.toFile().exists()) {
                    File[] topoDirFiles = topoDir.toFile().listFiles();
                    if (topoDirFiles != null) {
                        for (File portDir : topoDirFiles) {
                            fileResults.addAll(directoryCleaner.getFilesForDir(portDir.toPath()));
                        }
                    }
                }

            } else {
                File portDir = ConfigUtils.getWorkerDirFromRoot(logRoot.toString(), topologyId, port).getCanonicalFile();
                if (!portDir.getPath().startsWith(logRoot.toString())) {
                    return LogviewerResponseBuilder.buildSuccessJsonResponse(Collections.emptyList(), callback, origin);
                }
                if (portDir.exists()) {
                    fileResults = directoryCleaner.getFilesForDir(portDir.toPath());
                }
            }
        }

        List<String> files;
        if (fileResults != null) {
            files = fileResults.stream()
                    .map(WorkerLogs::getTopologyPortWorkerLog)
                    .sorted().collect(toList());
        } else {
            files = new ArrayList<>();
        }

        return LogviewerResponseBuilder.buildSuccessJsonResponse(files, callback, origin);
    }

    /**
     * Provides a worker log file to view, starting from the specified position
     * or default starting position of the most recent page.
     *
     * @param fileName file to view
     * @param start start offset, or null if the most recent page is desired
     * @param length length to read in this page, or null if default page length is desired
     * @param grep search string if request is a result of the search, can be null
     * @param user username
     * @return HTML view page of worker log
     */
    public Response logPage(String fileName, Integer start, Integer length, String grep, String user)
            throws IOException, InvalidRequestException {
        Path rawFile = logRoot.resolve(fileName);
        Path absFile = rawFile.toAbsolutePath().normalize();
        if (!absFile.startsWith(logRoot) || !rawFile.normalize().toString().equals(rawFile.toString())) {
            //Ensure filename doesn't contain ../ parts 
            return LogviewerResponseBuilder.buildResponsePageNotFound();
        }
        
        if (resourceAuthorizer.isUserAllowedToAccessFile(user, fileName)) {
            workerLogs.setLogFilePermission(fileName);

            Path topoDir = absFile.getParent().getParent();
            if (absFile.toFile().exists()) {
                SortedSet<Path> logFiles;
                try {
                    logFiles = Arrays.stream(topoDir.toFile().listFiles())
                        .flatMap(Unchecked.function(portDir -> directoryCleaner.getFilesForDir(portDir.toPath()).stream()))
                        .filter(Files::isRegularFile)
                            .collect(toCollection(TreeSet::new));
                } catch (UncheckedIOException e) {
                    throw e.getCause();
                }

                List<String> reorderedFilesStr = logFiles.stream()
                        .map(WorkerLogs::getTopologyPortWorkerLog)
                        .filter(fileStr -> !StringUtils.equals(fileName, fileStr))
                        .collect(toList());
                reorderedFilesStr.add(fileName);

                length = length != null ? Math.min(10485760, length) : LogviewerConstant.DEFAULT_BYTES_PER_PAGE;
                final boolean isZipFile = absFile.getFileName().toString().endsWith(".gz");
                long fileLength = getFileLength(absFile.toFile(), isZipFile);
                if (start == null) {
                    start = Long.valueOf(fileLength - length).intValue();
                }

                String logString = isTxtFile(fileName) ? escapeHtml(pageFile(absFile.toString(), isZipFile, fileLength, start, length)) :
                    escapeHtml("This is a binary file and cannot display! You may download the full file.");

                List<DomContent> bodyContents = new ArrayList<>();
                if (StringUtils.isNotEmpty(grep)) {
                    String matchedString = String.join("\n", Arrays.stream(logString.split("\n"))
                            .filter(str -> str.contains(grep)).collect(toList()));
                    bodyContents.add(pre(matchedString).withId("logContent"));
                } else {
                    DomContent pagerData = null;
                    if (isTxtFile(fileName)) {
                        pagerData = pagerLinks(fileName, start, length, Long.valueOf(fileLength).intValue(), "log");
                    }

                    bodyContents.add(searchFileForm(fileName, "no"));
                    // list all files for this topology
                    bodyContents.add(logFileSelectionForm(reorderedFilesStr, fileName, "log"));
                    if (pagerData != null) {
                        bodyContents.add(pagerData);
                    }
                    bodyContents.add(downloadLink(fileName));
                    bodyContents.add(pre(logString).withClass("logContent"));
                    if (pagerData != null) {
                        bodyContents.add(pagerData);
                    }
                }

                String content = logTemplate(bodyContents, fileName, user).render();
                return LogviewerResponseBuilder.buildSuccessHtmlResponse(content);
            } else {
                return LogviewerResponseBuilder.buildResponsePageNotFound();
            }
        } else {
            if (resourceAuthorizer.getLogUserGroupWhitelist(fileName) == null) {
                return LogviewerResponseBuilder.buildResponsePageNotFound();
            } else {
                return LogviewerResponseBuilder.buildResponseUnauthorizedUser(user);
            }
        }
    }

    /**
     * Provides a daemon log file to view.
     *
     * @param fileName file to view
     * @param start start offset, or null if the most recent page is desired
     * @param length length to read in this page, or null if default page length is desired
     * @param grep search string if request is a result of the search, can be null
     * @param user username
     * @return HTML view page of daemon log
     */
    public Response daemonLogPage(String fileName, Integer start, Integer length, String grep, String user)
            throws IOException, InvalidRequestException {
        Path file = daemonLogRoot.resolve(fileName).toAbsolutePath().normalize();
        if (!file.startsWith(daemonLogRoot) || Paths.get(fileName).getNameCount() != 1) {
            //Prevent fileName from pathing into worker logs, or outside daemon log root 
            return LogviewerResponseBuilder.buildResponsePageNotFound();
        }

        if (file.toFile().exists()) {
            // all types of files included
            List<File> logFiles = Arrays.stream(daemonLogRoot.toFile().listFiles())
                    .filter(File::isFile)
                    .collect(toList());

            List<String> reorderedFilesStr = logFiles.stream()
                    .map(File::getName)
                    .filter(fName -> !StringUtils.equals(fileName, fName))
                    .collect(toList());
            reorderedFilesStr.add(fileName);

            length = length != null ? Math.min(10485760, length) : LogviewerConstant.DEFAULT_BYTES_PER_PAGE;
            final boolean isZipFile = file.getFileName().toString().endsWith(".gz");
            long fileLength = getFileLength(file.toFile(), isZipFile);
            if (start == null) {
                start = Long.valueOf(fileLength - length).intValue();
            }

            String logString = isTxtFile(fileName) ? escapeHtml(pageFile(file.toString(), isZipFile, fileLength, start, length)) :
                    escapeHtml("This is a binary file and cannot display! You may download the full file.");

            List<DomContent> bodyContents = new ArrayList<>();
            if (StringUtils.isNotEmpty(grep)) {
                String matchedString = String.join("\n", Arrays.stream(logString.split("\n"))
                        .filter(str -> str.contains(grep)).collect(toList()));
                bodyContents.add(pre(matchedString).withId("logContent"));
            } else {
                DomContent pagerData = null;
                if (isTxtFile(fileName)) {
                    pagerData = pagerLinks(fileName, start, length, Long.valueOf(fileLength).intValue(), "daemonlog");
                }

                bodyContents.add(searchFileForm(fileName, "yes"));
                // list all daemon logs
                bodyContents.add(logFileSelectionForm(reorderedFilesStr, fileName, "daemonlog"));
                if (pagerData != null) {
                    bodyContents.add(pagerData);
                }
                bodyContents.add(daemonDownloadLink(fileName));
                bodyContents.add(pre(logString).withClass("logContent"));
                if (pagerData != null) {
                    bodyContents.add(pagerData);
                }
            }

            String content = logTemplate(bodyContents, fileName, user).render();
            return LogviewerResponseBuilder.buildSuccessHtmlResponse(content);
        } else {
            return LogviewerResponseBuilder.buildResponsePageNotFound();
        }
    }

    private long getFileLength(File file, boolean isZipFile) throws IOException {
        try {
            return isZipFile ? ServerUtils.zipFileSize(file) : file.length();
        } catch (FileNotFoundException e) {
            numFileOpenExceptions.mark();
            throw e;
        } catch (IOException e) {
            numFileReadExceptions.mark();
            throw e;
        }
    }

    private DomContent logTemplate(List<DomContent> bodyContents, String fileName, String user) {
        List<DomContent> finalBodyContents = new ArrayList<>();

        if (StringUtils.isNotBlank(user)) {
            finalBodyContents.add(div(p("User: " + user)).withClass("ui-user"));
        }

        finalBodyContents.add(div(p("Note: the drop-list shows at most 1024 files for each worker directory.")).withClass("ui-note"));
        finalBodyContents.add(h3(escapeHtml(fileName)));
        finalBodyContents.addAll(bodyContents);

        return html(
                head(
                        title(escapeHtml(fileName) + " - Storm Log Viewer"),
                        link().withRel("stylesheet").withHref("/css/bootstrap-3.3.1.min.css"),
                        link().withRel("stylesheet").withHref("/css/jquery.dataTables.1.10.4.min.css"),
                        link().withRel("stylesheet").withHref("/css/style.css")
                ),
                body(
                        finalBodyContents.toArray(new DomContent[]{})
                )
        );
    }

    private DomContent downloadLink(String fileName) {
        return p(linkTo(UIHelpers.urlFormat("/api/v1/download?file=%s", fileName), "Download Full File"));
    }

    private DomContent daemonDownloadLink(String fileName) {
        return p(linkTo(UIHelpers.urlFormat("/api/v1/daemondownload?file=%s", fileName), "Download Full File"));
    }

    private DomContent linkTo(String url, String content) {
        return a(content).withHref(url);
    }

    private DomContent logFileSelectionForm(List<String> logFiles, String selectedFile, String type) {
        return form(
                dropDown("file", logFiles, selectedFile),
                input().withType("submit").withValue("Switch file")
        ).withAction(type).withId("list-of-files");
    }

    private DomContent dropDown(String name, List<String> logFiles, String selectedFile) {
        List<DomContent> options = logFiles.stream()
                .map(file -> option(file).condAttr(file.equals(selectedFile), "selected", "selected"))
                .collect(toList());
        return select(options.toArray(new DomContent[]{})).withName(name).withId(name).withValue(selectedFile);
    }

    private DomContent searchFileForm(String fileName, String isDaemonValue) {
        return form(
                text("search this file:"),
                input().withType("text").withName("search"),
                input().withType("hidden").withName("is-daemon").withValue(isDaemonValue),
                input().withType("hidden").withName("file").withValue(fileName),
                input().withType("submit").withValue("Search")
        ).withAction("/logviewer_search.html").withId("search-box");
    }

    private DomContent pagerLinks(String fileName, Integer start, Integer length, Integer fileLength, String type) {
        Map<String, Object> urlQueryParams = new HashMap<>();
        urlQueryParams.put("file", fileName);
        urlQueryParams.put("start", Math.max(0, start - length));
        urlQueryParams.put("length", length);

        List<DomContent> btnLinks = new ArrayList<>();

        int prevStart = Math.max(0, start - length);
        btnLinks.add(toButtonLink(UrlBuilder.build("/api/v1/" + type, urlQueryParams), "Prev", prevStart < start));

        urlQueryParams.clear();
        urlQueryParams.put("file", fileName);
        urlQueryParams.put("start", 0);
        urlQueryParams.put("length", length);

        btnLinks.add(toButtonLink(UrlBuilder.build("/api/v1/" + type, urlQueryParams), "First"));

        urlQueryParams.clear();
        urlQueryParams.put("file", fileName);
        urlQueryParams.put("length", length);

        btnLinks.add(toButtonLink(UrlBuilder.build("/api/v1/" + type, urlQueryParams), "Last"));

        urlQueryParams.clear();
        urlQueryParams.put("file", fileName);
        urlQueryParams.put("start", Math.min(Math.max(0, fileLength - length), start + length));
        urlQueryParams.put("length", length);

        int nextStart = fileLength > 0 ? Math.min(Math.max(0, fileLength - length), start + length) : start + length;
        btnLinks.add(toButtonLink(UrlBuilder.build("/api/v1/" + type, urlQueryParams), "Next", nextStart > start));

        return div(btnLinks.toArray(new DomContent[]{}));
    }

    private DomContent toButtonLink(String url, String text) {
        return toButtonLink(url, text, true);
    }

    private DomContent toButtonLink(String url, String text, boolean enabled) {
        return a(text).withHref(url).withClass("btn btn-default " + (enabled ? "enabled" : "disabled"));
    }

    private String pageFile(String path, boolean isZipFile, long fileLength, Integer start, Integer readLength)
        throws IOException, InvalidRequestException {
        try (InputStream input = isZipFile ? new GZIPInputStream(new FileInputStream(path)) : new FileInputStream(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (start >= fileLength) {
                throw new InvalidRequestException("Cannot start past the end of the file");
            }
            if (start > 0) {
                StreamUtil.skipBytes(input, start);
            }

            byte[] buffer = new byte[1024];
            while (output.size() < readLength) {
                int size = input.read(buffer, 0, Math.min(1024, readLength - output.size()));
                if (size > 0) {
                    output.write(buffer, 0, size);
                } else {
                    break;
                }
            }

            numPageRead.mark();
            return output.toString();
        } catch (FileNotFoundException e) {
            numFileOpenExceptions.mark();
            throw e;
        } catch (IOException e) {
            numFileReadExceptions.mark();
            throw e;
        }
    }

    private boolean isTxtFile(String fileName) {
        Pattern p = Pattern.compile("\\.(log.*|txt|yaml|pid)$");
        Matcher matcher = p.matcher(fileName);
        return matcher.find();
    }
}
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

package org.apache.storm.daemon.logviewer.handler;

import static java.util.stream.Collectors.toList;
import static org.apache.storm.daemon.utils.ListFunctionalSupport.drop;
import static org.apache.storm.daemon.utils.ListFunctionalSupport.first;
import static org.apache.storm.daemon.utils.ListFunctionalSupport.last;
import static org.apache.storm.daemon.utils.ListFunctionalSupport.rest;
import static org.apache.storm.daemon.utils.PathUtil.truncatePathToLastElements;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import javax.ws.rs.core.Response;

import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.storm.DaemonConfig;
import org.apache.storm.daemon.common.JsonResponseBuilder;
import org.apache.storm.daemon.logviewer.LogviewerConstant;
import org.apache.storm.daemon.logviewer.utils.DirectoryCleaner;
import org.apache.storm.daemon.logviewer.utils.ExceptionMeterNames;
import org.apache.storm.daemon.logviewer.utils.LogviewerResponseBuilder;
import org.apache.storm.daemon.logviewer.utils.ResourceAuthorizer;
import org.apache.storm.daemon.logviewer.utils.WorkerLogs;
import org.apache.storm.daemon.supervisor.SupervisorUtils;
import org.apache.storm.daemon.ui.InvalidRequestException;
import org.apache.storm.daemon.utils.StreamUtil;
import org.apache.storm.daemon.utils.UrlBuilder;
import org.apache.storm.metric.StormMetricsRegistry;
import org.apache.storm.utils.ObjectReader;
import org.apache.storm.utils.ServerUtils;
import org.apache.storm.utils.Utils;
import org.jooq.lambda.Unchecked;
import org.json.simple.JSONAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogviewerLogSearchHandler {

    private static final Logger LOG = LoggerFactory.getLogger(LogviewerLogSearchHandler.class);
    public static final int GREP_MAX_SEARCH_SIZE = 1024;
    public static final int GREP_BUF_SIZE = 2048;
    public static final int GREP_CONTEXT_SIZE = 128;
    public static final Pattern WORKER_LOG_FILENAME_PATTERN = Pattern.compile("^worker.log(.*)");

    private final Meter numDeepSearchNoResult;
    private final Histogram numFileScanned;
    private final Meter numSearchRequestNoResult;
    private final Meter numFileOpenExceptions;
    private final Meter numFileReadExceptions;

    private final Map<String, Object> stormConf;
    private final Path logRoot;
    private final Path daemonLogRoot;
    private final ResourceAuthorizer resourceAuthorizer;
    private final Integer logviewerPort;
    private final String scheme;
    private final DirectoryCleaner directoryCleaner;

    /**
     * Constructor.
     *
     * @param stormConf storm configuration
     * @param logRoot log root directory
     * @param daemonLogRoot daemon log root directory
     * @param resourceAuthorizer {@link ResourceAuthorizer}
     * @param metricsRegistry The logviewer metrics registry
     */
    public LogviewerLogSearchHandler(Map<String, Object> stormConf, Path logRoot, Path daemonLogRoot,
        ResourceAuthorizer resourceAuthorizer, StormMetricsRegistry metricsRegistry) {
        this.stormConf = stormConf;
        this.logRoot = logRoot.toAbsolutePath().normalize();
        this.daemonLogRoot = daemonLogRoot.toAbsolutePath().normalize();
        this.resourceAuthorizer = resourceAuthorizer;
        Object httpsPort = stormConf.get(DaemonConfig.LOGVIEWER_HTTPS_PORT);
        if (httpsPort == null) {
            this.logviewerPort = ObjectReader.getInt(stormConf.get(DaemonConfig.LOGVIEWER_PORT));
            this.scheme = "http";
        } else {
            this.logviewerPort = ObjectReader.getInt(httpsPort);
            this.scheme = "https";
        }
        this.numDeepSearchNoResult = metricsRegistry.registerMeter("logviewer:num-deep-search-no-result");
        this.numFileScanned = metricsRegistry.registerHistogram("logviewer:num-files-scanned-per-deep-search");
        this.numSearchRequestNoResult = metricsRegistry.registerMeter("logviewer:num-search-request-no-result");
        this.numFileOpenExceptions = metricsRegistry.registerMeter(ExceptionMeterNames.NUM_FILE_OPEN_EXCEPTIONS);
        this.numFileReadExceptions = metricsRegistry.registerMeter(ExceptionMeterNames.NUM_FILE_READ_EXCEPTIONS);
        this.directoryCleaner = new DirectoryCleaner(metricsRegistry);
    }

    /**
     * Search from a worker log file.
     *
     * @param fileName log file
     * @param user username
     * @param isDaemon whether the log file is regarding worker or daemon
     * @param search search string
     * @param numMatchesStr the count of maximum matches
     * @param offsetStr start offset for log file
     * @param callback callbackParameterName for JSONP
     * @param origin origin
     * @return Response containing JSON content representing search result
     */
    public Response searchLogFile(String fileName, String user, boolean isDaemon, String search,
        String numMatchesStr, String offsetStr, String callback, String origin)
        throws IOException, InvalidRequestException {
        boolean noResult = true;

        Path rootDir = isDaemon ? daemonLogRoot : logRoot;
        Path rawFile = rootDir.resolve(fileName);
        Path absFile = rawFile.toAbsolutePath().normalize();
        if (!absFile.startsWith(rootDir) || !rawFile.normalize().toString().equals(rawFile.toString())) {
            //Ensure filename doesn't contain ../ parts 
            return searchLogFileNotFound(callback);
        }
        if (isDaemon && Paths.get(fileName).getNameCount() != 1) {
            //Don't permit path traversal for calls intended to read from the daemon logs
            return searchLogFileNotFound(callback);
        }
        Response response;
        if (absFile.toFile().exists()) {
            if (isDaemon || resourceAuthorizer.isUserAllowedToAccessFile(user, fileName)) {
                Integer numMatchesInt = numMatchesStr != null ? tryParseIntParam("num-matches", numMatchesStr) : null;
                Integer offsetInt = offsetStr != null ? tryParseIntParam("start-byte-offset", offsetStr) : null;

                try {
                    if (StringUtils.isNotEmpty(search) && search.getBytes("UTF-8").length <= GREP_MAX_SEARCH_SIZE) {
                        Map<String, Object> entity = new HashMap<>();
                        entity.put("isDaemon", isDaemon ? "yes" : "no");
                        Map<String, Object> res = substringSearch(absFile, search, isDaemon, numMatchesInt, offsetInt);
                        entity.putAll(res);
                        noResult = ((List) res.get("matches")).isEmpty();

                        response = LogviewerResponseBuilder.buildSuccessJsonResponse(entity, callback, origin);
                    } else {
                        throw new InvalidRequestException("Search substring must be between 1 and 1024 "
                            + "UTF-8 bytes in size (inclusive)");
                    }
                } catch (Exception ex) {
                    response = LogviewerResponseBuilder.buildExceptionJsonResponse(ex, callback);
                }
            } else {
                // unauthorized
                response = LogviewerResponseBuilder.buildUnauthorizedUserJsonResponse(user, callback);
            }
        } else {
            response = searchLogFileNotFound(callback);
        }

        if (noResult) {
            numSearchRequestNoResult.mark();
        }
        return response;
    }

    private Response searchLogFileNotFound(String callback) {
        Map<String, String> entity = new HashMap<>();
        entity.put("error", "Not Found");
        entity.put("errorMessage", "The file was not found on this node.");

        return new JsonResponseBuilder().setData(entity).setCallback(callback).setStatus(404).build();
    }

    /**
     * Advanced search across worker log files in a topology.
     *
     * @param topologyId topology ID
     * @param user username
     * @param search search string
     * @param numMatchesStr the count of maximum matches. Note that this number is with respect to each port, not to each log or each search
     *     request
     * @param portStr worker port, null or '*' if the request wants to search from all worker logs
     * @param fileOffsetStr index (offset) of the log files
     * @param offsetStr start offset for log file
     * @param searchArchived true if the request wants to search also archived files, false if not
     * @param callback callbackParameterName for JSONP
     * @param origin origin
     * @return Response containing JSON content representing search result
     */
    public Response deepSearchLogsForTopology(String topologyId, String user, String search,
        String numMatchesStr, String portStr, String fileOffsetStr, String offsetStr,
        Boolean searchArchived, String callback, String origin) throws IOException {
        int numMatchedFiles = 0;
        int numScannedFiles = 0;

        Path rootDir = logRoot;
        Path absTopoDir = rootDir.resolve(topologyId).toAbsolutePath().normalize();
        Object returnValue;
        if (StringUtils.isEmpty(search) || !absTopoDir.toFile().exists() || !absTopoDir.startsWith(rootDir)) {
            returnValue = new ArrayList<>();
        } else {
            int fileOffset = ObjectReader.getInt(fileOffsetStr, 0);
            int offset = ObjectReader.getInt(offsetStr, 0);
            int numMatches = ObjectReader.getInt(numMatchesStr, 1);

            if (StringUtils.isEmpty(portStr) || portStr.equals("*")) {
                try (Stream<Path> topoDir = Files.list(absTopoDir)) {
                    // check for all ports
                    Stream<List<Path>> portsOfLogs = topoDir
                        .map(portDir -> logsForPort(user, portDir))
                        .filter(logs -> logs != null && !logs.isEmpty());

                    if (BooleanUtils.isNotTrue(searchArchived)) {
                        portsOfLogs = portsOfLogs.map(fl -> Collections.singletonList(first(fl)));
                    }

                    final List<Matched> matchedList = portsOfLogs
                        .map(logs -> findNMatches(logs, numMatches, 0, 0, search))
                        .collect(toList());
                    numMatchedFiles = matchedList.stream().mapToInt(match -> match.getMatches().size()).sum();
                    numScannedFiles = matchedList.stream().mapToInt(match -> match.openedFiles).sum();
                    returnValue = matchedList;
                }
            } else {
                int port = Integer.parseInt(portStr);
                // check just the one port
                @SuppressWarnings("unchecked")
                List<Integer> slotsPorts = SupervisorUtils.getSlotsPorts(stormConf);
                boolean containsPort = slotsPorts.stream()
                    .anyMatch(slotPort -> slotPort != null && (slotPort == port));
                if (!containsPort) {
                    returnValue = new ArrayList<>();
                } else {
                    Path absPortDir = absTopoDir.resolve(Integer.toString(port)).toAbsolutePath().normalize();

                    if (!absPortDir.toFile().exists()
                        || !absPortDir.startsWith(absTopoDir)) {
                        returnValue = new ArrayList<>();
                    } else {
                        List<Path> filteredLogs = logsForPort(user, absPortDir);
                        if (BooleanUtils.isNotTrue(searchArchived)) {
                            filteredLogs = Collections.singletonList(first(filteredLogs));
                            fileOffset = 0;
                        }
                        returnValue = findNMatches(filteredLogs, numMatches, fileOffset, offset, search);
                        numMatchedFiles = ((Matched) returnValue).getMatches().size();
                        numScannedFiles = ((Matched) returnValue).openedFiles;
                    }
                }
            }
        }

        if (numMatchedFiles == 0) {
            numDeepSearchNoResult.mark();
        }
        numFileScanned.update(numScannedFiles);
        return LogviewerResponseBuilder.buildSuccessJsonResponse(returnValue, callback, origin);
    }

    private Integer tryParseIntParam(String paramName, String value) throws InvalidRequestException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("Could not parse " + paramName + " to an integer");
        }
    }

    @VisibleForTesting
    Map<String, Object> substringSearch(Path file, String searchString) throws InvalidRequestException {
        return substringSearch(file, searchString, false, 10, 0);
    }

    @VisibleForTesting
    Map<String, Object> substringSearch(Path file, String searchString, int numMatches) throws InvalidRequestException {
        return substringSearch(file, searchString, false, numMatches, 0);
    }

    @VisibleForTesting
    Map<String, Object> substringSearch(Path file,
            String searchString,
            int numMatches,
            int startByteOffset) throws InvalidRequestException {
        return substringSearch(file, searchString, false, numMatches, startByteOffset);
    }

    private Map<String, Object> substringSearch(Path file, String searchString, boolean isDaemon, Integer numMatches,
        Integer startByteOffset) throws InvalidRequestException {
        if (StringUtils.isEmpty(searchString)) {
            throw new IllegalArgumentException("Precondition fails: search string should not be empty.");
        }
        if (searchString.getBytes(StandardCharsets.UTF_8).length > GREP_MAX_SEARCH_SIZE) {
            throw new IllegalArgumentException("Precondition fails: the length of search string should be less than "
                + GREP_MAX_SEARCH_SIZE);
        }

        boolean isZipFile = file.toString().endsWith(".gz");
        try (InputStream fis = Files.newInputStream(file)) {
            try (InputStream gzippedInputStream = isZipFile ? new GZIPInputStream(fis) : fis;
                BufferedInputStream stream = new BufferedInputStream(gzippedInputStream)) {

                //It's more likely to be a file read exception here, so we don't differentiate
                int fileLength = isZipFile ? (int) ServerUtils.zipFileSize(file.toFile()) : (int) Files.size(file);

                ByteBuffer buf = ByteBuffer.allocate(GREP_BUF_SIZE);
                final byte[] bufArray = buf.array();
                final byte[] searchBytes = searchString.getBytes(StandardCharsets.UTF_8);
                numMatches = numMatches != null ? numMatches : 10;
                startByteOffset = startByteOffset != null ? startByteOffset : 0;

                // Start at the part of the log file we are interested in.
                // Allow searching when start-byte-offset == file-len so it doesn't blow up on 0-length files
                if (startByteOffset > fileLength) {
                    throw new InvalidRequestException("Cannot search past the end of the file");
                }

                if (startByteOffset > 0) {
                    StreamUtil.skipBytes(stream, startByteOffset);
                }

                Arrays.fill(bufArray, (byte) 0);

                int totalBytesRead = 0;
                int bytesRead = stream.read(bufArray, 0, Math.min(fileLength, GREP_BUF_SIZE));
                buf.limit(bytesRead);
                totalBytesRead += bytesRead;

                List<Map<String, Object>> initialMatches = new ArrayList<>();
                int initBufOffset = 0;
                int byteOffset = startByteOffset;
                byte[] beforeBytes = null;

                Map<String, Object> ret = new HashMap<>();
                while (true) {
                    SubstringSearchResult searchRet = bufferSubstringSearch(isDaemon, file, fileLength, byteOffset, initBufOffset,
                        stream, startByteOffset, totalBytesRead, buf, searchBytes, initialMatches, numMatches, beforeBytes);

                    List<Map<String, Object>> matches = searchRet.getMatches();
                    Integer newByteOffset = searchRet.getNewByteOffset();
                    byte[] newBeforeBytes = searchRet.getNewBeforeBytes();

                    if (matches.size() < numMatches && totalBytesRead + startByteOffset < fileLength) {
                        // The start index is positioned to find any possible
                        // occurrence search string that did not quite fit in the
                        // buffer on the previous read.
                        final int newBufOffset = Math.min(buf.limit(), GREP_MAX_SEARCH_SIZE) - searchBytes.length;

                        totalBytesRead = rotateGrepBuffer(buf, stream, totalBytesRead, fileLength);
                        if (totalBytesRead < 0) {
                            throw new InvalidRequestException("Cannot search past the end of the file");
                        }

                        initialMatches = matches;
                        initBufOffset = newBufOffset;
                        byteOffset = newByteOffset;
                        beforeBytes = newBeforeBytes;
                    } else {
                        ret.put("isDaemon", isDaemon ? "yes" : "no");
                        Integer nextByteOffset = null;
                        if (matches.size() >= numMatches || totalBytesRead < fileLength) {
                            nextByteOffset = (Integer) last(matches).get("byteOffset") + searchBytes.length;
                            if (fileLength <= nextByteOffset) {
                                nextByteOffset = null;
                            }
                        }
                        ret.putAll(mkGrepResponse(searchBytes, startByteOffset, matches, nextByteOffset));
                        break;
                    }
                }
                return ret;
            } catch (UnknownHostException | UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                numFileReadExceptions.mark();
                throw new RuntimeException(e);
            }
        } catch (IOException e) {
            numFileOpenExceptions.mark();
            throw new RuntimeException(e);
        }
    }

    @VisibleForTesting
    Map<String, Object> substringSearchDaemonLog(Path file, String searchString) throws InvalidRequestException {
        return substringSearch(file, searchString, true, 10, 0);
    }

    /**
     * Get the filtered, authorized, sorted log files for a port.
     */
    @VisibleForTesting
    List<Path> logsForPort(String user, Path portDir) {
        try {
            List<Path> workerLogs = directoryCleaner.getFilesForDir(portDir).stream()
                .filter(file -> WORKER_LOG_FILENAME_PATTERN.asPredicate().test(file.getFileName().toString()))
                .collect(toList());

            return workerLogs.stream()
                .filter(log -> resourceAuthorizer.isUserAllowedToAccessFile(user, WorkerLogs.getTopologyPortWorkerLog(log)))
                .map(Unchecked.function(p -> Pair.of(p, Files.getLastModifiedTime(p))))
                .sorted(Comparator.comparing((Pair<Path, FileTime> p) -> p.getRight()).reversed())
                .map(p -> p.getLeft())
                .collect(toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Find the first N matches of target string in files.
     *
     * @param logs all candidate log files to search
     * @param numMatches number of matches expected
     * @param fileOffset number of log files to skip initially
     * @param startByteOffset number of byte to be ignored in each log file
     * @param targetStr searched string
     * @return all matched results
     */
    @VisibleForTesting
    Matched findNMatches(List<Path> logs, int numMatches, int fileOffset, int startByteOffset, String targetStr) {
        logs = drop(logs, fileOffset);
        LOG.debug("{} files to scan", logs.size());

        List<Map<String, Object>> matches = new ArrayList<>();
        int matchCount = 0;
        int scannedFiles = 0;

        while (true) {
            if (logs.isEmpty()) {
                //fileOffset = one past last scanned file
                break;
            }

            Path firstLog = logs.get(0);
            Map<String, Object> matchInLog;
            try {
                LOG.debug("Looking through {}", firstLog);
                matchInLog = substringSearch(firstLog, targetStr, numMatches - matchCount, startByteOffset);
                scannedFiles++;
            } catch (InvalidRequestException e) {
                LOG.error("Can't search past end of file.", e);
                matchInLog = new HashMap<>();
            }

            String fileName = WorkerLogs.getTopologyPortWorkerLog(firstLog);

            //This section simply put the formatted log filename and corresponding port in the matching.
            final List<Map<String, Object>> newMatches = new ArrayList<>(matches);
            Map<String, Object> currentFileMatch = new HashMap<>(matchInLog);
            currentFileMatch.put("fileName", fileName);
            Path firstLogAbsPath = firstLog.toAbsolutePath().normalize();
            currentFileMatch.put("port", truncatePathToLastElements(firstLogAbsPath, 2).getName(0).toString());
            newMatches.add(currentFileMatch);

            int newCount = matchCount + ((List<?>) matchInLog.getOrDefault("matches", Collections.emptyList())).size();
            if (newCount == matchCount) {
                // matches and matchCount is not changed
                logs = rest(logs);
                startByteOffset = 0;
                fileOffset = fileOffset + 1;
            } else if (newCount >= numMatches) {
                matches = newMatches;
                //fileOffset = the index of last scanned file
                break;
            } else {
                matches = newMatches;
                logs = rest(logs);
                startByteOffset = 0;
                fileOffset = fileOffset + 1;
                matchCount = newCount;
            }
        }

        LOG.debug("scanned {} files", scannedFiles);
        return new Matched(fileOffset, targetStr, matches, scannedFiles);
    }

    /**
     * As the file is read into a buffer, 1/2 the buffer's size at a time, we search the buffer for matches of the substring and return a
     * list of zero or more matches.
     */
    private SubstringSearchResult bufferSubstringSearch(boolean isDaemon, Path file, int fileLength, int offsetToBuf,
        int initBufOffset, BufferedInputStream stream, Integer bytesSkipped,
        int bytesRead, ByteBuffer haystack, byte[] needle,
        List<Map<String, Object>> initialMatches, Integer numMatches, byte[] beforeBytes)
        throws IOException {
        int bufOffset = initBufOffset;
        List<Map<String, Object>> matches = initialMatches;

        byte[] newBeforeBytes;
        Integer newByteOffset;

        while (true) {
            int offset = offsetOfBytes(haystack.array(), needle, bufOffset);
            if (matches.size() < numMatches && offset >= 0) {
                final int fileOffset = offsetToBuf + offset;
                final int bytesNeededAfterMatch = haystack.limit() - GREP_CONTEXT_SIZE - needle.length;

                byte[] beforeArg = null;
                byte[] afterArg = null;
                if (offset < GREP_CONTEXT_SIZE) {
                    beforeArg = beforeBytes;
                }

                if (offset > bytesNeededAfterMatch) {
                    afterArg = tryReadAhead(stream, haystack, offset, fileLength, bytesRead);
                }

                bufOffset = offset + needle.length;
                matches.add(mkMatchData(needle, haystack, offset, fileOffset,
                    file.toAbsolutePath().normalize(), isDaemon, beforeArg, afterArg));
            } else {
                int beforeStrToOffset = Math.min(haystack.limit(), GREP_MAX_SEARCH_SIZE);
                int beforeStrFromOffset = Math.max(0, beforeStrToOffset - GREP_CONTEXT_SIZE);
                newBeforeBytes = Arrays.copyOfRange(haystack.array(), beforeStrFromOffset, beforeStrToOffset);

                // It's OK if new-byte-offset is negative.
                // This is normal if we are out of bytes to read from a small file.
                if (matches.size() >= numMatches) {
                    newByteOffset = ((Number) last(matches).get("byteOffset")).intValue() + needle.length;
                } else {
                    newByteOffset = bytesSkipped + bytesRead - GREP_MAX_SEARCH_SIZE;
                }

                break;
            }
        }

        return new SubstringSearchResult(matches, newByteOffset, newBeforeBytes);
    }

    private int rotateGrepBuffer(ByteBuffer buf, BufferedInputStream stream, int totalBytesRead, int fileLength) throws IOException {
        byte[] bufArray = buf.array();

        // Copy the 2nd half of the buffer to the first half.
        System.arraycopy(bufArray, GREP_MAX_SEARCH_SIZE, bufArray, 0, GREP_MAX_SEARCH_SIZE);

        // Zero-out the 2nd half to prevent accidental matches.
        Arrays.fill(bufArray, GREP_MAX_SEARCH_SIZE, bufArray.length, (byte) 0);

        // Fill the 2nd half with new bytes from the stream.
        int bytesRead = stream.read(bufArray, GREP_MAX_SEARCH_SIZE, Math.min(fileLength, GREP_MAX_SEARCH_SIZE));
        buf.limit(GREP_MAX_SEARCH_SIZE + bytesRead);
        return totalBytesRead + bytesRead;
    }

    private Map<String, Object> mkMatchData(byte[] needle, ByteBuffer haystack, int haystackOffset, int fileOffset, Path canonicalPath,
        boolean isDaemon, byte[] beforeBytes, byte[] afterBytes)
        throws UnsupportedEncodingException, UnknownHostException {
        String url;
        if (isDaemon) {
            url = urlToMatchCenteredInLogPageDaemonFile(needle, canonicalPath, fileOffset, logviewerPort);
        } else {
            url = urlToMatchCenteredInLogPage(needle, canonicalPath, fileOffset, logviewerPort);
        }

        byte[] haystackBytes = haystack.array();
        String beforeString;
        String afterString;

        if (haystackOffset >= GREP_CONTEXT_SIZE) {
            beforeString = new String(haystackBytes, (haystackOffset - GREP_CONTEXT_SIZE), GREP_CONTEXT_SIZE, "UTF-8");
        } else {
            int numDesired = Math.max(0, GREP_CONTEXT_SIZE - haystackOffset);
            int beforeSize = beforeBytes != null ? beforeBytes.length : 0;
            int numExpected = Math.min(beforeSize, numDesired);

            if (numExpected > 0) {
                StringBuilder sb = new StringBuilder();
                sb.append(new String(beforeBytes, beforeSize - numExpected, numExpected, "UTF-8"));
                sb.append(new String(haystackBytes, 0, haystackOffset, "UTF-8"));
                beforeString = sb.toString();
            } else {
                beforeString = new String(haystackBytes, 0, haystackOffset, "UTF-8");
            }
        }

        int needleSize = needle.length;
        int afterOffset = haystackOffset + needleSize;
        int haystackSize = haystack.limit();

        if ((afterOffset + GREP_CONTEXT_SIZE) < haystackSize) {
            afterString = new String(haystackBytes, afterOffset, GREP_CONTEXT_SIZE, "UTF-8");
        } else {
            int numDesired = GREP_CONTEXT_SIZE - (haystackSize - afterOffset);
            int afterSize = afterBytes != null ? afterBytes.length : 0;
            int numExpected = Math.min(afterSize, numDesired);

            if (numExpected > 0) {
                StringBuilder sb = new StringBuilder();
                sb.append(new String(haystackBytes, afterOffset, (haystackSize - afterOffset), "UTF-8"));
                sb.append(new String(afterBytes, 0, numExpected, "UTF-8"));
                afterString = sb.toString();
            } else {
                afterString = new String(haystackBytes, afterOffset, (haystackSize - afterOffset), "UTF-8");
            }
        }

        Map<String, Object> ret = new HashMap<>();
        ret.put("byteOffset", fileOffset);
        ret.put("beforeString", beforeString);
        ret.put("afterString", afterString);
        ret.put("matchString", new String(needle, "UTF-8"));
        ret.put("logviewerURL", url);

        return ret;
    }

    /**
     * Tries once to read ahead in the stream to fill the context and resets the stream to its position before the call.
     */
    private byte[] tryReadAhead(BufferedInputStream stream, ByteBuffer haystack, int offset, int fileLength, int bytesRead)
        throws IOException {
        int numExpected = Math.min(fileLength - bytesRead, GREP_CONTEXT_SIZE);
        byte[] afterBytes = new byte[numExpected];
        stream.mark(numExpected);
        // Only try reading once.
        stream.read(afterBytes, 0, numExpected);
        stream.reset();
        return afterBytes;
    }

    /**
     * Searches a given byte array for a match of a sub-array of bytes. Returns the offset to the byte that matches, or -1 if no match was
     * found.
     */
    private int offsetOfBytes(byte[] buffer, byte[] search, int initOffset) {
        if (search.length <= 0) {
            throw new IllegalArgumentException("Search array should not be empty.");
        }

        if (initOffset < 0) {
            throw new IllegalArgumentException("Start offset shouldn't be negative.");
        }

        int offset = initOffset;
        int candidateOffset = initOffset;
        int valOffset = 0;
        int retOffset = 0;

        while (true) {
            if (search.length - valOffset <= 0) {
                // found
                retOffset = candidateOffset;
                break;
            } else {
                if (offset >= buffer.length) {
                    // We ran out of buffer for the search.
                    retOffset = -1;
                    break;
                } else {
                    if (search[valOffset] != buffer[offset]) {
                        // The match at this candidate offset failed, so start over with the
                        // next candidate byte from the buffer.
                        int newOffset = candidateOffset + 1;

                        offset = newOffset;
                        candidateOffset = newOffset;
                        valOffset = 0;
                    } else {
                        // So far it matches.  Keep going...
                        offset = offset + 1;
                        valOffset = valOffset + 1;
                    }
                }
            }
        }

        return retOffset;
    }

    /**
     * This response data only includes a next byte offset if there is more of the file to read.
     */
    private Map<String, Object> mkGrepResponse(byte[] searchBytes, Integer offset, List<Map<String, Object>> matches,
        Integer nextByteOffset) throws UnsupportedEncodingException {
        Map<String, Object> ret = new HashMap<>();
        ret.put("searchString", new String(searchBytes, "UTF-8"));
        ret.put("startByteOffset", offset);
        ret.put("matches", matches);
        if (nextByteOffset != null) {
            ret.put("nextByteOffset", nextByteOffset);
        }
        return ret;
    }

    @VisibleForTesting
    String urlToMatchCenteredInLogPage(byte[] needle, Path canonicalPath, int offset, Integer port) throws UnknownHostException {
        final String host = Utils.hostname();
        final Path truncatedFilePath = truncatePathToLastElements(canonicalPath, 3);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("file", truncatedFilePath.toString());
        parameters.put("start", Math.max(0, offset - (LogviewerConstant.DEFAULT_BYTES_PER_PAGE / 2) - (needle.length / -2)));
        parameters.put("length", LogviewerConstant.DEFAULT_BYTES_PER_PAGE);

        return UrlBuilder.build(String.format(this.scheme + "://%s:%d/api/v1/log", host, port), parameters);
    }

    @VisibleForTesting
    String urlToMatchCenteredInLogPageDaemonFile(byte[] needle, Path canonicalPath, int offset, Integer port) throws UnknownHostException {
        final String host = Utils.hostname();
        final Path truncatedFilePath = truncatePathToLastElements(canonicalPath, 1);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("file", truncatedFilePath.toString());
        parameters.put("start", Math.max(0, offset - (LogviewerConstant.DEFAULT_BYTES_PER_PAGE / 2) - (needle.length / -2)));
        parameters.put("length", LogviewerConstant.DEFAULT_BYTES_PER_PAGE);

        return UrlBuilder.build(String.format(this.scheme + "://%s:%d/api/v1/daemonlog", host, port), parameters);
    }

    @VisibleForTesting
    public static class Matched implements JSONAware {

        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

        private int fileOffset;
        private String searchString;
        private List<Map<String, Object>> matches;
        @JsonIgnore
        private final int openedFiles;

        /**
         * Constructor.
         *
         * @param fileOffset offset (index) of the files
         * @param searchString search string
         * @param matches map representing matched search result
         * @param openedFiles number of files scanned, used for metrics only
         */
        public Matched(int fileOffset, String searchString, List<Map<String, Object>> matches, int openedFiles) {
            this.fileOffset = fileOffset;
            this.searchString = searchString;
            this.matches = matches;
            this.openedFiles = openedFiles;
        }

        public int getFileOffset() {
            return fileOffset;
        }

        public String getSearchString() {
            return searchString;
        }

        public List<Map<String, Object>> getMatches() {
            return matches;
        }

        @Override
        public String toJSONString() {
            try {
                return OBJECT_MAPPER.writeValueAsString(this);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class SubstringSearchResult {

        private List<Map<String, Object>> matches;
        private Integer newByteOffset;
        private byte[] newBeforeBytes;

        SubstringSearchResult(List<Map<String, Object>> matches, Integer newByteOffset, byte[] newBeforeBytes) {
            this.matches = matches;
            this.newByteOffset = newByteOffset;
            this.newBeforeBytes = newBeforeBytes;
        }

        public List<Map<String, Object>> getMatches() {
            return matches;
        }

        public Integer getNewByteOffset() {
            return newByteOffset;
        }

        public byte[] getNewBeforeBytes() {
            return newBeforeBytes;
        }
    }
}
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

package org.apache.storm.daemon.logviewer.handler;

import static j2html.TagCreator.a;
import static j2html.TagCreator.body;
import static j2html.TagCreator.head;
import static j2html.TagCreator.html;
import static j2html.TagCreator.li;
import static j2html.TagCreator.link;
import static j2html.TagCreator.title;
import static j2html.TagCreator.ul;
import static java.util.stream.Collectors.toList;

import com.codahale.metrics.Meter;
import j2html.tags.DomContent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.ws.rs.core.Response;

import org.apache.commons.lang.StringUtils;
import org.apache.storm.daemon.logviewer.utils.DirectoryCleaner;
import org.apache.storm.daemon.logviewer.utils.ExceptionMeterNames;
import org.apache.storm.daemon.logviewer.utils.LogviewerResponseBuilder;
import org.apache.storm.daemon.logviewer.utils.ResourceAuthorizer;
import org.apache.storm.metric.StormMetricsRegistry;

public class LogviewerProfileHandler {

    public static final String WORKER_LOG_FILENAME = "worker.log";

    private final Meter numFileDownloadExceptions;

    private final Path logRoot;
    private final ResourceAuthorizer resourceAuthorizer;
    private final DirectoryCleaner directoryCleaner;

    /**
     * Constructor.
     *
     * @param logRoot worker log root directory
     * @param resourceAuthorizer {@link ResourceAuthorizer}
     * @param metricsRegistry The logviewer metrisc registry
     */
    public LogviewerProfileHandler(String logRoot, ResourceAuthorizer resourceAuthorizer, StormMetricsRegistry metricsRegistry) {
        this.logRoot = Paths.get(logRoot).toAbsolutePath().normalize();
        this.resourceAuthorizer = resourceAuthorizer;
        this.numFileDownloadExceptions = metricsRegistry.registerMeter(ExceptionMeterNames.NUM_FILE_DOWNLOAD_EXCEPTIONS);
        this.directoryCleaner = new DirectoryCleaner(metricsRegistry);
    }

    /**
     * Enumerate dump (profile) files for given worker.
     *
     * @param topologyId topology ID
     * @param hostPort host and port of worker
     * @param user username
     * @return The HTML page representing list page of dump files
     */
    public Response listDumpFiles(String topologyId, String hostPort, String user) throws IOException {
        String portStr = hostPort.split(":")[1];
        Path rawDir = logRoot.resolve(topologyId).resolve(portStr);
        Path absDir = rawDir.toAbsolutePath().normalize();
        if (!absDir.startsWith(logRoot) || !rawDir.normalize().toString().equals(rawDir.toString())) {
            //Ensure filename doesn't contain ../ parts 
            return LogviewerResponseBuilder.buildResponsePageNotFound();
        }

        if (absDir.toFile().exists()) {
            String workerFileRelativePath = String.join(File.separator, topologyId, portStr, WORKER_LOG_FILENAME);
            if (resourceAuthorizer.isUserAllowedToAccessFile(user, workerFileRelativePath)) {
                String content = buildDumpFileListPage(topologyId, hostPort, absDir.toFile());
                return LogviewerResponseBuilder.buildSuccessHtmlResponse(content);
            } else {
                return LogviewerResponseBuilder.buildResponseUnauthorizedUser(user);
            }
        } else {
            return LogviewerResponseBuilder.buildResponsePageNotFound();
        }
    }

    /**
     * Download a dump file.
     *
     * @param topologyId topology ID
     * @param hostPort host and port of worker
     * @param fileName dump file name
     * @param user username
     * @return a Response which lets browsers download that file.
     */
    public Response downloadDumpFile(String topologyId, String hostPort, String fileName, String user) throws IOException {
        String[] hostPortSplit = hostPort.split(":");
        String host = hostPortSplit[0];
        String portStr = hostPortSplit[1];
        Path rawFile = logRoot.resolve(topologyId).resolve(portStr).resolve(fileName);
        Path absFile = rawFile.toAbsolutePath().normalize();
        if (!absFile.startsWith(logRoot) || !rawFile.normalize().toString().equals(rawFile.toString())) {
            //Ensure filename doesn't contain ../ parts 
            return LogviewerResponseBuilder.buildResponsePageNotFound();
        }

        if (absFile.toFile().exists()) {
            String workerFileRelativePath = String.join(File.separator, topologyId, portStr, WORKER_LOG_FILENAME);
            if (resourceAuthorizer.isUserAllowedToAccessFile(user, workerFileRelativePath)) {
                String downloadedFileName = host + "-" + topologyId + "-" + portStr + "-" + absFile.getFileName();
                return LogviewerResponseBuilder.buildDownloadFile(downloadedFileName, absFile.toFile(), numFileDownloadExceptions);
            } else {
                return LogviewerResponseBuilder.buildResponseUnauthorizedUser(user);
            }
        } else {
            return LogviewerResponseBuilder.buildResponsePageNotFound();
        }
    }

    private String buildDumpFileListPage(String topologyId, String hostPort, File dir) throws IOException {
        List<DomContent> liTags = getProfilerDumpFiles(dir).stream()
            .map(file -> li(a(file).withHref("/api/v1/dumps/" + topologyId + "/" + hostPort + "/" + file)))
            .collect(toList());

        return html(
            head(
                title("File Dumps - Storm Log Viewer"),
                link().withRel("stylesheet").withHref("/css/bootstrap-3.3.1.min.css"),
                link().withRel("stylesheet").withHref("/css/jquery.dataTables.1.10.4.min.css"),
                link().withRel("stylesheet").withHref("/css/style.css")
            ),
            body(
                ul(liTags.toArray(new DomContent[]{}))
            )
        ).render();
    }

    private List<String> getProfilerDumpFiles(File dir) throws IOException {
        List<Path> filesForDir = directoryCleaner.getFilesForDir(dir.toPath());
        return filesForDir.stream()
            .map(path -> path.toFile())
            .filter(file -> {
                String fileName = file.getName();
                return StringUtils.isNotEmpty(fileName)
                    && (fileName.endsWith(".txt") || fileName.endsWith(".jfr") || fileName.endsWith(".bin"));
            }).map(File::getName).collect(toList());
    }

}
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

package org.apache.storm.daemon.logviewer.utils;

import static j2html.TagCreator.body;
import static j2html.TagCreator.h2;
import static javax.ws.rs.core.Response.Status.FORBIDDEN;
import static javax.ws.rs.core.Response.Status.OK;
import static org.apache.commons.lang.StringEscapeUtils.escapeHtml;

import com.codahale.metrics.Meter;
import com.google.common.io.ByteStreams;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.storm.daemon.common.JsonResponseBuilder;
import org.apache.storm.daemon.ui.UIHelpers;

public class LogviewerResponseBuilder {

    private LogviewerResponseBuilder() {
    }

    /**
     * Build a Response object representing success response with HTML entity.
     *
     * @param content HTML entity content, String type
     */
    public static Response buildSuccessHtmlResponse(String content) {
        return Response.status(OK).entity(content)
                .type(MediaType.TEXT_HTML_TYPE).build();
    }

    /**
     * Build a Response object representing success response with JSON entity.
     *
     * @param entity entity object to represent it as JSON
     * @param callback callbackParameterName for JSONP
     * @param origin origin
     */
    public static Response buildSuccessJsonResponse(Object entity, String callback, String origin) {
        return new JsonResponseBuilder().setData(entity).setCallback(callback)
                .setHeaders(LogviewerResponseBuilder.getHeadersForSuccessResponse(origin)).build();
    }

    /**
     * Build a Response object representing download a file.
     *
     * @param contentDispositionName The name to set in the Content-Disposition header
     * @param file file to download
     */
    public static Response buildDownloadFile(String contentDispositionName,
        File file, Meter numFileDownloadExceptions) throws IOException {
        try {
            // do not close this InputStream in method: it will be used from jetty server
            InputStream is = Files.newInputStream(file.toPath());
            return Response.status(OK)
                    .entity(wrapWithStreamingOutput(is))
                    .type(MediaType.APPLICATION_OCTET_STREAM_TYPE)
                    .header("Content-Disposition", "attachment; filename=\"" + contentDispositionName + "\"")
                    .build();
        } catch (IOException e) {
            numFileDownloadExceptions.mark();
            throw e;
        }
    }

    /**
     * Build a Response object representing unauthorized user, with HTML response.
     *
     * @param user username
     */
    public static Response buildResponseUnauthorizedUser(String user) {
        String entity = buildUnauthorizedUserHtml(user);
        return Response.status(FORBIDDEN)
                .entity(entity)
                .type(MediaType.TEXT_HTML_TYPE)
                .build();
    }

    /**
     * Build a Response object representing page not found.
     */
    public static Response buildResponsePageNotFound() {
        return Response.status(404)
                .entity("Page not found")
                .type(MediaType.TEXT_HTML_TYPE)
                .build();
    }

    /**
     * Build a Response object representing unauthorized user, with JSON response.
     *
     * @param user username
     * @param callback callbackParameterName for JSONP
     */
    public static Response buildUnauthorizedUserJsonResponse(String user, String callback) {
        return new JsonResponseBuilder().setData(UIHelpers.unauthorizedUserJson(user))
                .setCallback(callback).setStatus(403).build();
    }

    /**
     * Build a Response object representing exception, with JSON response.
     *
     * @param ex Exception object
     * @param callback callbackParameterName for JSONP
     */
    public static Response buildExceptionJsonResponse(Exception ex, String callback) {
        int statusCode = 500;
        return new JsonResponseBuilder().setData(UIHelpers.exceptionToJson(ex, statusCode))
                .setCallback(callback).setStatus(statusCode).build();
    }

    private static Map<String, Object> getHeadersForSuccessResponse(String origin) {
        Map<String, Object> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", origin);
        headers.put("Access-Control-Allow-Credentials", "true");
        return headers;
    }

    private static String buildUnauthorizedUserHtml(String user) {
        String content = "User '" + escapeHtml(user) + "' is not authorized.";
        return body(h2(content)).render();
    }

    private static StreamingOutput wrapWithStreamingOutput(final InputStream inputStream) {
        return os -> {
            OutputStream wrappedOutputStream = os;
            if (!(os instanceof BufferedOutputStream)) {
                wrappedOutputStream = new BufferedOutputStream(os);
            }

            ByteStreams.copy(inputStream, wrappedOutputStream);

            wrappedOutputStream.flush();
        };
    }

}
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

package org.apache.storm.daemon.logviewer.webapp;

import static org.apache.storm.DaemonConfig.LOGVIEWER_APPENDER_NAME;

import java.io.File;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.storm.daemon.common.AuthorizationExceptionMapper;
import org.apache.storm.daemon.logviewer.handler.LogviewerLogDownloadHandler;
import org.apache.storm.daemon.logviewer.handler.LogviewerLogPageHandler;
import org.apache.storm.daemon.logviewer.handler.LogviewerLogSearchHandler;
import org.apache.storm.daemon.logviewer.handler.LogviewerProfileHandler;
import org.apache.storm.daemon.logviewer.utils.ResourceAuthorizer;
import org.apache.storm.daemon.logviewer.utils.WorkerLogs;
import org.apache.storm.metric.StormMetricsRegistry;
import org.apache.storm.security.auth.IHttpCredentialsPlugin;
import org.apache.storm.security.auth.ServerAuthUtils;
import org.apache.storm.utils.ConfigUtils;
import org.apache.storm.utils.ObjectReader;

@ApplicationPath("")
public class LogviewerApplication extends Application {
    private static Map<String, Object> stormConf;
    private static StormMetricsRegistry metricsRegistry;
    private final Set<Object> singletons = new HashSet<>();

    /**
     * Constructor.
     */
    public LogviewerApplication() {
        String logRoot = ConfigUtils.workerArtifactsRoot(stormConf);
        String daemonLogRoot = logRootDir(ObjectReader.getString(stormConf.get(LOGVIEWER_APPENDER_NAME)));

        ResourceAuthorizer resourceAuthorizer = new ResourceAuthorizer(stormConf);
        WorkerLogs workerLogs = new WorkerLogs(stormConf, Paths.get(logRoot), metricsRegistry);

        LogviewerLogPageHandler logviewer = new LogviewerLogPageHandler(logRoot, daemonLogRoot, workerLogs, resourceAuthorizer,
            metricsRegistry);
        LogviewerProfileHandler profileHandler = new LogviewerProfileHandler(logRoot, resourceAuthorizer, metricsRegistry);
        LogviewerLogDownloadHandler logDownloadHandler = new LogviewerLogDownloadHandler(logRoot, daemonLogRoot,
                workerLogs, resourceAuthorizer, metricsRegistry);
        LogviewerLogSearchHandler logSearchHandler = new LogviewerLogSearchHandler(stormConf, Paths.get(logRoot), Paths.get(daemonLogRoot),
                resourceAuthorizer, metricsRegistry);
        IHttpCredentialsPlugin httpCredsHandler = ServerAuthUtils.getUiHttpCredentialsPlugin(stormConf);

        singletons.add(new LogviewerResource(logviewer, profileHandler, logDownloadHandler, logSearchHandler,
            httpCredsHandler, metricsRegistry));
        singletons.add(new AuthorizationExceptionMapper());
    }
    
    @Override
    public Set<Object> getSingletons() {
        return singletons;
    }

    /**
     * Spot to inject storm configuration before initializing LogviewerApplication instance.
     *
     * @param stormConf storm configuration
     * @param metricRegistry The metrics registry
     */
    public static void setup(Map<String, Object> stormConf, StormMetricsRegistry metricRegistry) {
        LogviewerApplication.stormConf = stormConf;
        LogviewerApplication.metricsRegistry = metricRegistry;
    }

    /**
     * Given an appender name, as configured, get the parent directory of the appender's log file.
     * Note that if anything goes wrong, this will throw an Error and exit.
     */
    private String logRootDir(String appenderName) {
        Appender appender = ((LoggerContext) LogManager.getContext()).getConfiguration().getAppender(appenderName);
        if (appenderName != null && appender != null && RollingFileAppender.class.isInstance(appender)) {
            return new File(((RollingFileAppender) appender).getFileName()).getParent();
        } else {
            throw new RuntimeException("Log viewer could not find configured appender, or the appender is not a FileAppender. "
                    + "Please check that the appender name configured in storm and log4j agree.");
        }
    }

}
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

package org.apache.storm.daemon.logviewer.webapp;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;

import java.io.IOException;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.storm.daemon.common.JsonResponseBuilder;
import org.apache.storm.daemon.logviewer.handler.LogviewerLogDownloadHandler;
import org.apache.storm.daemon.logviewer.handler.LogviewerLogPageHandler;
import org.apache.storm.daemon.logviewer.handler.LogviewerLogSearchHandler;
import org.apache.storm.daemon.logviewer.handler.LogviewerProfileHandler;
import org.apache.storm.daemon.logviewer.utils.ExceptionMeterNames;
import org.apache.storm.daemon.ui.InvalidRequestException;
import org.apache.storm.daemon.ui.UIHelpers;
import org.apache.storm.daemon.ui.resources.StormApiResource;
import org.apache.storm.metric.StormMetricsRegistry;
import org.apache.storm.security.auth.IHttpCredentialsPlugin;
import org.apache.storm.utils.Utils;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles HTTP requests for Logviewer.
 */
@Path("/")
public class LogviewerResource {
    private static final Logger LOG = LoggerFactory.getLogger(LogviewerResource.class);

    private final Meter meterLogPageHttpRequests;
    private final Meter meterDaemonLogPageHttpRequests;
    private final Meter meterDownloadLogFileHttpRequests;
    private final Meter meterDownloadLogDaemonFileHttpRequests;
    private final Meter meterListLogsHttpRequests;
    private final Meter numSearchLogRequests;
    private final Meter numDeepSearchArchived;
    private final Meter numDeepSearchNonArchived;
    private final Meter numReadLogExceptions;
    private final Meter numReadDaemonLogExceptions;
    private final Meter numListLogExceptions;
    private final Meter numListDumpExceptions;
    private final Meter numDownloadDumpExceptions;
    private final Meter numDownloadLogExceptions;
    private final Meter numDownloadDaemonLogExceptions;
    private final Meter numSearchExceptions;
    private final Timer searchLogRequestDuration;
    private final Timer deepSearchRequestDuration;

    private final LogviewerLogPageHandler logviewer;
    private final LogviewerProfileHandler profileHandler;
    private final LogviewerLogDownloadHandler logDownloadHandler;
    private final LogviewerLogSearchHandler logSearchHandler;
    private final IHttpCredentialsPlugin httpCredsHandler;

    /**
     * Constructor.
     *
     * @param logviewerParam {@link LogviewerLogPageHandler}
     * @param profileHandler {@link LogviewerProfileHandler}
     * @param logDownloadHandler {@link LogviewerLogDownloadHandler}
     * @param logSearchHandler {@link LogviewerLogSearchHandler}
     * @param httpCredsHandler {@link IHttpCredentialsPlugin}
     * @param metricsRegistry The metrics registry
     */
    public LogviewerResource(LogviewerLogPageHandler logviewerParam, LogviewerProfileHandler profileHandler,
                             LogviewerLogDownloadHandler logDownloadHandler, LogviewerLogSearchHandler logSearchHandler,
                             IHttpCredentialsPlugin httpCredsHandler, StormMetricsRegistry metricsRegistry) {
        this.meterLogPageHttpRequests = metricsRegistry.registerMeter("logviewer:num-log-page-http-requests");
        this.meterDaemonLogPageHttpRequests = metricsRegistry.registerMeter(
            "logviewer:num-daemonlog-page-http-requests");
        this.meterDownloadLogFileHttpRequests = metricsRegistry.registerMeter(
            "logviewer:num-download-log-file-http-requests");
        this.meterDownloadLogDaemonFileHttpRequests = metricsRegistry.registerMeter(
            "logviewer:num-download-log-daemon-file-http-requests");
        this.meterListLogsHttpRequests = metricsRegistry.registerMeter("logviewer:num-list-logs-http-requests");
        this.numSearchLogRequests = metricsRegistry.registerMeter("logviewer:num-search-logs-requests");
        this.numDeepSearchArchived = metricsRegistry.registerMeter("logviewer:num-deep-search-requests-with-archived");
        this.numDeepSearchNonArchived = metricsRegistry.registerMeter("logviewer:num-deep-search-requests-without-archived");
        this.numReadLogExceptions = metricsRegistry.registerMeter(ExceptionMeterNames.NUM_READ_LOG_EXCEPTIONS);
        this.numReadDaemonLogExceptions = metricsRegistry.registerMeter(ExceptionMeterNames.NUM_READ_DAEMON_LOG_EXCEPTIONS);
        this.numListLogExceptions = metricsRegistry.registerMeter(ExceptionMeterNames.NUM_LIST_LOG_EXCEPTIONS);
        this.numListDumpExceptions = metricsRegistry.registerMeter(ExceptionMeterNames.NUM_LIST_DUMP_EXCEPTIONS);
        this.numDownloadDumpExceptions = metricsRegistry.registerMeter(ExceptionMeterNames.NUM_DOWNLOAD_DUMP_EXCEPTIONS);
        this.numDownloadLogExceptions = metricsRegistry.registerMeter(ExceptionMeterNames.NUM_DOWNLOAD_LOG_EXCEPTIONS);
        this.numDownloadDaemonLogExceptions = metricsRegistry.registerMeter(ExceptionMeterNames.NUM_DOWNLOAD_DAEMON_LOG_EXCEPTIONS);
        this.numSearchExceptions = metricsRegistry.registerMeter(ExceptionMeterNames.NUM_SEARCH_EXCEPTIONS);
        this.searchLogRequestDuration = metricsRegistry.registerTimer("logviewer:search-requests-duration-ms");
        this.deepSearchRequestDuration = metricsRegistry.registerTimer("logviewer:deep-search-request-duration-ms");
        this.logviewer = logviewerParam;
        this.profileHandler = profileHandler;
        this.logDownloadHandler = logDownloadHandler;
        this.logSearchHandler = logSearchHandler;
        this.httpCredsHandler = httpCredsHandler;
    }

    /**
     * Handles '/log' request.
     */
    @GET
    @Path("/log")
    public Response log(@Context HttpServletRequest request) throws IOException {
        meterLogPageHttpRequests.mark();

        try {
            String user = httpCredsHandler.getUserName(request);
            user = sanitizeParameter(user);
            Integer start = request.getParameter("start") != null ? parseIntegerFromMap(request.getParameterMap(), "start") : null;
            Integer length = request.getParameter("length") != null ? parseIntegerFromMap(request.getParameterMap(), "length") : null;
            String decodedFileName = Utils.urlDecodeUtf8(request.getParameter("file"));
            decodedFileName = sanitizeParameter(decodedFileName);
            String grep = request.getParameter("grep");
            grep = sanitizeParameter(grep);
            return logviewer.logPage(decodedFileName, start, length, grep, user);
        } catch (InvalidRequestException e) {
            LOG.error(e.getMessage(), e);
            return Response.status(400).entity(e.getMessage()).build();
        } catch (IOException e) {
            numReadLogExceptions.mark();
            throw e;
        }
    }

    private String sanitizeParameter(String param) {
        if (StringUtils.isNotBlank(param)) {
            return Jsoup.clean(param, Safelist.basic());
        }
        return param;
    }

    /**
     * Handles '/daemonlog' request.
     */
    @GET
    @Path("/daemonlog")
    public Response daemonLog(@Context HttpServletRequest request) throws IOException {
        meterDaemonLogPageHttpRequests.mark();

        try {
            String user = httpCredsHandler.getUserName(request);
            user = sanitizeParameter(user);
            Integer start = request.getParameter("start") != null ? parseIntegerFromMap(request.getParameterMap(), "start") : null;
            Integer length = request.getParameter("length") != null ? parseIntegerFromMap(request.getParameterMap(), "length") : null;
            String decodedFileName = Utils.urlDecodeUtf8(request.getParameter("file"));
            decodedFileName = sanitizeParameter(decodedFileName);
            String grep = request.getParameter("grep");
            grep = sanitizeParameter(grep);
            return logviewer.daemonLogPage(decodedFileName, start, length, grep, user);
        } catch (InvalidRequestException e) {
            LOG.error(e.getMessage(), e);
            return Response.status(400).entity(e.getMessage()).build();
        } catch (IOException e) {
            numReadDaemonLogExceptions.mark();
            throw e;
        }
    }

    /**
     * Handles '/searchLogs' request.
     */
    @GET
    @Path("/searchLogs")
    public Response searchLogs(@Context HttpServletRequest request) throws IOException {
        String topologyId = request.getParameter("topoId");
        Utils.validateTopologyName(topologyId);
        String user = httpCredsHandler.getUserName(request);
        user = sanitizeParameter(user);
        String portStr = request.getParameter("port");
        portStr = sanitizeParameter(portStr);
        String callback = request.getParameter("callbackParameterName");
        callback = sanitizeParameter(callback);
        String origin = request.getHeader("Origin");
        origin = sanitizeParameter(origin);
        return logviewer.listLogFiles(user, portStr != null ? Integer.parseInt(portStr) : null, topologyId, callback, origin);
    }

    /**
     * Handles '/listLogs' request.
     */
    @GET
    @Path("/listLogs")
    public Response listLogs(@Context HttpServletRequest request) throws IOException {
        meterListLogsHttpRequests.mark();

        String user = httpCredsHandler.getUserName(request);
        user = sanitizeParameter(user);
        String topologyId = request.getParameter("topoId");
        Utils.validateTopologyName(topologyId);
        String portStr = request.getParameter("port");
        portStr = sanitizeParameter(portStr);
        String callback = request.getParameter(StormApiResource.callbackParameterName);
        callback = sanitizeParameter(callback);
        String origin = request.getHeader("Origin");
        origin = sanitizeParameter(origin);

        try {
            return logviewer.listLogFiles(user, portStr != null ? Integer.parseInt(portStr) : null, topologyId, callback, origin);
        } catch (IOException e) {
            numListLogExceptions.mark();
            throw e;
        }
    }

    /**
     * Handles '/dumps' (listing dump files) request.
     */
    @GET
    @Path("/dumps/{topo-id}/{host-port}")
    public Response listDumpFiles(@PathParam("topo-id") String topologyId, @PathParam("host-port") String hostPort,
                                  @Context HttpServletRequest request) throws IOException {
        String user = httpCredsHandler.getUserName(request);
        user = sanitizeParameter(user);
        try {
            Utils.validateTopologyName(topologyId);
            hostPort = sanitizeParameter(hostPort);
            return profileHandler.listDumpFiles(topologyId, hostPort, user);
        } catch (IOException e) {
            numListDumpExceptions.mark();
            throw e;
        }
    }

    /**
     * Handles '/dumps' (downloading specific dump file) request.
     */
    @GET
    @Path("/dumps/{topo-id}/{host-port}/{filename}")
    public Response downloadDumpFile(@PathParam("topo-id") String topologyId, @PathParam("host-port") String hostPort,
                                     @PathParam("filename") String fileName, @Context HttpServletRequest request) throws IOException {

        String user = httpCredsHandler.getUserName(request);
        user = sanitizeParameter(user);
        try {
            Utils.validateTopologyName(topologyId);
            hostPort = sanitizeParameter(hostPort);
            fileName = sanitizeParameter(fileName);
            return profileHandler.downloadDumpFile(topologyId, hostPort, fileName, user);
        } catch (IOException e) {
            numDownloadDumpExceptions.mark();
            throw e;
        }
    }

    /**
     * Handles '/download' (downloading specific log file) request.
     */
    @GET
    @Path("/download")
    public Response downloadLogFile(@Context HttpServletRequest request) throws IOException {
        meterDownloadLogFileHttpRequests.mark();
        String user = httpCredsHandler.getUserName(request);
        user = sanitizeParameter(user);
        String file = request.getParameter("file");
        file = sanitizeParameter(file);
        String decodedFileName = Utils.urlDecodeUtf8(file);
        try {
            String host = Utils.hostname();
            return logDownloadHandler.downloadLogFile(host, decodedFileName, user);
        } catch (IOException e) {
            numDownloadLogExceptions.mark();
            throw e;
        }
    }

    /**
     * Handles '/daemondownload' (downloading specific daemon log file) request.
     */
    @GET
    @Path("/daemondownload")
    public Response downloadDaemonLogFile(@Context HttpServletRequest request) throws IOException {
        meterDownloadLogDaemonFileHttpRequests.mark();
        String user = httpCredsHandler.getUserName(request);
        user = sanitizeParameter(user);
        String file = request.getParameter("file");
        file = sanitizeParameter(file);
        String decodedFileName = Utils.urlDecodeUtf8(file);
        try {
            String host = Utils.hostname();
            return logDownloadHandler.downloadDaemonLogFile(host, decodedFileName, user);
        } catch (IOException e) {
            numDownloadDaemonLogExceptions.mark();
            throw e;
        }
    }

    /**
     * Handles '/search' (searching from specific worker or daemon log file) request.
     */
    @GET
    @Path("/search")
    public Response search(@Context HttpServletRequest request) throws IOException {
        numSearchLogRequests.mark();

        String user = httpCredsHandler.getUserName(request);
        user = sanitizeParameter(user);
        boolean isDaemon = StringUtils.equals(request.getParameter("is-daemon"), "yes");
        String file = request.getParameter("file");
        file = sanitizeParameter(file);
        String decodedFileName = Utils.urlDecodeUtf8(file);
        String searchString = request.getParameter("search-string");
        searchString = sanitizeParameter(searchString);
        String numMatchesStr = request.getParameter("num-matches");
        numMatchesStr = sanitizeParameter(numMatchesStr);
        String startByteOffset = request.getParameter("start-byte-offset");
        startByteOffset = sanitizeParameter(startByteOffset);
        String callback = request.getParameter(StormApiResource.callbackParameterName);
        callback = sanitizeParameter(callback);
        String origin = request.getHeader("Origin");
        origin = sanitizeParameter(origin);

        try (Timer.Context t = searchLogRequestDuration.time()) {
            return logSearchHandler.searchLogFile(decodedFileName, user, isDaemon,
                searchString, numMatchesStr, startByteOffset, callback, origin);
        } catch (InvalidRequestException e) {
            LOG.error(e.getMessage(), e);
            int statusCode = 400;
            return new JsonResponseBuilder().setData(UIHelpers.exceptionToJson(e, statusCode)).setCallback(callback)
                .setStatus(statusCode).build();
        } catch (IOException e) {
            numSearchExceptions.mark();
            throw e;
        }
    }

    /**
     * Handles '/deepSearch' request.
     */
    @GET
    @Path("/deepSearch/{topoId}")
    public Response deepSearch(@PathParam("topoId") String topologyId,
                               @Context HttpServletRequest request) throws IOException {
        Utils.validateTopologyName(topologyId);
        String user = httpCredsHandler.getUserName(request);
        user = sanitizeParameter(user);
        String searchString = request.getParameter("search-string");
        searchString = sanitizeParameter(searchString);
        String numMatchesStr = request.getParameter("num-matches");
        numMatchesStr = sanitizeParameter(numMatchesStr);
        String portStr = request.getParameter("port");
        portStr = sanitizeParameter(portStr);
        String startFileOffset = request.getParameter("start-file-offset");
        startFileOffset = sanitizeParameter(startFileOffset);
        String startByteOffset = request.getParameter("start-byte-offset");
        startByteOffset = sanitizeParameter(startByteOffset);
        String searchArchived = request.getParameter("search-archived");
        searchArchived = sanitizeParameter(searchArchived);
        String callback = request.getParameter(StormApiResource.callbackParameterName);
        callback = sanitizeParameter(callback);
        String origin = request.getHeader("Origin");
        origin = sanitizeParameter(origin);

        Boolean alsoSearchArchived = BooleanUtils.toBooleanObject(searchArchived);
        if (BooleanUtils.isTrue(alsoSearchArchived)) {
            numDeepSearchArchived.mark();
        } else {
            numDeepSearchNonArchived.mark();
        }
        try (Timer.Context t = deepSearchRequestDuration.time()) {
            return logSearchHandler.deepSearchLogsForTopology(topologyId, user, searchString, numMatchesStr, portStr, startFileOffset,
                startByteOffset, alsoSearchArchived, callback, origin);
        }
    }

    private int parseIntegerFromMap(Map<String, String[]> map, String parameterKey) throws InvalidRequestException {
        try {
            return Integer.parseInt(map.get(parameterKey)[0]);
        } catch (NumberFormatException ex) {
            throw new InvalidRequestException("Could not make an integer out of the query parameter '"
                + parameterKey + "'", ex);
        }
    }

}
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.storm.daemon.logviewer.handler;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.net.HttpHeaders;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.ws.rs.core.Response;
import org.apache.storm.daemon.logviewer.utils.ResourceAuthorizer;
import org.apache.storm.daemon.logviewer.utils.WorkerLogs;
import org.apache.storm.metric.StormMetricsRegistry;
import org.apache.storm.testing.TmpPath;
import org.apache.storm.utils.Utils;
import org.junit.jupiter.api.Test;

public class LogviewerLogDownloadHandlerTest {

    @Test
    public void testDownloadLogFile() throws IOException {
        try (TmpPath rootPath = new TmpPath()) {

            LogviewerLogDownloadHandler handler = createHandlerTraversalTests(rootPath.getFile().toPath());

            Response topoAResponse = handler.downloadLogFile("host", "topoA/1111/worker.log", "user");
            Response topoBResponse = handler.downloadLogFile("host", "topoB/1111/worker.log", "user");

            Utils.forceDelete(rootPath.toString());

            assertThat(topoAResponse.getStatus(), is(Response.Status.OK.getStatusCode()));
            assertThat(topoAResponse.getEntity(), not(nullValue()));
            String topoAContentDisposition = topoAResponse.getHeaderString(HttpHeaders.CONTENT_DISPOSITION);
            assertThat(topoAContentDisposition, containsString("host-topoA-1111-worker.log"));
            assertThat(topoBResponse.getStatus(), is(Response.Status.OK.getStatusCode()));
            assertThat(topoBResponse.getEntity(), not(nullValue()));
            String topoBContentDisposition = topoBResponse.getHeaderString(HttpHeaders.CONTENT_DISPOSITION);
            assertThat(topoBContentDisposition, containsString("host-topoB-1111-worker.log"));
        }
    }

    @Test
    public void testDownloadLogFileTraversal() throws IOException {
        try (TmpPath rootPath = new TmpPath()) {

            LogviewerLogDownloadHandler handler = createHandlerTraversalTests(rootPath.getFile().toPath());

            Response topoAResponse = handler.downloadLogFile("host","../nimbus.log", "user");

            Utils.forceDelete(rootPath.toString());

            assertThat(topoAResponse.getStatus(), is(Response.Status.NOT_FOUND.getStatusCode()));
        }
    }

    @Test
    public void testDownloadDaemonLogFile() throws IOException {
        try (TmpPath rootPath = new TmpPath()) {

            LogviewerLogDownloadHandler handler = createHandlerTraversalTests(rootPath.getFile().toPath());

            Response response = handler.downloadDaemonLogFile("host","nimbus.log", "user");

            Utils.forceDelete(rootPath.toString());

            assertThat(response.getStatus(), is(Response.Status.OK.getStatusCode()));
            assertThat(response.getEntity(), not(nullValue()));
            String contentDisposition = response.getHeaderString(HttpHeaders.CONTENT_DISPOSITION);
            assertThat(contentDisposition, containsString("host-nimbus.log"));
        }
    }

    @Test
    public void testDownloadDaemonLogFilePathIntoWorkerLogs() throws IOException {
        try (TmpPath rootPath = new TmpPath()) {

            LogviewerLogDownloadHandler handler = createHandlerTraversalTests(rootPath.getFile().toPath());

            Response response = handler.downloadDaemonLogFile("host","workers-artifacts/topoA/1111/worker.log", "user");

            Utils.forceDelete(rootPath.toString());

            assertThat(response.getStatus(), is(Response.Status.NOT_FOUND.getStatusCode()));
        }
    }

    @Test
    public void testDownloadDaemonLogFilePathOutsideLogRoot() throws IOException {
        try (TmpPath rootPath = new TmpPath()) {

            LogviewerLogDownloadHandler handler = createHandlerTraversalTests(rootPath.getFile().toPath());

            Response response = handler.downloadDaemonLogFile("host","../evil.sh", "user");

            Utils.forceDelete(rootPath.toString());

            assertThat(response.getStatus(), is(Response.Status.NOT_FOUND.getStatusCode()));
        }
    }

    private LogviewerLogDownloadHandler createHandlerTraversalTests(Path rootPath) throws IOException {
        Path daemonLogRoot = rootPath.resolve("logs");
        Path fileOutsideDaemonRoot = rootPath.resolve("evil.sh");
        Path workerLogRoot = daemonLogRoot.resolve("workers-artifacts");
        Path daemonFile = daemonLogRoot.resolve("nimbus.log");
        Path topoA = workerLogRoot.resolve("topoA");
        Path file1 = topoA.resolve("1111").resolve("worker.log");
        Path file2 = topoA.resolve("2222").resolve("worker.log");
        Path file3 = workerLogRoot.resolve("topoB").resolve("1111").resolve("worker.log");

        Files.createDirectories(file1.getParent());
        Files.createDirectories(file2.getParent());
        Files.createDirectories(file3.getParent());
        Files.createFile(file1);
        Files.createFile(file2);
        Files.createFile(file3);
        Files.createFile(fileOutsideDaemonRoot);
        Files.createFile(daemonFile);

        Map<String, Object> stormConf = Utils.readStormConfig();
        StormMetricsRegistry metricsRegistry = new StormMetricsRegistry();
        return new LogviewerLogDownloadHandler(workerLogRoot.toString(), daemonLogRoot.toString(),
            new WorkerLogs(stormConf, workerLogRoot, metricsRegistry), new ResourceAuthorizer(stormConf), metricsRegistry);
    }

}
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

package org.apache.storm.daemon.logviewer.handler;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.ws.rs.core.Response;

import org.apache.storm.daemon.logviewer.utils.LogviewerResponseBuilder;
import org.apache.storm.daemon.logviewer.utils.ResourceAuthorizer;
import org.apache.storm.daemon.logviewer.utils.WorkerLogs;
import org.apache.storm.metric.StormMetricsRegistry;
import org.apache.storm.testing.TmpPath;
import org.apache.storm.utils.Utils;
import org.assertj.core.util.Lists;
import org.junit.jupiter.api.Test;

public class LogviewerLogPageHandlerTest {

    /**
     * list-log-files filter selects the correct log files to return.
     */
    @Test
    public void testListLogFiles() throws IOException {
        String rootPath = Files.createTempDirectory("workers-artifacts").toFile().getCanonicalPath();
        File file1 = new File(String.join(File.separator, rootPath, "topoA", "1111"), "worker.log");
        File file2 = new File(String.join(File.separator, rootPath, "topoA", "2222"), "worker.log");
        File file3 = new File(String.join(File.separator, rootPath, "topoB", "1111"), "worker.log");

        file1.getParentFile().mkdirs();
        file2.getParentFile().mkdirs();
        file3.getParentFile().mkdirs();
        file1.createNewFile();
        file2.createNewFile();
        file3.createNewFile();

        String origin = "www.origin.server.net";
        Map<String, Object> stormConf = Utils.readStormConfig();
        StormMetricsRegistry metricsRegistry = new StormMetricsRegistry();
        LogviewerLogPageHandler handler = new LogviewerLogPageHandler(rootPath, rootPath,
                new WorkerLogs(stormConf, Paths.get(rootPath), metricsRegistry), new ResourceAuthorizer(stormConf), metricsRegistry);

        final Response expectedAll = LogviewerResponseBuilder.buildSuccessJsonResponse(
                Lists.newArrayList("topoA/port1/worker.log", "topoA/port2/worker.log", "topoB/port1/worker.log"),
                null,
                origin
        );

        final Response expectedFilterPort = LogviewerResponseBuilder.buildSuccessJsonResponse(
                Lists.newArrayList("topoA/port1/worker.log", "topoB/port1/worker.log"),
                null,
                origin
        );

        final Response expectedFilterTopoId = LogviewerResponseBuilder.buildSuccessJsonResponse(
                Lists.newArrayList("topoB/port1/worker.log"),
                null,
                origin
        );

        final Response returnedAll = handler.listLogFiles("user", null, null, null, origin);
        final Response returnedFilterPort = handler.listLogFiles("user", 1111, null, null, origin);
        final Response returnedFilterTopoId = handler.listLogFiles("user", null, "topoB", null, origin);

        Utils.forceDelete(rootPath);

        assertEqualsJsonResponse(expectedAll, returnedAll, List.class);
        assertEqualsJsonResponse(expectedFilterPort, returnedFilterPort, List.class);
        assertEqualsJsonResponse(expectedFilterTopoId, returnedFilterTopoId, List.class);
    }

    private <T> void assertEqualsJsonResponse(Response expected, Response actual, Class<T> entityClass) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        T entityFromExpected = objectMapper.readValue((String) expected.getEntity(), entityClass);
        T actualFromExpected = objectMapper.readValue((String) expected.getEntity(), entityClass);
        assertEquals(entityFromExpected, actualFromExpected);

        assertEquals(expected.getStatus(), actual.getStatus());
        assertTrue(expected.getHeaders().equalsIgnoreValueOrder(actual.getHeaders()));
    }

    @Test
    public void testListLogFilesOutsideLogRoot() throws IOException {
        try (TmpPath rootPath = new TmpPath()) {
            String origin = "www.origin.server.net";
            LogviewerLogPageHandler handler = createHandlerForTraversalTests(rootPath.getFile().toPath());

            //The response should be empty, since you should not be able to list files outside the worker log root.
            final Response expected = LogviewerResponseBuilder.buildSuccessJsonResponse(
                Lists.newArrayList(),
                null,
                origin
            );

            final Response returned = handler.listLogFiles("user", null, "../", null, origin);

            assertEqualsJsonResponse(expected, returned, List.class);
        }
    }

    @Test
    public void testLogPageOutsideLogRoot() throws Exception {
        try (TmpPath rootPath = new TmpPath()) {
            LogviewerLogPageHandler handler = createHandlerForTraversalTests(rootPath.getFile().toPath());

            final Response returned = handler.logPage("../nimbus.log", 0, 100, null, "user");

            Utils.forceDelete(rootPath.toString());

            //Should not show files outside worker log root.
            assertThat(returned.getStatus(), is(Response.Status.NOT_FOUND.getStatusCode()));
        }
    }

    @Test
    public void testDaemonLogPageOutsideLogRoot() throws Exception {
        try (TmpPath rootPath = new TmpPath()) {
            LogviewerLogPageHandler handler = createHandlerForTraversalTests(rootPath.getFile().toPath());

            final Response returned = handler.daemonLogPage("../evil.sh", 0, 100, null, "user");

            Utils.forceDelete(rootPath.toString());

            //Should not show files outside daemon log root.
            assertThat(returned.getStatus(), is(Response.Status.NOT_FOUND.getStatusCode()));
        }
    }

    @Test
    public void testDaemonLogPagePathIntoWorkerLogs() throws Exception {
        try (TmpPath rootPath = new TmpPath()) {
            LogviewerLogPageHandler handler = createHandlerForTraversalTests(rootPath.getFile().toPath());

            final Response returned = handler.daemonLogPage("workers-artifacts/topoA/worker.log", 0, 100, null, "user");

            Utils.forceDelete(rootPath.toString());

            //Should not show files outside log root.
            assertThat(returned.getStatus(), is(Response.Status.NOT_FOUND.getStatusCode()));
        }
    }

    private LogviewerLogPageHandler createHandlerForTraversalTests(Path rootPath) throws IOException {
        Path daemonLogRoot = rootPath.resolve("logs");
        Path fileOutsideDaemonRoot = rootPath.resolve("evil.sh");
        Path daemonFile = daemonLogRoot.resolve("nimbus.log");
        Path workerLogRoot = daemonLogRoot.resolve("workers-artifacts");
        Path topoA = workerLogRoot.resolve("topoA");
        Path file1 = topoA.resolve("1111").resolve("worker.log");
        Path file2 = topoA.resolve("2222").resolve("worker.log");
        Path file3 = workerLogRoot.resolve("topoB").resolve("1111").resolve("worker.log");

        Files.createDirectories(file1.getParent());
        Files.createDirectories(file2.getParent());
        Files.createDirectories(file3.getParent());
        Files.createFile(file1);
        Files.createFile(file2);
        Files.createFile(file3);
        Files.createFile(fileOutsideDaemonRoot);
        Files.createFile(daemonFile);

        Map<String, Object> stormConf = Utils.readStormConfig();
        StormMetricsRegistry metricsRegistry = new StormMetricsRegistry();
        return new LogviewerLogPageHandler(workerLogRoot.toString(), daemonLogRoot.toString(),
            new WorkerLogs(stormConf, workerLogRoot, metricsRegistry), new ResourceAuthorizer(stormConf), metricsRegistry);
    }
}
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

package org.apache.storm.daemon.logviewer.handler;

import static java.util.stream.Collectors.joining;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.apache.storm.DaemonConfig;
import org.apache.storm.daemon.logviewer.LogviewerConstant;
import org.apache.storm.daemon.logviewer.utils.ResourceAuthorizer;
import org.apache.storm.daemon.ui.InvalidRequestException;
import org.apache.storm.metric.StormMetricsRegistry;
import org.apache.storm.utils.Utils;
import org.jooq.lambda.Seq;
import org.jooq.lambda.Unchecked;
import org.jooq.lambda.tuple.Tuple3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

@RunWith(Enclosed.class)
public class LogviewerLogSearchHandlerTest {

    public static class SearchViaRestApi {

        private final String pattern = "needle";
        private final String expectedHost = "dev.null.invalid";
        private final Integer expectedPort = 8888;
        private final String logviewerUrlPrefix = "http://" + expectedHost + ":" + expectedPort;

        /*
         * When we click a link to the logviewer, we expect the match line to be somewhere near the middle of the page. So we subtract half
         * of the default page length from the offset at which we found the match.
         */
        private final Function<Integer, Integer> expOffsetFn = arg -> (LogviewerConstant.DEFAULT_BYTES_PER_PAGE / 2 - arg);

        @Test
        public void testSearchViaRestApiThrowsIfBogusFileIsGiven() throws InvalidRequestException {
            LogviewerLogSearchHandler handler = getSearchHandler();
            assertThrows(RuntimeException.class, () -> handler.substringSearch(null, "a string"));
        }

        @Test
        public void testLogviewerLinkCentersTheMatchInThePage() throws UnknownHostException {
            String expectedFname = "foobar.log";

            LogviewerLogSearchHandler handler = getSearchHandlerWithPort(expectedPort);
            Utils prevUtils = null;
            try {
                Utils mockedUtil = mock(Utils.class);
                prevUtils = Utils.setInstance(mockedUtil);

                when(mockedUtil.hostname()).thenReturn(expectedHost);

                String actualUrl = handler.urlToMatchCenteredInLogPage(new byte[42], new File(expectedFname).toPath(), 27526, 8888);

                assertEquals("http://" + expectedHost + ":" + expectedPort + "/api/v1/log?file=" + expectedFname
                    + "&start=1947&length=" + LogviewerConstant.DEFAULT_BYTES_PER_PAGE, actualUrl);
            } finally {
                Utils.setInstance(prevUtils);
            }
        }

        @Test
        public void testLogviewerLinkCentersTheMatchInThePageDaemon() throws UnknownHostException {
            String expectedFname = "foobar.log";

            LogviewerLogSearchHandler handler = getSearchHandlerWithPort(expectedPort);
            Utils prevUtils = null;
            try {
                Utils mockedUtil = mock(Utils.class);
                prevUtils = Utils.setInstance(mockedUtil);

                when(mockedUtil.hostname()).thenReturn(expectedHost);

                String actualUrl = handler.urlToMatchCenteredInLogPageDaemonFile(new byte[42], new File(expectedFname).toPath(), 27526, 8888);

                assertEquals("http://" + expectedHost + ":" + expectedPort + "/api/v1/daemonlog?file=" + expectedFname
                    + "&start=1947&length=" + LogviewerConstant.DEFAULT_BYTES_PER_PAGE, actualUrl);
            } finally {
                Utils.setInstance(prevUtils);
            }
        }

        @SuppressWarnings("checkstyle:LineLength")
        @Test
        public void testReturnsCorrectBeforeAndAfterContext() throws Exception {
            Utils prevUtils = null;
            try {
                Utils mockedUtil = mock(Utils.class);
                prevUtils = Utils.setInstance(mockedUtil);

                when(mockedUtil.hostname()).thenReturn(expectedHost);

                final File file = new File(String.join(File.separator, "src", "test", "resources"),
                    "logviewer-search-context-tests.log.test");

                Map<String, Object> expected = new HashMap<>();
                expected.put("isDaemon", "no");
                expected.put("searchString", pattern);
                expected.put("startByteOffset", 0);

                List<Map<String, Object>> matches = new ArrayList<>();

                matches.add(buildMatchData(0, "",
                    " needle000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000needle ",
                    pattern,
                    "/api/v1/log?file=test" + encodedFileSeparator() + "resources" + encodedFileSeparator() + file.getName()
                    + "&start=0&length=51200"
                ));

                matches.add(buildMatchData(7, "needle ",
                    "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000needle needle\n",
                    pattern,
                    "/api/v1/log?file=test" + encodedFileSeparator() + "resources" + encodedFileSeparator() + file.getName()
                    + "&start=0&length=51200"
                ));

                matches.add(buildMatchData(127,
                    "needle needle000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                    " needle\n",
                    pattern,
                    "/api/v1/log?file=test" + encodedFileSeparator() + "resources" + encodedFileSeparator() + file.getName()
                    + "&start=0&length=51200"
                ));

                matches.add(buildMatchData(134,
                    " needle000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000needle ",
                    "\n",
                    pattern,
                    "/api/v1/log?file=test" + encodedFileSeparator() + "resources" + encodedFileSeparator() + file.getName()
                    + "&start=0&length=51200"
                ));

                expected.put("matches", matches);

                LogviewerLogSearchHandler handler = getSearchHandlerWithPort(expectedPort);
                Map<String, Object> searchResult = handler.substringSearch(file.toPath(), pattern);

                assertEquals(expected, searchResult);
            } finally {
                Utils.setInstance(prevUtils);
            }
        }

        @Test
        public void testAreallySmallLogFile() throws Exception {
            Utils prevUtils = null;
            try {
                Utils mockedUtil = mock(Utils.class);
                prevUtils = Utils.setInstance(mockedUtil);

                when(mockedUtil.hostname()).thenReturn(expectedHost);

                final File file = new File(String.join(File.separator, "src", "test", "resources"),
                    "small-worker.log.test");

                Map<String, Object> expected = new HashMap<>();
                expected.put("isDaemon", "no");
                expected.put("searchString", pattern);
                expected.put("startByteOffset", 0);

                List<Map<String, Object>> matches = new ArrayList<>();

                matches.add(buildMatchData(7, "000000 ",
                    " 000000\n",
                    pattern,
                    "/api/v1/log?file=test" + encodedFileSeparator() + "resources" + encodedFileSeparator() + file.getName()
                    + "&start=0&length=51200"
                ));

                expected.put("matches", matches);

                LogviewerLogSearchHandler handler = getSearchHandlerWithPort(expectedPort);
                Map<String, Object> searchResult = handler.substringSearch(file.toPath(), pattern);

                assertEquals(expected, searchResult);
            } finally {
                Utils.setInstance(prevUtils);
            }
        }

        @Test
        public void testAreallySmallLogDaemonFile() throws InvalidRequestException, UnknownHostException {
            Utils prevUtils = null;
            try {
                Utils mockedUtil = mock(Utils.class);
                prevUtils = Utils.setInstance(mockedUtil);

                when(mockedUtil.hostname()).thenReturn(expectedHost);

                final File file = new File(String.join(File.separator, "src", "test", "resources"),
                    "small-worker.log.test");

                Map<String, Object> expected = new HashMap<>();
                expected.put("isDaemon", "yes");
                expected.put("searchString", pattern);
                expected.put("startByteOffset", 0);

                List<Map<String, Object>> matches = new ArrayList<>();

                matches.add(buildMatchData(7, "000000 ",
                    " 000000\n",
                    pattern,
                    "/api/v1/daemonlog?file=" + file.getName() + "&start=0&length=51200"
                ));

                expected.put("matches", matches);

                LogviewerLogSearchHandler handler = getSearchHandlerWithPort(expectedPort);
                Map<String, Object> searchResult = handler.substringSearchDaemonLog(file.toPath(), pattern);

                assertEquals(expected, searchResult);
            } finally {
                Utils.setInstance(prevUtils);
            }
        }

        @Test
        public void testNoOffsetReturnedWhenFileEndsOnBufferOffset() throws Exception {
            Utils prevUtils = null;
            try {
                Utils mockedUtil = mock(Utils.class);
                prevUtils = Utils.setInstance(mockedUtil);

                when(mockedUtil.hostname()).thenReturn(expectedHost);

                final File file = new File(String.join(File.separator, "src", "test", "resources"),
                    "test-3072.log.test");

                Map<String, Object> expected = new HashMap<>();
                expected.put("isDaemon", "no");
                expected.put("searchString", pattern);
                expected.put("startByteOffset", 0);

                List<Map<String, Object>> matches = new ArrayList<>();

                matches.add(buildMatchData(3066,
                    Seq.range(0, 128).map(x -> ".").collect(joining()),
                    "",
                    pattern,
                    "/api/v1/log?file=test" + encodedFileSeparator() + "resources" + encodedFileSeparator() + file.getName()
                    + "&start=0&length=51200"
                ));

                expected.put("matches", matches);

                LogviewerLogSearchHandler handler = getSearchHandlerWithPort(expectedPort);
                Map<String, Object> searchResult = handler.substringSearch(file.toPath(), pattern);
                Map<String, Object> searchResult2 = handler.substringSearch(file.toPath(), pattern, 1);

                assertEquals(expected, searchResult);
                assertEquals(expected, searchResult2);
            } finally {
                Utils.setInstance(prevUtils);
            }
        }

        @SuppressWarnings("checkstyle:LineLength")
        @Test
        public void testNextByteOffsetsAreCorrectForEachMatch() throws Exception {
            Utils prevUtils = null;
            try {
                Utils mockedUtil = mock(Utils.class);
                prevUtils = Utils.setInstance(mockedUtil);

                when(mockedUtil.hostname()).thenReturn(expectedHost);

                File file = new File(String.join(File.separator, "src", "test", "resources"),
                    "test-worker.log.test");

                LogviewerLogSearchHandler handler = getSearchHandlerWithPort(expectedPort);

                List<Tuple3<Integer, Integer, Integer>> dataAndExpected = new ArrayList<>();
                // numMatchesSought, numMatchesFound, expectedNextByteOffset
                dataAndExpected.add(new Tuple3<>(1, 1, 11));
                dataAndExpected.add(new Tuple3<>(2, 2, 2042));
                dataAndExpected.add(new Tuple3<>(3, 3, 2052));
                dataAndExpected.add(new Tuple3<>(4, 4, 3078));
                dataAndExpected.add(new Tuple3<>(5, 5, 3196));
                dataAndExpected.add(new Tuple3<>(6, 6, 3202));
                dataAndExpected.add(new Tuple3<>(7, 7, 6252));
                dataAndExpected.add(new Tuple3<>(8, 8, 6321));
                dataAndExpected.add(new Tuple3<>(9, 9, 6397));
                dataAndExpected.add(new Tuple3<>(10, 10, 6476));
                dataAndExpected.add(new Tuple3<>(11, 11, 6554));
                dataAndExpected.add(new Tuple3<>(12, 12, null));
                dataAndExpected.add(new Tuple3<>(13, 12, null));

                dataAndExpected.forEach(Unchecked.consumer(data -> {
                    Map<String, Object> result = handler.substringSearch(file.toPath(), pattern, data.v1());
                    assertEquals(data.v3(), result.get("nextByteOffset"));
                    assertEquals(data.v2().intValue(), ((List) result.get("matches")).size());
                }));

                Map<String, Object> expected = new HashMap<>();
                expected.put("isDaemon", "no");
                expected.put("searchString", pattern);
                expected.put("startByteOffset", 0);
                expected.put("nextByteOffset", 6252);

                List<Map<String, Object>> matches = new ArrayList<>();

                matches.add(buildMatchData(5,
                    "Test ",
                    " is near the beginning of the file.\nThis file assumes a buffer size of 2048 bytes, a max search string size of 1024 bytes, and a",
                    pattern,
                    "/api/v1/log?file=test" + encodedFileSeparator() + "resources" + encodedFileSeparator() + file.getName()
                    + "&start=0&length=51200"
                ));

                matches.add(buildMatchData(2036,
                    "ng 146\npadding 147\npadding 148\npadding 149\npadding 150\npadding 151\npadding 152\npadding 153\nNear the end of a 1024 byte block, a ",
                    ".\nA needle that straddles a 1024 byte boundary should also be detected.\n\npadding 157\npadding 158\npadding 159\npadding 160\npadding",
                    pattern,
                    "/api/v1/log?file=test" + encodedFileSeparator() + "resources" + encodedFileSeparator() + file.getName()
                    + "&start=0&length=51200"
                ));

                matches.add(buildMatchData(2046,
                    "ding 147\npadding 148\npadding 149\npadding 150\npadding 151\npadding 152\npadding 153\nNear the end of a 1024 byte block, a needle.\nA ",
                    " that straddles a 1024 byte boundary should also be detected.\n\npadding 157\npadding 158\npadding 159\npadding 160\npadding 161\npaddi",
                    pattern,
                    "/api/v1/log?file=test" + encodedFileSeparator() + "resources" + encodedFileSeparator() + file.getName()
                    + "&start=0&length=51200"
                ));

                matches.add(buildMatchData(3072,
                    "adding 226\npadding 227\npadding 228\npadding 229\npadding 230\npadding 231\npadding 232\npadding 233\npadding 234\npadding 235\n\n\nHere a ",
                    " occurs just after a 1024 byte boundary.  It should have the correct context.\n\nText with two adjoining matches: needleneedle\n\npa",
                    pattern,
                    "/api/v1/log?file=test" + encodedFileSeparator() + "resources" + encodedFileSeparator() + file.getName()
                    + "&start=0&length=51200"
                ));

                matches.add(buildMatchData(3190,
                    "\n\n\nHere a needle occurs just after a 1024 byte boundary.  It should have the correct context.\n\nText with two adjoining matches: ",
                    "needle\n\npadding 243\npadding 244\npadding 245\npadding 246\npadding 247\npadding 248\npadding 249\npadding 250\npadding 251\npadding 252\n",
                    pattern,
                    "/api/v1/log?file=test" + encodedFileSeparator() + "resources" + encodedFileSeparator() + file.getName()
                    + "&start=0&length=51200"
                ));

                matches.add(buildMatchData(3196,
                    "e a needle occurs just after a 1024 byte boundary.  It should have the correct context.\n\nText with two adjoining matches: needle",
                    "\n\npadding 243\npadding 244\npadding 245\npadding 246\npadding 247\npadding 248\npadding 249\npadding 250\npadding 251\npadding 252\npaddin",
                    pattern,
                    "/api/v1/log?file=test" + encodedFileSeparator() + "resources" + encodedFileSeparator() + file.getName()
                    + "&start=0&length=51200"
                ));

                matches.add(buildMatchData(6246,
                    "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n\nHere are four non-ascii 1-byte UTF-8 characters: αβγδε\n\n",
                    "\n\nHere are four printable 2-byte UTF-8 characters: ¡¢£¤¥\n\nneedle\n\n\n\nHere are four printable 3-byte UTF-8 characters: ऄअ",
                    pattern,
                    "/api/v1/log?file=test" + encodedFileSeparator() + "resources" + encodedFileSeparator() + file.getName()
                    + "&start=0&length=51200"
                ));

                expected.put("matches", matches);

                Map<String, Object> searchResult = handler.substringSearch(file.toPath(), pattern, 7);

                assertEquals(expected, searchResult);
            } finally {
                Utils.setInstance(prevUtils);
            }
        }

        @SuppressWarnings("checkstyle:LineLength")
        @Test
        public void testCorrectMatchOffsetIsReturnedWhenSkippingBytes() throws Exception {
            Utils prevUtils = null;
            try {
                Utils mockedUtil = mock(Utils.class);
                prevUtils = Utils.setInstance(mockedUtil);

                when(mockedUtil.hostname()).thenReturn(expectedHost);

                final File file = new File(String.join(File.separator, "src", "test", "resources"),
                    "test-worker.log.test");

                int startByteOffset = 3197;

                Map<String, Object> expected = new HashMap<>();
                expected.put("isDaemon", "no");
                expected.put("searchString", pattern);
                expected.put("startByteOffset", startByteOffset);
                expected.put("nextByteOffset", 6252);

                List<Map<String, Object>> matches = new ArrayList<>();

                matches.add(buildMatchData(6246,
                    "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\n\nHere are four non-ascii 1-byte UTF-8 characters: αβγδε\n\n",
                    "\n\nHere are four printable 2-byte UTF-8 characters: ¡¢£¤¥\n\nneedle\n\n\n\nHere are four printable 3-byte UTF-8 characters: ऄअ",
                    pattern,
                    "/api/v1/log?file=test" + encodedFileSeparator() + "resources" + encodedFileSeparator() + file.getName()
                    + "&start=0&length=51200"
                ));

                expected.put("matches", matches);

                LogviewerLogSearchHandler handler = getSearchHandlerWithPort(expectedPort);
                Map<String, Object> searchResult = handler.substringSearch(file.toPath(), pattern, 1, startByteOffset);

                assertEquals(expected, searchResult);
            } finally {
                Utils.setInstance(prevUtils);
            }
        }

        @SuppressWarnings("checkstyle:LineLength")
        @Test
        public void testAnotherPatterns1() throws Exception {
            Utils prevUtils = null;
            try {
                Utils mockedUtil = mock(Utils.class);
                prevUtils = Utils.setInstance(mockedUtil);

                when(mockedUtil.hostname()).thenReturn(expectedHost);

                final File file = new File(String.join(File.separator, "src", "test", "resources"),
                    "test-worker.log.test");

                String pattern = Seq.range(0, 1024).map(x -> "X").collect(joining());

                Map<String, Object> expected = new HashMap<>();
                expected.put("isDaemon", "no");
                expected.put("searchString", pattern);
                expected.put("startByteOffset", 0);
                expected.put("nextByteOffset", 6183);

                List<Map<String, Object>> matches = new ArrayList<>();

                matches.add(buildMatchData(4075,
                    "\n\nThe following match of 1024 bytes completely fills half the byte buffer.  It is a search substring of the maximum size......\n\n",
                    "\nThe following max-size match straddles a 1024 byte buffer.\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
                    pattern,
                    "/api/v1/log?file=test" + encodedFileSeparator() + "resources" + encodedFileSeparator() + file.getName()
                    + "&start=0&length=51200"
                ));

                matches.add(buildMatchData(5159,
                    "XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX\nThe following max-size match straddles a 1024 byte buffer.\n",
                    "\n\nHere are four non-ascii 1-byte UTF-8 characters: αβγδε\n\nneedle\n\nHere are four printable 2-byte UTF-8 characters: ¡¢£¤",
                    pattern,
                    "/api/v1/log?file=test" + encodedFileSeparator() + "resources" + encodedFileSeparator() + file.getName()
                    + "&start=0&length=51200"
                ));

                expected.put("matches", matches);

                LogviewerLogSearchHandler handler = getSearchHandlerWithPort(expectedPort);
                Map<String, Object> searchResult = handler.substringSearch(file.toPath(), pattern, 2);

                assertEquals(expected, searchResult);
            } finally {
                Utils.setInstance(prevUtils);
            }
        }

        @SuppressWarnings("checkstyle:LineLength")
        @Test
        public void testAnotherPatterns2() throws Exception {
            Utils prevUtils = null;
            try {
                Utils mockedUtil = mock(Utils.class);
                prevUtils = Utils.setInstance(mockedUtil);

                when(mockedUtil.hostname()).thenReturn(expectedHost);

                final File file = new File(String.join(File.separator, "src", "test", "resources"),
                    "test-worker.log.test");
                String pattern = "𐄀𐄁𐄂";

                Map<String, Object> expected = new HashMap<>();
                expected.put("isDaemon", "no");
                expected.put("searchString", pattern);
                expected.put("startByteOffset", 0);
                expected.put("nextByteOffset", 7176);

                List<Map<String, Object>> matches = new ArrayList<>();

                matches.add(buildMatchData(7164,
                    "padding 372\npadding 373\npadding 374\npadding 375\n\nThe following tests multibyte UTF-8 Characters straddling the byte boundary:   ",
                    "\n\nneedle",
                    pattern,
                    "/api/v1/log?file=test" + encodedFileSeparator() + "resources" + encodedFileSeparator() + file.getName()
                    + "&start=0&length=51200"
                ));

                expected.put("matches", matches);

                LogviewerLogSearchHandler handler = getSearchHandlerWithPort(expectedPort);
                Map<String, Object> searchResult = handler.substringSearch(file.toPath(), pattern, 1);

                assertEquals(expected, searchResult);
            } finally {
                Utils.setInstance(prevUtils);
            }
        }

        @Test
        public void testReturnsZeroMatchesForUnseenPattern() throws UnknownHostException, InvalidRequestException {
            Utils prevUtils = null;
            try {
                Utils mockedUtil = mock(Utils.class);
                prevUtils = Utils.setInstance(mockedUtil);

                String pattern = "Not There";

                when(mockedUtil.hostname()).thenReturn(expectedHost);

                final File file = new File(String.join(File.separator, "src", "test", "resources"),
                    "test-worker.log.test");

                Map<String, Object> expected = new HashMap<>();
                expected.put("isDaemon", "no");
                expected.put("searchString", pattern);
                expected.put("startByteOffset", 0);

                expected.put("matches", Collections.emptyList());

                LogviewerLogSearchHandler handler = getSearchHandlerWithPort(expectedPort);
                Map<String, Object> searchResult = handler.substringSearch(file.toPath(), pattern);

                assertEquals(expected, searchResult);
            } finally {
                Utils.setInstance(prevUtils);
            }
        }

        private Map<String, Object> buildMatchData(int byteOffset, String beforeString, String afterString,
            String matchString, String logviewerUrlPath) {
            Map<String, Object> match = new HashMap<>();
            match.put("byteOffset", byteOffset);
            match.put("beforeString", beforeString);
            match.put("afterString", afterString);
            match.put("matchString", matchString);
            match.put("logviewerURL", logviewerUrlPrefix + logviewerUrlPath);
            return match;
        }

        private String encodedFileSeparator() {
            return Utils.urlEncodeUtf8(File.separator);
        }
    }

    public static class FindNMatchesTest {

        /**
         * find-n-matches looks through logs properly.
         */
        @Test
        public void testFindNMatches() {
            List<Path> files = new ArrayList<>();
            files.add(new File(String.join(File.separator, "src", "test", "resources"),
                "logviewer-search-context-tests.log.test").toPath());
            files.add(new File(String.join(File.separator, "src", "test", "resources"),
                "logviewer-search-context-tests.log.gz").toPath());

            final LogviewerLogSearchHandler handler = getSearchHandler();

            final List<Map<String, Object>> matches1 = handler.findNMatches(files, 20, 0, 0, "needle").getMatches();
            final List<Map<String, Object>> matches2 = handler.findNMatches(files, 20, 0, 126, "needle").getMatches();
            final List<Map<String, Object>> matches3 = handler.findNMatches(files, 20, 1, 0, "needle").getMatches();

            assertEquals(2, matches1.size());
            assertEquals(4, ((List) matches1.get(0).get("matches")).size());
            assertEquals(4, ((List) matches1.get(1).get("matches")).size());
            assertEquals(String.join(File.separator, "test", "resources", "logviewer-search-context-tests.log.test"), matches1.get(0).get("fileName"));
            assertEquals(String.join(File.separator, "test", "resources", "logviewer-search-context-tests.log.gz"), matches1.get(1).get("fileName"));

            assertEquals(2, ((List) matches2.get(0).get("matches")).size());
            assertEquals(4, ((List) matches2.get(1).get("matches")).size());

            assertEquals(1, matches3.size());
            assertEquals(4, ((List) matches3.get(0).get("matches")).size());
        }
    }

    public static class TestDeepSearchLogs {

        public static final int METRIC_SCANNED_FILES = 0;
        private List<Path> logFiles;
        private Path topoPath;

        /**
         * Setup test environment for each test.
         */
        @BeforeEach
        public void setUp() throws IOException {
            logFiles = new ArrayList<>();
            logFiles.add(Paths.get("src/test/resources/logviewer-search-context-tests.log.test"));
            logFiles.add(Paths.get("src/test/resources/logviewer-search-context-tests.log.gz"));

            topoPath = Files.createTempDirectory("topoA").toAbsolutePath().normalize();
            new File(topoPath.toFile(), "6400").createNewFile();
            new File(topoPath.toFile(), "6500").createNewFile();
            new File(topoPath.toFile(), "6600").createNewFile();
            new File(topoPath.toFile(), "6700").createNewFile();
        }

        /**
         * Clean up test environment.
         */
        @AfterEach
        public void tearDown() {
            if (topoPath != null) {
                try {
                    Utils.forceDelete(topoPath.toString());
                } catch (IOException e) {
                    // ignore...
                }
            }
        }

        @Test
        public void testAllPortsAndSearchArchivedIsTrue() throws IOException {
            LogviewerLogSearchHandler handler = getStubbedSearchHandler();

            handler.deepSearchLogsForTopology("", null, "search", "20", "*", "20", "199", true, null, null);

            ArgumentCaptor<List> files = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<Integer> numMatches = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> fileOffset = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<String> search = ArgumentCaptor.forClass(String.class);

            verify(handler, times(4)).findNMatches(files.capture(), numMatches.capture(), fileOffset.capture(),
                offset.capture(), search.capture());
            verify(handler, times(4)).logsForPort(isNull(), any());

            // File offset and byte offset should always be zero when searching multiple workers (multiple ports).
            assertEquals(logFiles, files.getAllValues().get(0));
            assertEquals(Integer.valueOf(20), numMatches.getAllValues().get(0));
            assertEquals(Integer.valueOf(0), fileOffset.getAllValues().get(0));
            assertEquals(Integer.valueOf(0), offset.getAllValues().get(0));
            assertEquals("search", search.getAllValues().get(0));
            assertEquals(logFiles, files.getAllValues().get(0));

            assertEquals(Integer.valueOf(20), numMatches.getAllValues().get(1));
            assertEquals(Integer.valueOf(0), fileOffset.getAllValues().get(1));
            assertEquals(Integer.valueOf(0), offset.getAllValues().get(1));
            assertEquals("search", search.getAllValues().get(1));
            assertEquals(logFiles, files.getAllValues().get(1));

            assertEquals(Integer.valueOf(20), numMatches.getAllValues().get(2));
            assertEquals(Integer.valueOf(0), fileOffset.getAllValues().get(2));
            assertEquals(Integer.valueOf(0), offset.getAllValues().get(2));
            assertEquals("search", search.getAllValues().get(2));
            assertEquals(logFiles, files.getAllValues().get(2));

            assertEquals(Integer.valueOf(20), numMatches.getAllValues().get(3));
            assertEquals(Integer.valueOf(0), fileOffset.getAllValues().get(3));
            assertEquals(Integer.valueOf(0), offset.getAllValues().get(3));
            assertEquals("search", search.getAllValues().get(3));
        }

        @Test
        public void testAllPortsAndSearchArchivedIsFalse() throws IOException {
            LogviewerLogSearchHandler handler = getStubbedSearchHandler();

            handler.deepSearchLogsForTopology("", null, "search", "20", null, "20", "199", false, null, null);

            ArgumentCaptor<List> files = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<Integer> numMatches = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> fileOffset = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<String> search = ArgumentCaptor.forClass(String.class);

            verify(handler, times(4)).findNMatches(files.capture(), numMatches.capture(), fileOffset.capture(),
                offset.capture(), search.capture());
            verify(handler, times(4)).logsForPort(isNull(), any());

            // File offset and byte offset should always be zero when searching multiple workers (multiple ports).
            assertEquals(Collections.singletonList(logFiles.get(0)), files.getAllValues().get(0));
            assertEquals(Integer.valueOf(20), numMatches.getAllValues().get(0));
            assertEquals(Integer.valueOf(0), fileOffset.getAllValues().get(0));
            assertEquals(Integer.valueOf(0), offset.getAllValues().get(0));
            assertEquals("search", search.getAllValues().get(0));

            assertEquals(Collections.singletonList(logFiles.get(0)), files.getAllValues().get(1));
            assertEquals(Integer.valueOf(20), numMatches.getAllValues().get(1));
            assertEquals(Integer.valueOf(0), fileOffset.getAllValues().get(1));
            assertEquals(Integer.valueOf(0), offset.getAllValues().get(1));
            assertEquals("search", search.getAllValues().get(1));

            assertEquals(Collections.singletonList(logFiles.get(0)), files.getAllValues().get(2));
            assertEquals(Integer.valueOf(20), numMatches.getAllValues().get(2));
            assertEquals(Integer.valueOf(0), fileOffset.getAllValues().get(2));
            assertEquals(Integer.valueOf(0), offset.getAllValues().get(2));
            assertEquals("search", search.getAllValues().get(2));

            assertEquals(Collections.singletonList(logFiles.get(0)), files.getAllValues().get(3));
            assertEquals(Integer.valueOf(20), numMatches.getAllValues().get(3));
            assertEquals(Integer.valueOf(0), fileOffset.getAllValues().get(3));
            assertEquals(Integer.valueOf(0), offset.getAllValues().get(3));
            assertEquals("search", search.getAllValues().get(3));
        }

        @Test
        public void testOnePortAndSearchArchivedIsTrueAndNotFileOffset() throws IOException {
            LogviewerLogSearchHandler handler = getStubbedSearchHandler();

            handler.deepSearchLogsForTopology("", null, "search", "20", "6700", "0", "0", true, null, null);

            ArgumentCaptor<List> files = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<Integer> numMatches = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> fileOffset = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<String> search = ArgumentCaptor.forClass(String.class);

            verify(handler, times(1)).findNMatches(files.capture(), numMatches.capture(), fileOffset.capture(),
                offset.capture(), search.capture());
            verify(handler).logsForPort(isNull(), any());

            assertEquals(logFiles, files.getAllValues().get(0));
            assertEquals(Integer.valueOf(20), numMatches.getAllValues().get(0));
            assertEquals(Integer.valueOf(0), fileOffset.getAllValues().get(0));
            assertEquals(Integer.valueOf(0), offset.getAllValues().get(0));
            assertEquals("search", search.getAllValues().get(0));
        }

        @Test
        public void testOnePortAndSearchArchivedIsTrueAndFileOffsetIs1() throws IOException {
            LogviewerLogSearchHandler handler = getStubbedSearchHandler();

            handler.deepSearchLogsForTopology("", null, "search", "20", "6700", "1", "0", true, null, null);

            ArgumentCaptor<List> files = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<Integer> numMatches = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> fileOffset = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<String> search = ArgumentCaptor.forClass(String.class);

            verify(handler, times(1)).findNMatches(files.capture(), numMatches.capture(), fileOffset.capture(),
                offset.capture(), search.capture());
            verify(handler).logsForPort(isNull(), any());

            assertEquals(logFiles, files.getAllValues().get(0));
            assertEquals(Integer.valueOf(20), numMatches.getAllValues().get(0));
            assertEquals(Integer.valueOf(1), fileOffset.getAllValues().get(0));
            assertEquals(Integer.valueOf(0), offset.getAllValues().get(0));
            assertEquals("search", search.getAllValues().get(0));
        }

        @Test
        public void testOnePortAndSearchArchivedIsFalseAndFileOffsetIs1() throws IOException {
            LogviewerLogSearchHandler handler = getStubbedSearchHandler();

            handler.deepSearchLogsForTopology("", null, "search", "20", "6700", "1", "0", false, null, null);

            ArgumentCaptor<List> files = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<Integer> numMatches = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> fileOffset = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<String> search = ArgumentCaptor.forClass(String.class);

            verify(handler, times(1)).findNMatches(files.capture(), numMatches.capture(), fileOffset.capture(),
                offset.capture(), search.capture());
            verify(handler).logsForPort(isNull(), any());

            // File offset should be zero, since search-archived is false.
            assertEquals(Collections.singletonList(logFiles.get(0)), files.getAllValues().get(0));
            assertEquals(Integer.valueOf(20), numMatches.getAllValues().get(0));
            assertEquals(Integer.valueOf(0), fileOffset.getAllValues().get(0));
            assertEquals(Integer.valueOf(0), offset.getAllValues().get(0));
            assertEquals("search", search.getAllValues().get(0));
        }

        @Test
        public void testOnePortAndSearchArchivedIsTrueAndFileOffsetIs1AndByteOffsetIs100() throws IOException {
            LogviewerLogSearchHandler handler = getStubbedSearchHandler();

            handler.deepSearchLogsForTopology("", null, "search", "20", "6700", "1", "100", true, null, null);

            verify(handler, times(1)).findNMatches(anyList(), anyInt(), anyInt(), anyInt(), anyString());
            verify(handler, times(1)).logsForPort(isNull(), any());
        }

        @Test
        public void testBadPortAndSearchArchivedIsFalseAndFileOffsetIs1() throws IOException {
            LogviewerLogSearchHandler handler = getStubbedSearchHandler();

            handler.deepSearchLogsForTopology("", null, "search", "20", "2700", "1", "0", false, null, null);

            ArgumentCaptor<List> files = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<Integer> numMatches = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> fileOffset = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
            ArgumentCaptor<String> search = ArgumentCaptor.forClass(String.class);

            // Called with a bad port (not in the config) No searching should be done.
            verify(handler, never()).findNMatches(files.capture(), numMatches.capture(), fileOffset.capture(),
                offset.capture(), search.capture());
            verify(handler, never()).logsForPort(anyString(), any());
        }

        private LogviewerLogSearchHandler getStubbedSearchHandler() {
            Map<String, Object> stormConf = Utils.readStormConfig();
            LogviewerLogSearchHandler handler = new LogviewerLogSearchHandler(stormConf, topoPath, Paths.get(""),
                new ResourceAuthorizer(stormConf), new StormMetricsRegistry());
            handler = spy(handler);

            doReturn(logFiles).when(handler).logsForPort(any(), any());
            doAnswer(invocationOnMock -> {
                Object[] arguments = invocationOnMock.getArguments();
                int fileOffset = (Integer) arguments[2];
                String search = (String) arguments[4];

                return new LogviewerLogSearchHandler.Matched(fileOffset, search, Collections.emptyList(), METRIC_SCANNED_FILES);
            }).when(handler).findNMatches(any(), anyInt(), anyInt(), anyInt(), any());

            return handler;
        }
    }

    private static LogviewerLogSearchHandler getSearchHandler() {
        Map<String, Object> stormConf = Utils.readStormConfig();
        return new LogviewerLogSearchHandler(stormConf, Paths.get(""), Paths.get(""),
            new ResourceAuthorizer(stormConf), new StormMetricsRegistry());
    }

    private static LogviewerLogSearchHandler getSearchHandlerWithPort(int port) {
        Map<String, Object> stormConf = Utils.readStormConfig();
        stormConf.put(DaemonConfig.LOGVIEWER_PORT, port);
        return new LogviewerLogSearchHandler(stormConf, Paths.get(""), Paths.get(""),
            new ResourceAuthorizer(stormConf), new StormMetricsRegistry());
    }

}
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.storm.daemon.logviewer.handler;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.net.HttpHeaders;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.ws.rs.core.Response;
import org.apache.storm.daemon.logviewer.utils.ResourceAuthorizer;
import org.apache.storm.metric.StormMetricsRegistry;
import org.apache.storm.testing.TmpPath;
import org.apache.storm.utils.Utils;
import org.junit.jupiter.api.Test;

public class LogviewerProfileHandlerTest {

    @Test
    public void testListDumpFiles() throws Exception {
        try (TmpPath rootPath = new TmpPath()) {

            LogviewerProfileHandler handler = createHandlerTraversalTests(rootPath.getFile().toPath());

            Response topoAResponse = handler.listDumpFiles("topoA", "localhost:1111", "user");
            Response topoBResponse = handler.listDumpFiles("topoB", "localhost:1111", "user");

            Utils.forceDelete(rootPath.toString());

            assertThat(topoAResponse.getStatus(), is(Response.Status.OK.getStatusCode()));
            String contentA = (String) topoAResponse.getEntity();
            assertThat(contentA, containsString("worker.jfr"));
            assertThat(contentA, not(containsString("worker.bin")));
            assertThat(contentA, not(containsString("worker.txt")));
            String contentB = (String) topoBResponse.getEntity();
            assertThat(contentB, containsString("worker.txt"));
            assertThat(contentB, not(containsString("worker.jfr")));
            assertThat(contentB, not(containsString("worker.bin")));
        }
    }

    @Test
    public void testListDumpFilesTraversalInTopoId() throws Exception {
        try (TmpPath rootPath = new TmpPath()) {

            LogviewerProfileHandler handler = createHandlerTraversalTests(rootPath.getFile().toPath());

            Response response = handler.listDumpFiles("../../", "localhost:logs", "user");

            Utils.forceDelete(rootPath.toString());

            assertThat(response.getStatus(), is(Response.Status.NOT_FOUND.getStatusCode()));
        }
    }

    @Test
    public void testListDumpFilesTraversalInPort() throws Exception {
        try (TmpPath rootPath = new TmpPath()) {

            LogviewerProfileHandler handler = createHandlerTraversalTests(rootPath.getFile().toPath());

            Response response = handler.listDumpFiles("../", "localhost:../logs", "user");

            Utils.forceDelete(rootPath.toString());

            assertThat(response.getStatus(), is(Response.Status.NOT_FOUND.getStatusCode()));
        }
    }

    @Test
    public void testDownloadDumpFile() throws IOException {
        try (TmpPath rootPath = new TmpPath()) {

            LogviewerProfileHandler handler = createHandlerTraversalTests(rootPath.getFile().toPath());

            Response topoAResponse = handler.downloadDumpFile("topoA", "localhost:1111", "worker.jfr", "user");
            Response topoBResponse = handler.downloadDumpFile("topoB", "localhost:1111", "worker.txt", "user");

            Utils.forceDelete(rootPath.toString());

            assertThat(topoAResponse.getStatus(), is(Response.Status.OK.getStatusCode()));
            assertThat(topoAResponse.getEntity(), not(nullValue()));
            String topoAContentDisposition = topoAResponse.getHeaderString(HttpHeaders.CONTENT_DISPOSITION);
            assertThat(topoAContentDisposition, containsString("localhost-topoA-1111-worker.jfr"));
            assertThat(topoBResponse.getStatus(), is(Response.Status.OK.getStatusCode()));
            assertThat(topoBResponse.getEntity(), not(nullValue()));
            String topoBContentDisposition = topoBResponse.getHeaderString(HttpHeaders.CONTENT_DISPOSITION);
            assertThat(topoBContentDisposition, containsString("localhost-topoB-1111-worker.txt"));
        }
    }

    @Test
    public void testDownloadDumpFileTraversalInTopoId() throws IOException {
        try (TmpPath rootPath = new TmpPath()) {

            LogviewerProfileHandler handler = createHandlerTraversalTests(rootPath.getFile().toPath());

            Response topoAResponse = handler.downloadDumpFile("../../", "localhost:logs", "daemon-dump.bin", "user");

            Utils.forceDelete(rootPath.toString());

            assertThat(topoAResponse.getStatus(), is(Response.Status.NOT_FOUND.getStatusCode()));
        }
    }

    @Test
    public void testDownloadDumpFileTraversalInPort() throws IOException {
        try (TmpPath rootPath = new TmpPath()) {

            LogviewerProfileHandler handler = createHandlerTraversalTests(rootPath.getFile().toPath());

            Response topoAResponse = handler.downloadDumpFile("../", "localhost:../logs", "daemon-dump.bin", "user");

            Utils.forceDelete(rootPath.toString());

            assertThat(topoAResponse.getStatus(), is(Response.Status.NOT_FOUND.getStatusCode()));
        }
    }

    private LogviewerProfileHandler createHandlerTraversalTests(Path rootPath) throws IOException {
        Path daemonLogRoot = rootPath.resolve("logs");
        Path fileOutsideDaemonRoot = rootPath.resolve("evil.bin");
        Path workerLogRoot = daemonLogRoot.resolve("workers-artifacts");
        Path daemonFile = daemonLogRoot.resolve("daemon-dump.bin");
        Path topoA = workerLogRoot.resolve("topoA");
        Path file1 = topoA.resolve("1111").resolve("worker.jfr");
        Path file2 = topoA.resolve("2222").resolve("worker.bin");
        Path file3 = workerLogRoot.resolve("topoB").resolve("1111").resolve("worker.txt");

        Files.createDirectories(file1.getParent());
        Files.createDirectories(file2.getParent());
        Files.createDirectories(file3.getParent());
        Files.write(file1, "TopoA jfr".getBytes(StandardCharsets.UTF_8));
        Files.write(file3, "TopoB txt".getBytes(StandardCharsets.UTF_8));
        Files.createFile(file2);
        Files.createFile(fileOutsideDaemonRoot);
        Files.createFile(daemonFile);

        Map<String, Object> stormConf = Utils.readStormConfig();
        StormMetricsRegistry metricsRegistry = new StormMetricsRegistry();
        return new LogviewerProfileHandler(workerLogRoot.toString(), new ResourceAuthorizer(stormConf), metricsRegistry);
    }

}
