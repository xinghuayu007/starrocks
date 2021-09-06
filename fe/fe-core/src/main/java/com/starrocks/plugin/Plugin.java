// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/plugin/Plugin.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.plugin;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * Base Plugin
 * <p>
 * Note: plugin is thread-unsafe and work in parallel
 */
public abstract class Plugin implements Closeable {
    public static final int PLUGIN_DEFAULT_FLAGS = 0;
    public static final int PLUGIN_INSTALL_EARLY = 1;
    public static final int PLUGIN_NOT_DYNAMIC_UNINSTALL = 1 << 1;

    /**
     * invoke when the plugin install.
     * the plugin should only be initialized once.
     * So this method must be idempotent
     */
    public void init(PluginInfo info, PluginContext ctx) throws PluginException {

    }

    /**
     * invoke when the plugin uninstall
     */
    @Override
    public void close() throws IOException {
    }

    public int flags() {
        return PLUGIN_DEFAULT_FLAGS;
    }

    public void setVariable(String key, String value) {
    }

    public Map<String, String> variable() {
        return Collections.EMPTY_MAP;
    }

    public Map<String, String> status() {
        return Collections.EMPTY_MAP;
    }
}