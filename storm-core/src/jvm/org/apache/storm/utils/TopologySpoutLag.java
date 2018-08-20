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

package org.apache.storm.utils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.storm.generated.ComponentCommon;
import org.apache.storm.generated.ComponentObject;
import org.apache.storm.generated.SpoutSpec;
import org.apache.storm.generated.StormTopology;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TopologySpoutLag {
    // FIXME: This class can be moved to webapp once UI porting is done.

    private static final String SPOUT_ID = "spoutId";
    private static final String SPOUT_TYPE = "spoutType";
    private static final String SPOUT_LAG_RESULT = "spoutLagResult";
    private static final String ERROR_INFO = "errorInfo";
    private static final String CONFIG_KEY_PREFIX = "config.";
    private final static Logger logger = LoggerFactory.getLogger(TopologySpoutLag.class);

    public static Map<String, Map<String, Object>> lag(StormTopology stormTopology, Map<String, Object> topologyConf) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        Map<String, SpoutSpec> spouts = stormTopology.get_spouts();
        String className = null;
        for (Map.Entry<String, SpoutSpec> spout : spouts.entrySet()) {
            try {
                SpoutSpec spoutSpec = spout.getValue();
                addLagResultForKafkaSpout(result, spout.getKey(), spoutSpec);
            } catch (Exception e) {
                logger.warn("Exception thrown while getting lag for spout id: " + spout.getKey() + " and spout class: " + className);
                logger.warn("Exception message:" + e.getMessage(), e);
            }
        }
        return result;
    }

    private static List<String> getCommandLineOptionsForNewKafkaSpout(Map<String, Object> jsonConf) {
        logger.debug("json configuration: {}", jsonConf);

        List<String> commands = new ArrayList<>();
        commands.add("-t");
        commands.add((String) jsonConf.get(CONFIG_KEY_PREFIX + "topics"));
        commands.add("-g");
        commands.add((String) jsonConf.get(CONFIG_KEY_PREFIX + "groupid"));
        commands.add("-b");
        commands.add((String) jsonConf.get(CONFIG_KEY_PREFIX + "bootstrap.servers"));
        String securityProtocol = (String) jsonConf.get(CONFIG_KEY_PREFIX + "security.protocol");
        if (securityProtocol != null && !securityProtocol.isEmpty()) {
            commands.add("-s");
            commands.add(securityProtocol);
        }
        return commands;
    }

    private static void addLagResultForKafkaSpout(Map<String, Map<String, Object>> finalResult, String spoutId, SpoutSpec spoutSpec)
        throws IOException {
        ComponentCommon componentCommon = spoutSpec.get_common();
        String json = componentCommon.get_json_conf();
        if (json != null && !json.isEmpty()) {
            Map<String, Object> jsonMap = null;
            try {
                jsonMap = (Map<String, Object>) JSONValue.parseWithException(json);
            } catch (ParseException e) {
                throw new IOException(e);
            }

            if (jsonMap.containsKey(CONFIG_KEY_PREFIX + "topics")
                && jsonMap.containsKey(CONFIG_KEY_PREFIX + "groupid")
                && jsonMap.containsKey(CONFIG_KEY_PREFIX + "bootstrap.servers")) {

                Map<String, Object> result = null;
                List<String> commands = new ArrayList<>();
                String stormHomeDir = System.getenv("STORM_BASE_DIR");
                if (stormHomeDir != null && !stormHomeDir.endsWith("/")) {
                    stormHomeDir += File.separator;
                }
                commands.add(stormHomeDir != null ? stormHomeDir + "bin" + File.separator + "storm-kafka-monitor" : "storm-kafka-monitor");
                commands.addAll(getCommandLineOptionsForNewKafkaSpout(jsonMap));
                String errorMsg = "INTERNAL ERROR";

                logger.debug("Command to run: {}", commands);

                String resultFromMonitor = new ShellCommandRunnerImpl().execCommand(commands.toArray(new String[0]));

                try {
                    result = (Map<String, Object>) JSONValue.parseWithException(resultFromMonitor);
                } catch (ParseException e) {
                    logger.debug("JSON parsing failed, assuming message as error message: {}", resultFromMonitor);
                    // json parsing fail -> error received
                    errorMsg = resultFromMonitor;
                }

                Map<String, Object> kafkaSpoutLagInfo = new HashMap<>();
                kafkaSpoutLagInfo.put(SPOUT_ID, spoutId);
                kafkaSpoutLagInfo.put(SPOUT_TYPE, "KAFKA");

                if (result != null) {
                    kafkaSpoutLagInfo.put(SPOUT_LAG_RESULT, result);
                } else {
                    kafkaSpoutLagInfo.put(ERROR_INFO, errorMsg);
                }

                finalResult.put(spoutId, kafkaSpoutLagInfo);
            }
        }
    }
}
