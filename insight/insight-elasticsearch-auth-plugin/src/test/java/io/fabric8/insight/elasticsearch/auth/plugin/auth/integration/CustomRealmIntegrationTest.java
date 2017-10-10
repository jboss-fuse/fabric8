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
package io.fabric8.insight.elasticsearch.auth.plugin.auth.integration;

import io.fabric8.insight.elasticsearch.auth.plugin.HttpBasicServerPlugin;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;

public class CustomRealmIntegrationTest extends DefaultConfigurationIntegrationTest {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return ImmutableSettings.settingsBuilder()
                .put("http.basic.realm", "another")
                .put("plugin.types", HttpBasicServerPlugin.class.getName())
                .put("path.data", DEFAULT_DATA_DIRECTORY)
                .build();
    }
}