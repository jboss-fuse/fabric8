/**
 *  Copyright 2005-2015 Red Hat, Inc.
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
package io.fabric8.patch.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class PatchData {

    private static final String ID = "id";
    private static final String DESCRIPTION = "description";
    private static final String BUNDLES = "bundle";
    private static final String REQUIREMENTS = "requirement";
    private static final String FILES = "file";
    private static final String SOURCE = "source";
    private static final String TARGET = "target";
    private static final String COUNT = "count";
    private static final String RANGE = "range";
    private static final String MIGRATOR_BUNDLE = "migrator-bundle";

    private final String id;
    private final String description;
    private final String migratorBundle;
    private final Collection<String> bundles;
    private final Map<String, String> versionRanges;
    private final Collection<String> requirements;
    private final Collection<String> files = new LinkedList<String>();

    public PatchData(String id, String description, Collection<String> bundles, Map<String, String> versionRanges, Collection<String> requirements, String migratorBundle) {
        this.id = id;
        this.description = description;
        this.bundles = bundles;
        this.versionRanges = versionRanges;
        this.requirements = requirements;
        this.migratorBundle = migratorBundle;
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public String getVersionRange(String bundle) {
        return versionRanges.get(bundle);
    }

    public Collection<String> getBundles() {
        return bundles;
    }

    public Collection<String> getRequirements() {
        return requirements;
    }

    public Collection<String> getFiles() {
        return files;
    }

    public static PatchData load(InputStream is) throws IOException {
        Properties props = new Properties();
        props.load(is);
        String id = props.getProperty(ID);
        String desc = props.getProperty(DESCRIPTION);
        String installerBundle = props.getProperty(MIGRATOR_BUNDLE);
        List<String> bundles = new ArrayList<String>();
        Map<String, String> ranges = new HashMap<String, String>();
        int count = Integer.parseInt(props.getProperty(BUNDLES + "." + COUNT, "0"));
        for (int i = 0; i < count; i++) {
            String key = BUNDLES + "." + Integer.toString(i);
            String bundle = props.getProperty(key);
            bundles.add(bundle);

            if (props.containsKey(key + "." + RANGE)) {
                ranges.put(bundle, props.getProperty(key + "." + RANGE));
            }
        }
        List<String> requirements = new ArrayList<String>();
        int requirementCount = Integer.parseInt(props.getProperty(REQUIREMENTS + "." + COUNT, "0"));
        for (int i = 0; i < requirementCount; i++) {
            String key = REQUIREMENTS + "." + Integer.toString(i);
            String requirement = props.getProperty(key);
            requirements.add(requirement);
        }
        PatchData result = new PatchData(id, desc, bundles, ranges, requirements, installerBundle);
        // add info for patched files
        count = Integer.parseInt(props.getProperty(FILES + "." + COUNT, "0"));
        for (int i = 0; i < count; i++) {
            result.files.add(props.getProperty(FILES + "." + Integer.toString(i)));
        }
        return result;
    }

    public String getMigratorBundle() {
        return migratorBundle;
    }
}
