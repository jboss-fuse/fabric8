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
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.AbstractFileFilter;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public abstract class PatchTestSupport {

    protected File karafHome;
    protected File karafBase;
    protected File patchesHome;

    protected Properties properties;

    protected BundleContext bundleContext;
    protected Bundle bundle;
    protected BundleContext systemContext;
    protected Bundle system;

    protected void init(boolean deleteDirs, boolean defaultDirs) throws IOException, GitAPIException {
        if (defaultDirs) {
            karafHome = new File("target/karaf");
            karafBase = new File("target/karaf");
        }
        if (deleteDirs) {
            FileUtils.deleteQuietly(karafHome);
            FileUtils.deleteQuietly(karafBase);
        }
        patchesHome = new File(karafHome, "patches");

        properties = new Properties();
        properties.setProperty("karaf.home", karafHome.getCanonicalPath());
        properties.setProperty("karaf.base", karafBase.getCanonicalPath());
        properties.setProperty("karaf.data", karafBase.getCanonicalPath() + File.separator + "data");
        properties.setProperty("karaf.default.repository", "system");
        properties.setProperty("fuse.patch.location", new File(karafHome, "patches").getCanonicalPath());

        systemContext = mock(BundleContext.class);
        system = mock(Bundle.class);
        bundleContext = mock(BundleContext.class);
        bundle = mock(Bundle.class);

        when(bundleContext.getBundle()).thenReturn(bundle);
        when(bundle.getVersion()).thenReturn(new Version(1, 2, 0));
        when(bundleContext.getBundle(0)).thenReturn(system);
        when(system.getBundleContext()).thenReturn(systemContext);
        when(systemContext.getProperty(Mockito.anyString())).thenAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                return properties.get(invocation.getArguments()[0]);
            }
        });
        when(systemContext.getBundles()).thenReturn(new Bundle[0]);
    }

    /**
     * Create crucial Karaf files (like etc/startup.properties)
     */
    protected void freshKarafStandaloneDistro() throws IOException {
        FileUtils.copyFile(new File("src/test/resources/karaf/etc/startup.properties"), new File(karafHome, "etc/startup.properties"));
        FileUtils.copyFile(new File("src/test/resources/karaf/bin/admin"), new File(karafHome, "bin/admin"));
        FileUtils.copyFile(new File("src/test/resources/karaf/bin/start"), new File(karafHome, "bin/start"));
        FileUtils.copyFile(new File("src/test/resources/karaf/bin/stop"), new File(karafHome, "bin/stop"));
        FileUtils.copyFile(new File("src/test/resources/karaf/bin/setenv"), new File(karafHome, "bin/setenv"));
        FileUtils.copyFile(new File("src/test/resources/karaf/lib/karaf.jar"), new File(karafHome, "lib/karaf.jar"));
        FileUtils.copyFile(new File("src/test/resources/karaf/fabric/import/fabric/profiles/default.profile/io.fabric8.version.properties"),
                new File(karafHome, "fabric/import/fabric/profiles/default.profile/io.fabric8.version.properties"));
        new File(karafHome, "licenses").mkdirs();
        new File(karafHome, "metatype").mkdirs();
    }

    protected void preparePatchZip(String directoryToZip, String zipFile, final boolean includeParentDirectory) throws IOException {
        File zip = new File(zipFile);
        zip.getParentFile().mkdirs();
        final ZipArchiveOutputStream zos1 = new ZipArchiveOutputStream(new FileOutputStream(zip));
        final File patchDirectory = new File(directoryToZip);
        FileUtils.iterateFilesAndDirs(patchDirectory, new AbstractFileFilter() {
            @Override
            public boolean accept(File file) {
                if (file.isDirectory()) {
                    return false;
                }
                String path = Utils.relative(includeParentDirectory ? patchDirectory.getParentFile() : patchDirectory, file);
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

    protected Object getField(Object object, String fieldName) {
        Field f = null;
        try {
            f = object.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(object);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
