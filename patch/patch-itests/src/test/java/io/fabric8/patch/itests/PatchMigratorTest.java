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

import io.fabric8.api.gravia.ServiceLocator;
import io.fabric8.api.scr.Validatable;
import io.fabric8.common.util.IOHelpers;
import io.fabric8.patch.Service;
import io.fabric8.patch.management.PatchManagement;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.util.tracker.ServiceTracker;

import java.io.File;
import java.io.InputStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Integration tests for patching bundles using the patch:* commands.
 */
@RunWith(Arquillian.class)
@Ignore
public class PatchMigratorTest extends AbstractPatchIntegrationTest {

    @Deployment
    public static JavaArchive createdeployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "test.jar");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(Bundle.class, Service.class, PatchManagement.class, Validatable.class);
                return builder.openStream();
            }
        });
        archive.addClass(ServiceLocator.class);
        archive.addClass(IOHelpers.class);
        archive.addPackage(ServiceTracker.class.getPackage());
        archive.addPackages(true, OSGiManifestBuilder.class.getPackage());

        // add the original bundle as well as the patch zip files as resources
        archive.add(createPatchZipFile("migrator-patch-01"), "/patches", ZipExporter.class);
        return archive;
    }

    // Create a 'patchable' bundle with the specified version
    private static JavaArchive createMigratorBundle(final String version) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "migrator-1.0.1.jar");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("migrator");
                builder.addBundleVersion(version);
                builder.addImportPackages("org.osgi.framework");
                builder.addBundleActivator(ExampleMigrator.class);
                return builder.openStream();
            }
        });
        archive.addClass(ExampleMigrator.class);
        return archive;
    }

    // Create a patch zip file
    private static JavaArchive createPatchZipFile(final String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name + ".zip");
        archive.addAsResource("patches/" + name + ".patch", name + ".patch");
        archive.add(createMigratorBundle("1.0.1"), "repository/io/fabric8/patch/migrator/1.0.1", ZipExporter.class);
        return archive;
    }

    @Test
    public void testMigratorPatch() throws Exception {
        load("migrator-patch-01");
        File file = new File(System.getProperty("karaf.base"), "installed.txt");
        assertFalse(file.exists());
        install("migrator-patch-01");

        // Lets wait for the marker file to get created.
        for (int i = 0; i < 2*10 ; i++) {
            if( file.exists() ) {
                break;
            }
            System.out.println("Waiting.. for file to get created.");
            Thread.sleep(500);
        }

        assertTrue(file.exists());
    }




}