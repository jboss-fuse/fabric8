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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.util.tracker.ServiceTracker;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Integration tests for patching files.
 */
@RunWith(Arquillian.class)
public class PatchFilesIntegrationTest extends AbstractPatchIntegrationTest {

    // Bundle-SymbolicName of the bundle we're patching
    private static final String ORIGINAL_FILE_CONTENTS = "Original file contents\n";
    private static final String PATCHED_FILE_CONTENTS = "Patched file contents\n";
    private static final String PATCHED_FILE = "etc/patched.txt";
    private static final String PATCHED2_FILE = "etc/patched2.txt";

    @Deployment
    public static JavaArchive createdeployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "test.jar");
        archive.addClass(ServiceLocator.class);
        archive.addClass(IOHelpers.class);
        archive.addPackage(ServiceTracker.class.getPackage());
        archive.addPackages(true, OSGiManifestBuilder.class.getPackage());
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(Bundle.class, Service.class, PatchManagement.class, Validatable.class);
                return builder.openStream();
            }
        });

        // add the patch zip files as resources
        archive.add(createPatchZipFile("file-01"), "/patches", ZipExporter.class);
        archive.add(createPatchZipFile("file-02"), "/patches", ZipExporter.class);

        return archive;
    }

    // Create a patch zip file
    private static JavaArchive createPatchZipFile(final String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name + ".zip");
        archive.addAsResource("patches/" + name + ".patch", name + ".patch");
        archive.add(new Asset() {
            public InputStream openStream() {
                return new ByteArrayInputStream(PATCHED_FILE_CONTENTS.getBytes());
            }
        }, PATCHED_FILE);
        archive.add(new Asset() {
            public InputStream openStream() {
                return new ByteArrayInputStream(PATCHED_FILE_CONTENTS.getBytes());
            }
        }, PATCHED2_FILE);

        return archive;
    }

    @Test
    public void testInstallAndRollbackPatch01() throws Exception {
        load("file-01");

        File base = new File(System.getProperty("karaf.base"));
        File patched = new File(base, PATCHED_FILE);

        IOHelpers.writeTo(patched, ORIGINAL_FILE_CONTENTS);

        install("file-01");
        assertEquals(PATCHED_FILE_CONTENTS, IOHelpers.readFully(patched));

        rollback("file-01");
        assertEquals(ORIGINAL_FILE_CONTENTS, IOHelpers.readFully(patched));
    }

    @Test
    public void testInstallAndRollbackPatch02AddFile() throws Exception {
        load("file-02");

        File base = new File(System.getProperty("karaf.base"));
        File patched = new File(base, PATCHED2_FILE);

        assertFalse(patched.exists());

        install("file-02");
        assertEquals(PATCHED_FILE_CONTENTS, IOHelpers.readFully(patched));

        rollback("file-02");
        assertFalse(patched.exists());
    }

}
