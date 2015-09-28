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
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import io.fabric8.patch.management.impl.GitPatchManagementServiceImpl;
import io.fabric8.patch.management.impl.Utils;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.AbstractFileFilter;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PatchManagementTest {

    private File karafHome;
    private File patchesHome;

    private PatchManagement pm;
    private BundleContext bundleContext;
    private BundleContext systemContext;
    private Bundle systemBundle;
    private Properties properties;

    @Before
    public void prepareProperties() throws IOException {
        karafHome = new File("target/karaf");
        FileUtils.deleteQuietly(karafHome);
        patchesHome = new File(karafHome, "patches");

        properties = new Properties();
        properties.setProperty("karaf.home", karafHome.getCanonicalPath());
        properties.setProperty("karaf.default.repository", "system");
        properties.setProperty("fuse.patch.location", new File(karafHome, "patches").getCanonicalPath());

        systemContext = mock(BundleContext.class);
        systemBundle = mock(Bundle.class);
        bundleContext = mock(BundleContext.class);

        when(bundleContext.getBundle(0)).thenReturn(systemBundle);
        when(systemBundle.getBundleContext()).thenReturn(systemContext);
        when(systemContext.getProperty(Mockito.anyString())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                return properties.get(invocation.getArguments()[0]);
            }
        });

        pm = new GitPatchManagementServiceImpl(bundleContext);
        ((GitPatchManagementServiceImpl)pm).start();

        // prepare some ZIP patches
        preparePatchZip("src/test/resources/content/patch1", "target/karaf/patches/source/patch-1.zip");
        preparePatchZip("src/test/resources/content/patch3", "target/karaf/patches/source/patch-3.zip");
    }

    @Test
    public void fetchPatch1FromZipFileWithDescriptor() throws IOException {
        List<PatchData> patches = pm.fetchPatches(new File("target/karaf/patches/source/patch-1.zip").toURI().toURL());
        assertThat(patches.size(), equalTo(1));
        PatchData data = patches.get(0);
        assertThat(data.getId(), equalTo("my-patch-1"));
        assertThat(data.getBundles().size(), equalTo(1));
        assertThat(data.getBundles().iterator().next(), equalTo("io/fabric8/fabric-tranquility/1.2.3/fabric-tranquility-1.2.3.jar"));
        assertThat(data.getFiles().size(), equalTo(2));

        assertThat(FileUtils.readFileToString(new File(karafHome, "patches/my-patch-1/bin/start")), equalTo("#!/bin/bash\n\necho \"started\"\n"));
        assertThat("Fetching a patch should extract maven artifacts immediately",
                FileUtils.readFileToString(new File(karafHome, "system/io/fabric8/fabric-tranquility/1.2.3/fabric-tranquility-1.2.3.jar")),
                equalTo("JAR\n"));
    }

    @Test
    public void fetchPatch2AsPlainDescriptor() throws IOException {
        List<PatchData> patches = pm.fetchPatches(new File("src/test/resources/descriptors/my-patch-2.patch").toURI().toURL());
        assertThat(patches.size(), equalTo(1));
        PatchData data = patches.get(0);
        assertThat(data.getId(), equalTo("my-patch-2"));
        assertThat(data.getBundles().size(), equalTo(1));
        assertThat(data.getBundles().iterator().next(), equalTo("mvn:io.fabric8/fabric-tranquility/1.2.3"));
        assertThat(data.getFiles().size(), equalTo(0));

        assertFalse(new File(karafHome, "patches/my-patch-2").exists());
        assertTrue(new File(karafHome, "patches/my-patch-2.patch").exists());
        assertTrue(new File(karafHome, "patches/my-patch-2.patch").isFile());
    }

    @Test
    public void fetchPatch3FromZipFileWithoutDescriptor() throws IOException {
        List<PatchData> patches = pm.fetchPatches(new File("target/karaf/patches/source/patch-3.zip").toURI().toURL());
        assertThat(patches.size(), equalTo(1));
        PatchData data = patches.get(0);
        assertTrue(data.isGenerated());
        assertThat(data.getId(), equalTo("patch-3"));
        assertThat(data.getBundles().size(), equalTo(1));
        assertThat(data.getBundles().iterator().next(), equalTo("io/fabric8/fabric-colours/1.2.3/fabric-colours-1.2.3.jar"));
        assertThat(data.getFiles().size(), equalTo(2));

        assertThat(FileUtils.readFileToString(new File(karafHome, "patches/patch-3/bin/stop")), equalTo("#!/bin/bash\n\necho \"stopped\"\n"));
        assertThat("Fetching a patch should extract maven artifacts immediately",
                FileUtils.readFileToString(new File(karafHome, "system/io/fabric8/fabric-colours/1.2.3/fabric-colours-1.2.3.jar")),
                equalTo("JAR\n"));
        assertFalse("Maven artifacts should not be extracted to patch data directory", new File(karafHome, "patches/patch-3/system").exists());
    }

    private void preparePatchZip(String directoryToZip, String zipFile) throws IOException {
        File zip = new File(zipFile);
        zip.getParentFile().mkdirs();
        final ZipArchiveOutputStream zos1 = new ZipArchiveOutputStream(new FileOutputStream(zip));
        final File patchDirectory1 = new File(directoryToZip);
        FileUtils.iterateFilesAndDirs(patchDirectory1, new AbstractFileFilter() {
            @Override
            public boolean accept(File file) {
                if (file.isDirectory()) {
                    return false;
                }
                String path = Utils.relative(patchDirectory1, file);
                ZipArchiveEntry entry = new ZipArchiveEntry(path);
                try {
                    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file.toPath());
                    entry.setUnixMode(Utils.getUnixModeFromPermissions(file, permissions));
                    byte[] bytes = FileUtils.readFileToByteArray(file);
                    zos1.putArchiveEntry(entry);
                    zos1.write(bytes, 0, bytes.length);
                    zos1.closeArchiveEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
                return true;
            }
        }, DirectoryFileFilter.DIRECTORY);
        zos1.close();
    }

}
