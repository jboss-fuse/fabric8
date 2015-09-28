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
package io.fabric8.patch.management;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.IOUtils;

/**
 * Information about patch ZIP content
 */
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

    private boolean generated;

    private final String id;
    private String description;
    private String migratorBundle;

    // directory base of unpacked patch file. May be null if patch file was *.patch, not a ZIP file.
    // this path is relative to ${karaf.home}
    private String patchDirectory;

    private Collection<String> bundles = new LinkedList<>();
    private Collection<String> featureFiles = new LinkedList<>();
    private Collection<String> otherArtifacts = new LinkedList<>();
    private Collection<String> files = new LinkedList<String>();

    private Map<String, String> versionRanges;

    private Collection<String> requirements;

    // TODO: ↓
    private Map<String, Long> fileSizes = new HashMap<>();
    private Map<String, Long> artifactSizes = new HashMap<>();

    public PatchData(String id) {
        this.id = id;
        this.description = id;
    }

    public PatchData(String id, String description, Collection<String> bundles, Map<String, String> versionRanges, Collection<String> requirements, String migratorBundle) {
        this.id = id;
        this.description = description;
        this.bundles = bundles;
        this.versionRanges = versionRanges;
        this.requirements = requirements;
        this.migratorBundle = migratorBundle;
    }

    /**
     * Static constructor of PatchData object that takes initialization data from {@link java.io.InputStream}.
     * @param inputStream
     * @return
     * @throws IOException
     */
    public static PatchData load(InputStream inputStream) throws IOException {
        Properties props = new Properties();
        props.load(inputStream);
        IOUtils.closeQuietly(inputStream);
        return load(props);
    }

    /**
     * Static constructor of PatchData object that takes initialization data from {@link Properties} file.
     * @param props
     * @return
     * @throws IOException
     */
    public static PatchData load(Properties props) throws IOException {
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

    /**
     * We can write the patch data as well. The <code>out</code> {@link OutputStream} is closed after the write.
     * @param out
     */
    public void storeTo(OutputStream out) {
        PrintWriter pw = new PrintWriter(out);
        pw.write("# generated file, do not modify\n");
        pw.write("id = " + getId() + "\n");
        int n = 0;
        if (bundles.size() > 0) {
            for (String bundle : bundles) {
                pw.write(String.format("bundle.%d = %s\n", n++, bundle));
            }
            pw.write(String.format("bundle.count = %d\n", n));
        }
        n = 0;
        if (featureFiles.size() > 0) {
            for (String ff : featureFiles) {
                pw.write(String.format("featureDescriptor.%d = %s\n", n++, ff));
            }
            pw.write(String.format("featureDescriptor.count = %d\n", n));
        }
        n = 0;
        if (otherArtifacts.size() > 0) {
            for (String artifact : otherArtifacts) {
                pw.write(String.format("artifact.%d = %s\n", n++, artifact));
            }
            pw.write(String.format("artifact.count = %d\n", n));
        }
        n = 0;
        if (files.size() > 0) {
            for (String file : files) {
                pw.write(String.format("file.%d = %s\n", n++, file));
            }
            pw.write(String.format("file.count = %d\n", n));
        }
        if (migratorBundle != null) {
            pw.write(String.format("%s = %s\n", MIGRATOR_BUNDLE, migratorBundle));
        }
        pw.close();
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

    public Collection<String> getFeatureFiles() {
        return featureFiles;
    }

    public Collection<String> getOtherArtifacts() {
        return otherArtifacts;
    }

    public Collection<String> getRequirements() {
        return requirements;
    }

    public Collection<String> getFiles() {
        return files;
    }

    public String getMigratorBundle() {
        return migratorBundle;
    }

    public boolean isGenerated() {
        return generated;
    }

    public void setGenerated(boolean generated) {
        this.generated = generated;
    }

    public String getPatchDirectory() {
        return patchDirectory;
    }

    /**
     * Sets a path (relative to ${karaf.home}) to the directory where patch content (without patch descriptor)
     * is unpacked.
     * @param patchDirectory
     */
    public void setPatchDirectory(String patchDirectory) {
        this.patchDirectory = patchDirectory;
    }

}
