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
package io.fabric8.patch.itests;

import io.fabric8.api.InvalidComponentException;
import io.fabric8.api.gravia.ServiceLocator;
import io.fabric8.api.scr.Validatable;
import io.fabric8.common.util.IOHelpers;
import io.fabric8.itests.support.CommandSupport;
import org.apache.felix.gogo.commands.Action;
import org.apache.felix.gogo.commands.basic.AbstractCommand;
import org.apache.felix.service.command.Function;
import org.apache.felix.utils.version.VersionCleaner;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.*;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.Version;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;

import java.io.*;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Map;
import java.util.jar.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Integration tests for patching the patch-core bundle itself.
 */
@RunWith(Arquillian.class)
@Ignore("Patch bundles are updated not by patch:install, but simply by uninstalling/installing features")
public class PatchThePatchServiceTest extends AbstractPatchCommandIntegrationTest {

    // Bundle-SymbolicName of the bundle we're patching
    private static final String PATCH_CORE_BSN = "io.fabric8.patch.patch-core";

    // Patch name
    private static final String PATCH_ID = "patch-core-fix";

    private static final File KARAF_HOME = new File(System.getProperty("karaf.home"));

    @Deployment
    public static JavaArchive createdeployment() throws Exception {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "test.jar");
        archive.addClass(ServiceLocator.class);
        archive.addClass(IOHelpers.class);
        archive.addPackage(ServiceTracker.class.getPackage());
        archive.addPackages(true, OSGiManifestBuilder.class.getPackage());
        archive.addPackage(CommandSupport.class.getPackage());
        archive.addClass(VersionCleaner.class);
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(Bundle.class, Logger.class);
                builder.addImportPackages(AbstractCommand.class, Action.class, Function.class, Validatable.class);
                builder.addImportPackages(InvalidComponentException.class);
                builder.addImportPackage("org.apache.felix.service.command;status=provisional");
                return builder.openStream();
            }
        });

        // add the patch zip files as resource
        archive.add(createPatchZipFile(PATCH_ID), "/patches", ZipExporter.class);

        return archive;
    }

    // Create a patch zip file
    private static JavaArchive createPatchZipFile(final String name) throws Exception {
        final Version next = getNextVersion();
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name + ".zip");
        archive.addAsResource(new Asset() {
            @Override
            public InputStream openStream() {
                StringBuilder builder = new StringBuilder();
                builder.append(String.format("id = %s%n", PATCH_ID));
                builder.append(String.format("bundle.count = 1%n"));
                builder.append(String.format("bundle.0 = mvn:io.fabric8.patch/patch-core/%s%n", next));
                System.out.println(builder.toString());
                return new ByteArrayInputStream(builder.toString().getBytes());
            }
        }, name + ".patch");
        archive.addAsResource(createNextPatchCoreVersion(), String.format("repository/io/fabric8/patch/patch-core/%1$s/patch-core-%1$s.jar", next));
        return archive;
    }

    // Create a next version of the patch-core jar for testing purposes
    private static File createNextPatchCoreVersion() throws Exception {
        File current = new File(KARAF_HOME, String.format("system/io/fabric8/patch/patch-core/%1$s/patch-core-%1$s.jar", getCurrentMavenVersion()));
        JarFile jar = new JarFile(current);

        File next = new File("target/patched-patch-core.jar");
        JarOutputStream jos = null;
        try {
            // replace all versions in the manifest
            final Manifest manifest = jar.getManifest();
            for (Map.Entry<Object, Object> attribute : manifest.getMainAttributes().entrySet()) {
                manifest.getMainAttributes().putValue(attribute.getKey().toString(),
                        attribute.getValue().toString().replaceAll(getCurrentVersion().toString(), getNextVersion().toString()));
            }

            // create a new JAR with the updated MANIFEST.MF...
            jos = new JarOutputStream(new FileOutputStream(next), manifest);

            // ... and a copy of all the other stuff from the original
            final Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!"META-INF/MANIFEST.MF".equals(entry.getName())) {
                    jos.putNextEntry(entry);
                    IOHelpers.writeTo(jos, jar.getInputStream(entry), false);
                    jos.closeEntry();
                }
            }
        } finally {
            IOHelpers.close(jos);
        }

        return next;
    }

    // Get the next patch-core version
    private static Version getNextVersion() {
        Version current = getCurrentVersion();
        return new Version(current.getMajor(), current.getMinor(), current.getMicro() + 1, current.getQualifier());
    }

    // Get the current patch-core version
    private static Version getCurrentVersion() {
        return Version.parseVersion(VersionCleaner.clean(getCurrentMavenVersion()));
    }

    // Get the current patch-core version (Maven artifact version)
    private static String getCurrentMavenVersion() {
        final String[] files = new File(KARAF_HOME, "system/io/fabric8/patch/patch-core/").list();
        Arrays.sort(files);
        return files[0];
    }

    @Test
    public void testInstallAndRollbackPatch01() throws Exception {
        load(PATCH_ID);

        String version = getPatchCoreBundle().getVersion().toString();

        install(PATCH_ID);
        assertEquals(getNextVersion().toString(), getPatchCoreBundle().getVersion().toString());

        rollback(PATCH_ID);
        assertEquals(version, getPatchCoreBundle().getVersion().toString());
    }

    // Find the bundle we're patching
    private Bundle getPatchCoreBundle() {
        for (Bundle bundle : context.getBundles()) {
            if(PATCH_CORE_BSN.equals(bundle.getSymbolicName())) {
                return bundle;
            }
        }
        fail(String.format("Bundle '%s' was not installed!", PATCH_CORE_BSN));
        return null;
    }
}