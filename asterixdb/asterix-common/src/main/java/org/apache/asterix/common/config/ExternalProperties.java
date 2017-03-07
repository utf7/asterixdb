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
package org.apache.asterix.common.config;

import static org.apache.hyracks.control.common.config.OptionTypes.*;

import org.apache.hyracks.api.config.IOption;
import org.apache.hyracks.api.config.IOptionType;
import org.apache.hyracks.api.config.Section;

public class ExternalProperties extends AbstractProperties {

    public enum Option implements IOption {
        WEB_PORT(INTEGER, 19001),
        WEB_QUERYINTERFACE_PORT(INTEGER, 19006),
        API_PORT(INTEGER, 19002),
        FEED_PORT(INTEGER, 19003),
        LOG_LEVEL(LEVEL, java.util.logging.Level.WARNING),
        MAX_WAIT_ACTIVE_CLUSTER(INTEGER, 60),
        PLOT_ACTIVATE(BOOLEAN, false),
        CC_JAVA_OPTS(STRING, "-Xmx1024m"),
        NC_JAVA_OPTS(STRING, "-Xmx1024m");

        private final IOptionType type;
        private final Object defaultValue;

        Option(IOptionType type, Object defaultValue) {
            this.type = type;
            this.defaultValue = defaultValue;
        }

        @Override
        public Section section() {
            switch (this) {
                case WEB_PORT:
                case WEB_QUERYINTERFACE_PORT:
                case API_PORT:
                case FEED_PORT:
                    return Section.CC;
                case LOG_LEVEL:
                case MAX_WAIT_ACTIVE_CLUSTER:
                case PLOT_ACTIVATE:
                    return Section.COMMON;
                case CC_JAVA_OPTS:
                case NC_JAVA_OPTS:
                    return Section.VIRTUAL;
                default:
                    throw new IllegalStateException("NYI: " + this);
            }
        }

        @Override
        public String description() {
            // TODO(mblow): add descriptions
            return null;
        }

        @Override
        public IOptionType type() {
            return type;
        }

        @Override
        public Object defaultValue() {
            return defaultValue;
        }
    }

    public ExternalProperties(PropertiesAccessor accessor) {
        super(accessor);
    }

    public int getWebInterfacePort() {
        return accessor.getInt(Option.WEB_PORT);
    }

    public int getQueryWebInterfacePort() {
        return accessor.getInt(Option.WEB_QUERYINTERFACE_PORT);
    }

    public int getAPIServerPort() {
        return accessor.getInt(Option.API_PORT);
    }

    public int getFeedServerPort() {
        return accessor.getInt(Option.FEED_PORT);
    }

    public java.util.logging.Level getLogLevel() {
        return accessor.getLoggingLevel(Option.LOG_LEVEL);
    }

    public int getMaxWaitClusterActive() {
        return accessor.getInt(Option.MAX_WAIT_ACTIVE_CLUSTER);
    }

    public boolean getIsPlottingEnabled() {
        return accessor.getBoolean(Option.PLOT_ACTIVATE);
    }

    public String getNCJavaParams() {
        return accessor.getString(Option.NC_JAVA_OPTS);
    }

    public String getCCJavaParams() {
        return accessor.getString(Option.CC_JAVA_OPTS);
    }
}
