/**
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
package org.apache.storm.hdfs.bolt.sync;

import org.apache.storm.tuple.Tuple;

/**
 * SyncPolicy implementation that does nothing.
 * Closing/Rotating a file will sync it to disk.
 */
public class NullSyncPolicy implements SyncPolicy {
    @Override
    public boolean mark(Tuple tuple, long offset) {
        return false;
    }

    @Override
    public void reset() {
        //Empty
    }
}
