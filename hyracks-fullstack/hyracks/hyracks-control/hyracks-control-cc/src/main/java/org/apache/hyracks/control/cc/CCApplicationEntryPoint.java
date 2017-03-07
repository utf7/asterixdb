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
package org.apache.hyracks.control.cc;

import java.util.Arrays;

import org.apache.hyracks.api.application.ICCApplicationContext;
import org.apache.hyracks.api.application.ICCApplicationEntryPoint;
import org.apache.hyracks.api.config.IConfigManager;
import org.apache.hyracks.api.config.Section;
import org.apache.hyracks.api.job.resource.DefaultJobCapacityController;
import org.apache.hyracks.api.job.resource.IJobCapacityController;
import org.apache.hyracks.control.common.controllers.CCConfig;
import org.apache.hyracks.control.common.controllers.ControllerConfig;
import org.apache.hyracks.control.common.controllers.NCConfig;

public class CCApplicationEntryPoint implements ICCApplicationEntryPoint {
    public static final ICCApplicationEntryPoint INSTANCE = new CCApplicationEntryPoint();

    protected CCApplicationEntryPoint() {
    }

    @Override
    public void start(ICCApplicationContext ccAppCtx, String[] args) throws Exception {
        if (args.length > 0) {
            throw new IllegalArgumentException("Unrecognized argument(s): " + Arrays.toString(args));
        }
    }

    @Override
    public void stop() throws Exception {
        // no-op
    }

    @Override
    public void startupCompleted() throws Exception {
        // no-op
    }

    @Override
    public IJobCapacityController getJobCapacityController() {
        return DefaultJobCapacityController.INSTANCE;
    }

    @Override
    public void registerConfig(IConfigManager configManager) {
        configManager.addIniParamOptions(ControllerConfig.Option.CONFIG_FILE, ControllerConfig.Option.CONFIG_FILE_URL);
        configManager.addCmdLineSections(Section.CC, Section.COMMON);
        configManager.setUsageFilter(getUsageFilter());
        configManager.register(ControllerConfig.Option.class, CCConfig.Option.class, NCConfig.Option.class);
    }
}
