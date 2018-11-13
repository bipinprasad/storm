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

package org.apache.storm.perf;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.storm.Config;
import org.apache.storm.StormSubmitter;
import org.apache.storm.generated.AlreadyAliveException;
import org.apache.storm.generated.AuthorizationException;
import org.apache.storm.generated.InvalidTopologyException;
import org.apache.storm.metric.LoggingMetricsConsumer;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.BasicOutputCollector;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.topology.base.BaseBasicBolt;
import org.apache.storm.topology.base.BaseRichSpout;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.apache.storm.utils.ObjectReader;
import org.apache.storm.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a really simple topology. But it's useful for easy-to-understand performance testings especially for ShuffleGrouping.
 * We can configure spout/bolt waiting time and parallelisms so that the performance testing is mostly under our control.
 * This is not a real world topology but it's a good start point to understand the performance gain/loss.
 */
public class SimpleShuffleGroupingTopology {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleShuffleGroupingTopology.class);

    private static final String SPOUT_WAITING_MS = "spout.waiting.ms";
    private static final String BOLT_WAITING_MS = "bolt.waiting.ms";

    static class SimpleSpout extends BaseRichSpout {

        SpoutOutputCollector collector;
        Random rand;
        List<String> words;
        long waitingMs = 5;

        @Override
        public void open(Map<String, Object> conf, TopologyContext context, SpoutOutputCollector collector) {
            this.collector = collector;
            rand = new Random();
            words = Arrays.asList("ethan", "yining");
            waitingMs = ObjectReader.getLong(conf.getOrDefault(SPOUT_WAITING_MS, 5));
        }

        @Override
        public void nextTuple() {
            Utils.sleep(waitingMs);
            String word = words.get(rand.nextInt(words.size()));
            LOG.debug("Emitting tuple: {}", word);
            collector.emit(new Values(word));
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {
            declarer.declare(new Fields("word"));
        }
    }

    static class SimpleBolt extends BaseBasicBolt {
        long waitingMs = 20;

        @Override
        public void prepare(Map<String, Object> topoConf, TopologyContext context) {
            waitingMs = ObjectReader.getLong(topoConf.getOrDefault(BOLT_WAITING_MS, 20));
        }

        @Override
        public void execute(Tuple input, BasicOutputCollector collector) {
            String word = input.getString(0);
            LOG.debug("executing: {}", word);
            Utils.sleep(waitingMs);
        }

        @Override
        public void declareOutputFields(OutputFieldsDeclarer declarer) {

        }
    }

    /**
     * Main function.
     * @param args args
     * @throws InvalidTopologyException when topology is invalid
     * @throws AuthorizationException when it's not authorized
     * @throws AlreadyAliveException when the topology is already alive
     */
    public static void main(String[] args) throws InvalidTopologyException, AuthorizationException, AlreadyAliveException {
        TopologyBuilder builder = new TopologyBuilder();

        int spoutParallel = 2;
        int boltParallel = 10;
        String topologyName = "simple-topology";
        if (args.length == 3) {
            topologyName = args[0];
            spoutParallel = Integer.parseInt(args[1]);
            boltParallel = Integer.parseInt(args[2]);
        }

        builder.setSpout("spout", new SimpleSpout(), spoutParallel);
        builder.setBolt("bolt", new SimpleBolt(), boltParallel).shuffleGrouping("spout");
        Config conf = new Config();
        conf.registerMetricsConsumer(LoggingMetricsConsumer.class);

        StormSubmitter.submitTopology(topologyName, conf, builder.createTopology());
    }
}
