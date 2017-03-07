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
package org.apache.hyracks.api.application;

import org.apache.hyracks.api.config.IConfigManager;
import org.apache.hyracks.api.job.resource.NodeCapacity;
import org.kohsuke.args4j.OptionHandlerFilter;

public interface INCApplicationEntryPoint {
    void start(INCApplicationContext ncAppCtx, String[] args) throws Exception;

    void notifyStartupComplete() throws Exception;

    void stop() throws Exception;

    NodeCapacity getCapacity();

    void registerConfigOptions(IConfigManager configManager);

    default OptionHandlerFilter getUsageFilter() {
        return OptionHandlerFilter.PUBLIC;
    }
}
