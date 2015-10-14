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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.IOUtils;

/**
 * <p>Information about patch ZIP content - static part of patch information before it is added and installed.</p>
 * <p>The information from the descriptor is immutable - it isn't altered by patch management after retrieving the
 * descriptor from ZIP file or URL when <code>patch:add</code>ing.</p>
 */
public class PatchData {

    private static final String ID = "id";
    private static final String DESCRIPTION = "description";
    private static final String ROLLUP = "rollup";
    private static final String BUNDLES = "bundle";
    private static final String REQUIREMENTS = "requirement";
    private static final String FILES = "file";
    private static final String SOURCE = "source";
    private static final String TARGET = "target";
    private static final String COUNT = "count";
    private static final String RANGE = "range";
    private static final String MIGRATOR_BUNDLE = "migrator-bundle";
    private static final String FEATURE_DESCRIPTOR = "featureDescriptor";

    // when ZIP file doesn't contain *.patch descriptor, we'll generate it on the fly
    private boolean generated;

    // patch may or may not be a rollup patch. Rollup patch creates new baseline when installed
    // non-rollup patch is a simple diff that may be committed (or cherry-picked) and reverted along the user changes
    // commits in "main" patch branch. When rollup patch is installed, all user changes without non-rollup cherry-picks
    // are rebased (git rebase) on top of new baseline tag
    private boolean rollupPatch = false;

    private final String id;
    private String description;
    private String migratorBundle;

    // directory base of unpacked patch file. May be null if patch file was *.patch, not a ZIP file.
    // this field is kind of "transient" - it's not stored in *.patch file, only set after reading the file
    // to point to real directory where patch was unpacked
    private File patchDirectory;
    // even if patch has no content, we'll keep the directory where *.patch descriptor itself is stored
    // e.g., to be able to write *.patch.result file
    private File patchLocation;

    private List<String> bundles = new LinkedList<>();
    private List<String> featureFiles = new LinkedList<>();
    private List<String> otherArtifacts = new LinkedList<>();
    private List<String> files = new LinkedList<String>();

    private Map<String, String> versionRanges;

    private List<String> requirements;

    // TODO: ↓
    private Map<String, Long> fileSizes = new HashMap<>();
    private Map<String, Long> artifactSizes = new HashMap<>();

    public PatchData(String id) {
        this.id = id;
        this.description = id;
    }

    public PatchData(String id, String description, List<String> bundles, List<String> featureFiles, Map<String, String> versionRanges, List<String> requirements, String migratorBundle) {
        this.id = id;
        this.description = description;
        this.bundles = bundles;
        this.featureFiles = featureFiles;
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
        boolean rollupPatch = "true".equals(props.getProperty(ROLLUP));

        List<String> bundles = new ArrayList<String>();
        List<String> featureDescriptors = new ArrayList<String>();
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

        count = Integer.parseInt(props.getProperty(FEATURE_DESCRIPTOR + "." + COUNT, "0"));
        for (int i = 0; i < count; i++) {
            String key = FEATURE_DESCRIPTOR + "." + Integer.toString(i);
            featureDescriptors.add(props.getProperty(key));
        }

        List<String> requirements = new ArrayList<String>();
        int requirementCount = Integer.parseInt(props.getProperty(REQUIREMENTS + "." + COUNT, "0"));
        for (int i = 0; i < requirementCount; i++) {
            String key = REQUIREMENTS + "." + Integer.toString(i);
            String requirement = props.getProperty(key);
            requirements.add(requirement);
        }

        PatchData result = new PatchData(id, desc, bundles, featureDescriptors, ranges, requirements, installerBundle);
        result.setRollupPatch(rollupPatch);
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
        pw.write(ROLLUP + " = " + Boolean.toString(rollupPatch) + "\n");
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
                pw.write(String.format("%s.%d = %s\n", FEATURE_DESCRIPTOR, n++, ff));
            }
            pw.write(String.format("%s.%s = %d\n", FEATURE_DESCRIPTOR, COUNT, n));
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

    public List<String> getBundles() {
        return bundles;
    }

    public List<String> getFeatureFiles() {
        return featureFiles;
    }

    public List<String> getOtherArtifacts() {
        return otherArtifacts;
    }

    public List<String> getRequirements() {
        return requirements;
    }

    public List<String> getFiles() {
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

    public boolean isRollupPatch() {
        return rollupPatch;
    }

    public void setRollupPatch(boolean rollupPatch) {
        this.rollupPatch = rollupPatch;
    }

    public File getPatchDirectory() {
        return patchDirectory;
    }

    /**
     * Sets a directory where patch content (without patch descriptor) is unpacked. This directory must exist.
     * This field isn't stored in <code>*.patch</code> file, it has to be set explicitly after reading the
     * patch descriptor.
     * @param patchDirectory
     */
    public void setPatchDirectory(File patchDirectory) {
        this.patchDirectory = patchDirectory;
    }

    public File getPatchLocation() {
        return patchLocation;
    }

    /**
     * Sets a directory where patch descriptor itself is stored
     * @param patchLocation
     */
    public void setPatchLocation(File patchLocation) {
        this.patchLocation = patchLocation;
    }

}
