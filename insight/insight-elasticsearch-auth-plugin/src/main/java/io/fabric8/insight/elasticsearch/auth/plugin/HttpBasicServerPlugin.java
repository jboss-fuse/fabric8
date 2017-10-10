/**
 *  Copyright 2005-2016 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.insight.elasticsearch.auth.plugin;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.AbstractPlugin;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.settings.ImmutableSettings;

import java.util.Collection;

import static org.elasticsearch.common.collect.Lists.*;

public class HttpBasicServerPlugin extends AbstractPlugin {

    private boolean enabledByDefault = true;
    private final Settings settings;

    @Inject public HttpBasicServerPlugin(Settings settings) {
        this.settings = settings;
    }

    @Override public String name() {
        return "fabric8-http-basic-plugin";
    }

    @Override public String description() {
        return "HTTP Basic Server Plugin for fabric8 v1";
    }

    @Override public Collection<Class<? extends Module>> modules() {
        Collection<Class<? extends Module>> modules = newArrayList();
        if (settings.getAsBoolean("http.basic.enabled", enabledByDefault)) {
            modules.add(HttpBasicServerModule.class);
        }
        return modules;
    }

    @Override public Collection<Class<? extends LifecycleComponent>> services() {
        Collection<Class<? extends LifecycleComponent>> services = newArrayList();
        if (settings.getAsBoolean("http.basic.enabled", enabledByDefault)) {
            services.add(HttpBasicServer.class);
        }
        return services;
    }

    @Override public Settings additionalSettings() {
        if (settings.getAsBoolean("http.basic.enabled", enabledByDefault)) {
            return ImmutableSettings.settingsBuilder().
                    put("http.enabled", false).                    
                    build();
        } else {
            return ImmutableSettings.Builder.EMPTY_SETTINGS;
        }
    }
}
