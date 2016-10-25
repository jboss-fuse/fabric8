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
package io.fabric8.patch.management.conflicts;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.felix.utils.properties.Properties;

public class Fabric8AgentPropertiesFileResolver extends PropertiesFileResolver {

    /**
     * In case of <code>io.fabric8.agent.properties</code> we may resolve conflicts with "attribute.parents"
     * property - we simply collect'em all
     * @param key
     * @param firstProperties
     * @param secondProperties
     * @param rollback
     * @return
     */
    @Override
    protected String specialPropertyMerge(String key, Properties firstProperties, Properties secondProperties, boolean rollback) {
        if ("attribute.parents".equals(key)) {
            String parentsFromPatch = firstProperties.get(key);
            Set<String> parentNamesFromPatch = new LinkedHashSet<>(Arrays.asList(parentsFromPatch.split("\\s+")));
            String currentParents = secondProperties.get(key);
            Set<String> currentParentNames = new LinkedHashSet<>(Arrays.asList(currentParents.split("\\s+")));
            parentNamesFromPatch.addAll(currentParentNames);
            StringBuilder sw = new StringBuilder();
            for (String fn : parentNamesFromPatch) {
                sw.append(" ").append(fn);
            }
            return sw.toString().length() > 0 ? sw.toString().substring(1) : "";
        }
        return super.specialPropertyMerge(key, firstProperties, secondProperties, rollback);
    }

}
