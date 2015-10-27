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
import io.fabric8.itests.support.CommandSupport;
import io.fabric8.patch.Service;
import org.apache.felix.gogo.commands.Action;
import org.apache.felix.gogo.commands.basic.AbstractCommand;
import org.apache.felix.service.command.Function;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;

import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Integration tests for patching bundles.
 */
@RunWith(Arquillian.class)
public class PatchBundlesCommandIntegrationTest extends AbstractPatchCommandIntegrationTest {

    // Bundle-SymbolicName of the bundle we're patching
    private static final String PATCHABLE_BSN = "patchable";

    @Deployment
    public static JavaArchive createdeployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "test.jar");
        archive.addClass(ServiceLocator.class);
        archive.addClass(IOHelpers.class);
        archive.addPackage(ServiceTracker.class.getPackage());
        archive.addPackages(true, OSGiManifestBuilder.class.getPackage());
        archive.addPackage(CommandSupport.class.getPackage());
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleManifestVersion(2);
                builder.addImportPackages(Bundle.class, Logger.class);
                builder.addImportPackages(AbstractCommand.class, Action.class, Function.class, Validatable.class);
                builder.addImportPackage("org.apache.felix.service.command;status=provisional");
                return builder.openStream();
            }
        });

        // add the original bundle as well as the patch zip files as resources
        archive.add(createPatchableBundle("1.0.0"), "/bundles", ZipExporter.class);
        archive.add(createPatchZipFile("patch-01"), "/patches", ZipExporter.class);
        archive.add(createPatchZipFile("patch-02"), "/patches", ZipExporter.class);
        archive.add(createPatchZipFile("patch-02-without-range"), "/patches", ZipExporter.class);

        return archive;
    }

    // Create a 'patchable' bundle with the specified version
    private static JavaArchive createPatchableBundle(final String version) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "patchable-" + version + ".jar");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(PATCHABLE_BSN);
                builder.addBundleVersion(version);
                return builder.openStream();
            }
        });
        return archive;
    }

    // Create a patch zip file
    private static JavaArchive createPatchZipFile(final String name) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, name + ".zip");
        archive.addAsResource("patches/" + name + ".patch", name + ".patch");
        archive.add(createPatchableBundle("1.0.1"), "repository/io/fabric8/patch/patchable/1.0.1", ZipExporter.class);
        archive.add(createPatchableBundle("1.1.2"), "repository/io/fabric8/patch/patchable/1.1.2", ZipExporter.class);
        return archive;
    }

    @Test
    public void testInstallAndRollbackPatch01() throws Exception {
        load("patch-01");

        assertEquals("1.0.0", getPatchableBundle().getVersion().toString());

        install("patch-01");
        assertEquals("1.0.1", getPatchableBundle().getVersion().toString());

        rollback("patch-01");
        assertEquals("1.0.0", getPatchableBundle().getVersion().toString());
    }

    @Test
    public void testInstallAndRollbackPatch02() throws Exception {
        load("patch-02");

        assertEquals("1.0.0", getPatchableBundle().getVersion().toString());

        install("patch-02");
        assertEquals("1.1.2", getPatchableBundle().getVersion().toString());

        rollback("patch-02");
        assertEquals("1.0.0", getPatchableBundle().getVersion().toString());
    }

    @Test
    public void testInstallAndRollbackPatch02WithoutRange() throws Exception {
        load("patch-02-without-range");

        assertEquals("1.0.0", getPatchableBundle().getVersion().toString());

        install("patch-02-without-range");
        assertEquals("1.0.0", getPatchableBundle().getVersion().toString());

        rollback("patch-02-without-range");
        assertEquals("1.0.0", getPatchableBundle().getVersion().toString());
    }

    @Test
    public void testInstallAndRollbackPatch01And02() throws Exception {
        load("patch-01");
        load("patch-02");

        assertEquals("1.0.0", getPatchableBundle().getVersion().toString());

        install("patch-01");
        assertEquals("1.0.1", getPatchableBundle().getVersion().toString());

        install("patch-02");
        assertEquals("1.1.2", getPatchableBundle().getVersion().toString());

        rollback("patch-02");
        assertEquals("1.0.1", getPatchableBundle().getVersion().toString());

        rollback("patch-01");
        assertEquals("1.0.0", getPatchableBundle().getVersion().toString());
    }

    // Find the bundle we're patching
    private Bundle getPatchableBundle() {
        for (Bundle bundle : context.getBundles()) {
            if (PATCHABLE_BSN.equals(bundle.getSymbolicName())) {
                return bundle;
            }
        }
        fail("Bundle 'patchable' was not installed!");
        return null;
    }

    // Reinstall version 1.0.0 of the 'patchable' bundle and return the bundle id
    @Before
    public void installPatchableBundle() throws Exception {
        // let's uninstall any previous bundle versions
        for (Bundle bundle : context.getBundles()) {
            if ("patchable".equals(bundle.getSymbolicName())) {
                bundle.uninstall();
            }
        }

        // now, copy the version 1.0.0 bundle into the system folder...
        File base = new File(System.getProperty("karaf.base"));
        File system = new File(base, "system");

        File target = new File(system, "io/fabric8/patch/patchable/1.0.0/patchable-1.0.0.jar");
        target.getParentFile().mkdirs();

        IOHelpers.writeTo(target, getClass().getResourceAsStream("/bundles/patchable-1.0.0.jar"));

        // ... and install the bundle
        context.installBundle("mvn:io.fabric8.patch/patchable/1.0.0");
    }
}