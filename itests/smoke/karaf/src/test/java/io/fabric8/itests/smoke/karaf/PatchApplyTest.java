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
package io.fabric8.itests.smoke.karaf;

import io.fabric8.api.FabricService;
import io.fabric8.api.Profile;
import io.fabric8.api.ProfileService;
import io.fabric8.api.gravia.ServiceLocator;
import io.fabric8.common.util.IOHelpers;
import io.fabric8.itests.support.CommandSupport;
import io.fabric8.itests.support.ServiceProxy;
import org.apache.felix.gogo.commands.Action;
import org.apache.felix.gogo.commands.basic.AbstractCommand;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.osgi.StartLevelAware;
import org.jboss.osgi.metadata.OSGiManifestBuilder;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.GenericArchive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Test case for the <code>patch:apply</code> command in Fabric
 */
@RunWith(Arquillian.class)
@Ignore("It tries to upload to https://repo.fusesource.com/nexus/content/groups/public/ ...")
public class PatchApplyTest {

    private static final String ORIGINAL_VERSION = "1.0.0";
    private static final String PATCHED_VERSION = "1.0.1";

    private static final String PATCHABLE_BSN = "patchable";

    @Deployment
    @StartLevelAware(autostart = true)
    public static Archive<?> deployment() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "patch-apply-test.jar");
        archive.addPackage(CommandSupport.class.getPackage());
        archive.addPackage(IOHelpers.class.getPackage());
        archive.setManifest(new Asset() {
            @Override
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName(archive.getName());
                builder.addBundleVersion("1.0.0");
                builder.addImportPackages(ServiceLocator.class, FabricService.class);
                builder.addImportPackages(AbstractCommand.class, Action.class);
                builder.addImportPackage("org.apache.felix.service.command;status=provisional");
                builder.addImportPackages(ConfigurationAdmin.class, ServiceTracker.class, Logger.class);
                return builder.openStream();
            }
        });

        // add the original 'patchable' bundle version and the patch file to the test bundle as an extra resource
        archive.add(createPatchableBundle(ORIGINAL_VERSION), "/bundles", ZipExporter.class);
        archive.add(createPatchZip(), "/patches", ZipExporter.class);

        return archive;
    }

    // Create the patch ZIP file with the 1.0.1 version of the 'patchable' bundle
    private static Archive createPatchZip() {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "patch-01.zip");
        archive.add(new Asset() {
            @Override
            public InputStream openStream() {
                StringBuilder builder = new StringBuilder(String.format("id = %s%n", "patch-01"));
                builder.append(String.format("bundle.count=1%n"));
                builder.append(String.format("bundle.0=%s%n", getMavenUrl(PATCHED_VERSION)));
                return new ByteArrayInputStream(builder.toString().getBytes());
            }
        }, "patch-01.patch");
        archive.add(createPatchableBundle(PATCHED_VERSION), "repository/" + getMavenRepoFolder(PATCHED_VERSION), ZipExporter.class);
        return archive;
    }

    // Create a 'patchable' bundle with the specified version
    private static JavaArchive createPatchableBundle(final String version) {
        final JavaArchive archive = ShrinkWrap.create(JavaArchive.class, "patchable-" + version + ".jar");
        archive.setManifest(new Asset() {
            public InputStream openStream() {
                OSGiManifestBuilder builder = OSGiManifestBuilder.newInstance();
                builder.addBundleManifestVersion(2);
                builder.addBundleSymbolicName("patchable");
                builder.addBundleVersion(version);
                return builder.openStream();
            }
        });
        return archive;
    }

    // Get the full Maven repository path and filename
    private static String getMavenRepoFile(String version) {
        return String.format("%s/%s-%s.jar", getMavenRepoFolder(version), PATCHABLE_BSN, version);
    }

    // Get the Maven repository path
    private static String getMavenRepoFolder(String version) {
        return String.format("io/fabric8/itests/patchable/%s", version);
    }

    // Get the mvn: URL
    private static String getMavenUrl(String version) {
        return String.format("mvn:io.fabric8.itests/patchable/%s", version);
    }

    @Test
    public void testApplyPatch() throws Exception {
        System.err.println(CommandSupport.executeCommand("fabric:create --force --clean -n --wait-for-provisioning"));
        BundleContext moduleContext = ServiceLocator.getSystemContext();
        ServiceProxy<FabricService> fabricProxy = ServiceProxy.createServiceProxy(moduleContext, FabricService.class);
        try {
            // copy the original version of the bundle into the system folder
            final File jar = new File("system/" + getMavenRepoFile(ORIGINAL_VERSION));
            jar.getParentFile().mkdirs();
            IOHelpers.writeTo(jar,
                    getClass().getClassLoader().getResourceAsStream("/bundles/patchable-1.0.0.jar"));

            // copy the patch zip file into a local working directory
            File patch = new File("data/patches/patch-01.zip");
            patch.getParentFile().mkdirs();
            IOHelpers.writeTo(patch, getClass().getClassLoader().getResourceAsStream("/patches/patch-01.zip"));

            FabricService fabricService = fabricProxy.getService();
            ProfileService profileService = fabricService.adapt(ProfileService.class);

            // set up the 'patchable' profile and make sure it contains the original bundle version
            CommandSupport.executeCommand("fabric:profile-create --parents default patchable");
            CommandSupport.executeCommand("fabric:profile-edit --bundle mvn:io.fabric8.itests/patchable/1.0.0 patchable");

            Profile profile = fabricService.getRequiredDefaultVersion().getRequiredProfile("patchable");
            Profile overlay = profileService.getOverlayProfile(profile);
            assertTrue(overlay.getBundles().contains(getMavenUrl(ORIGINAL_VERSION)));
            assertFalse(overlay.getOverrides().contains(getMavenUrl(PATCHED_VERSION)));

            // create a new version and apply the patch
            CommandSupport.executeCommand("fabric:version-create 1.1");
            Thread.sleep(2000);
            CommandSupport.executeCommand(String.format("fabric:patch-apply -u admin -p admin --version 1.1 %s", patch.toURI().toURL()));

            // ensure there's an override with the patched bundle version in the 'patchable' profile
            profile = profileService.getRequiredVersion("1.1").getProfile("patchable");
            overlay = profileService.getOverlayProfile(profile);
            assertTrue(overlay.getBundles().contains(getMavenUrl(ORIGINAL_VERSION)));
            assertTrue(overlay.getOverrides().contains(getMavenUrl(PATCHED_VERSION)));
        } finally {
            fabricProxy.close();
        }
    }
}
