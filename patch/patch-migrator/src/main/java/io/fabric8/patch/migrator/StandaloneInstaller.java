/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fabric8.patch.migrator;

import java.util.ArrayList;
import java.util.Properties;
import io.fabric8.api.scr.ValidatingReference;
import org.apache.felix.scr.annotations.*;
import org.apache.karaf.features.FeaturesService;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.Namespace;
import org.jdom.input.SAXBuilder;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.packageadmin.PackageAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.util.regex.Pattern;

import static io.fabric8.patch.migrator.Support.*;
import static io.fabric8.patch.migrator.Support.pump;
import static io.fabric8.patch.migrator.Support.writeText;

@Component(
    name = "io.fabric8.patch.installer.StandaloneInstaller",
    label = "Fuse Patch Installer",
    policy = ConfigurationPolicy.IGNORE,
    immediate = true
)
@Service(StandaloneInstaller.class)
public class StandaloneInstaller {

    public static final Logger LOG = LoggerFactory.getLogger(StandaloneInstaller.class);

    @Reference(referenceInterface = FeaturesService.class)
    private final ValidatingReference<FeaturesService> featuresService = new ValidatingReference<>();
    void bindFeaturesService(FeaturesService service) {
        this.featuresService.bind(service);
    }
    void unbindFeaturesService(FeaturesService service) {
        this.featuresService.unbind(service);
    }

    @Reference(referenceInterface = PackageAdmin.class)
    private final ValidatingReference<PackageAdmin> packageAdmin = new ValidatingReference<>();
    void bindPackageAdmin(PackageAdmin service) {
        this.packageAdmin.bind(service);
    }
    void unbindPackageAdmin(PackageAdmin service) {
        this.packageAdmin.unbind(service);
    }


    private BundleContext bundleContext;
    private File karafBase;

    Properties loadStatus() throws IOException {
        File file = new File(karafBase, "patches/installer-status.properties");
        Properties rc = new Properties();
        if( file.exists() ) {
            try ( FileInputStream is = new FileInputStream(file) ) {
                rc.load(is);
            }
        }
        return rc;
    }

    void storeStatus(Properties status) throws IOException {
        File file = new File(karafBase, "patches/installer-status.properties");
        try ( FileOutputStream os = new FileOutputStream(file) ) {
            status.store(os, null);
        }
    }


    @Activate
    void activate(ComponentContext context) throws Exception {
        bundleContext = context.getBundleContext();
        karafBase = new File(System.getProperty("karaf.base")).getCanonicalFile();

        // Use a status file to make sure we only apply updates once.
        // We could incrementally apply more changes later too by looking at the level.
        Properties status = loadStatus();
        int migrationLevel = Integer.parseInt(status.getProperty("level", "0"));


        ArrayList<Runnable> deferedTasks = new ArrayList<>();

        switch( migrationLevel ) {
            case 0:
                installNewFeaturesCore();
                updateAriesBlueprintCompatibility();
                updateJREProperties();
                updateKarafJar();

                deferedTasks.add(new Runnable() {
                    @Override
                    public void run() {
                        refreshAriesBlueprint();
                        LOG.warn("Karaf container settings patched.  Please restart JVM.");
                        System.out.println("\n");
                        System.out.println("======================================================");
                        System.out.println("Karaf container settings patched.  Please restart JVM.");
                        System.out.println("======================================================");
                        System.out.println("\n");
                    }
                });

            case 1:
                // In the future we could add additional updates here.
            default:
        }

        status.put("level", "1");
        storeStatus(status);

        // These tasks seem to cause this bundle to re-deploy.. so lets do AFTER
        // all the other work.
        for (Runnable postTask : deferedTasks) {
            postTask.run();
        }

   }

    private void refreshAriesBlueprint() {
        try {
            FeaturesService featureService = this.featuresService.get();
            if( featureService.isInstalled(featureService.getFeature("aries-blueprint")) ) {

                LOG.info("Refreshing feature: aries-blueprint");

                // Uninstal, refresh and the install aries-blueprint
                featureService.uninstallFeature("aries-blueprint");

                URI uri = new URI("mvn:org.apache.karaf.assemblies.features/standard/2.4.0.redhat-620133/xml/features");
                try {
                    featureService.removeRepository(uri);
                    featureService.addRepository(uri);
                } catch (Exception e) {
                    featureService.restoreRepository(uri);
                }

                featureService.installFeature("aries-blueprint");

                // Find and refresh "org.apache.aries.blueprint.core"
                for (Bundle bundle : bundleContext.getBundles()) {
                    if ( "org.apache.aries.blueprint.core".equals(bundle.getSymbolicName()) ) {
                        final Bundle needsRefresh = bundle;
                        PackageAdmin pa = this.packageAdmin.get();
                        pa.refreshPackages(new Bundle[]{needsRefresh});
                    }
                }

                LOG.info("Refreshed feature: aries-blueprint");
            }
        } catch (Exception e) {
            LOG.error("Failed refreshing feature: aries-blueprint" , e);
        }
    }


    public int updateAriesBlueprintCompatibility() throws Exception {
        Namespace NS = Namespace.getNamespace("http://karaf.apache.org/xmlns/features/v1.2.0");

        File xmlFile = new File(karafBase, "system/org/apache/karaf/assemblies/features/standard/2.4.0.redhat-620133/standard-2.4.0.redhat-620133-features.xml");
        if( !xmlFile.exists() ) {
            LOG.error("File not found: " + xmlFile);
            return 0;
        }

        SAXBuilder builder = new SAXBuilder();
        Document doc = (Document) builder.build(xmlFile);
        Element rootNode = doc.getRootElement();

        for (Element feature : findChildrenWith(rootNode, "feature", "name", "aries-blueprint", NS)) {

            // Is it not installed?
            String compatBundle = "mvn:org.apache.aries.blueprint/org.apache.aries.blueprint.core.compatibility/1.0.0";
            if( findChildrenWithText(feature, "bundle", compatBundle, NS).isEmpty() )  {

                Element bundle = new Element("bundle", NS).setText(compatBundle);
                bundle.setAttribute("start-level", "20");
                feature.addContent(bundle);

                XMLOutputter xmlOutput = new XMLOutputter();
                xmlOutput.setFormat(Format.getRawFormat());
                xmlOutput.output(doc, new FileWriter(xmlFile));

                LOG.info("Updated: " + xmlFile);
                return 1;
            }
        }
        return 0;
    }

    private int updateKarafJar() throws IOException {

        InputStream is = getClass().getResourceAsStream("karaf.jar");
        if( is == null ) {
            LOG.error("karaf.jar resource not found!");
            return 0;
        }

        File karafJar = new File(karafBase, "lib/karaf.jar");
        try ( FileOutputStream os = new FileOutputStream(karafJar)  ) {
            pump(is, os);
        }
        is.close();

        LOG.info("Updated: " + karafJar);
        return 1;
    }


    private int updateJREProperties() throws IOException {
        File jreFile = new File(karafBase, "etc/jre.properties");

        String original = readText(jreFile);

        String updated = original.replaceAll(Pattern.quote("javax.annotation;version=\"1.1\""), "javax.annotation;version=\"1.0\"");
        updated = updated.replaceAll(Pattern.quote("javax.annotation.processing;version=\"1.1\""), "javax.annotation.processing;version=\"1.0\"");

        if( original.equals(updated) ) {
            return 0;
        }

        writeText(jreFile, updated);
        LOG.info("Updated: " + jreFile);
        return 1;
    }

    private int installNewFeaturesCore() throws IOException {

        File dir = new File(karafBase, "system/org/apache/karaf/features/org.apache.karaf.features.core/2.4.0.redhat-620143");
        dir.mkdirs();


        InputStream is = getClass().getResourceAsStream("org.apache.karaf.features.core-2.4.0.redhat-620143.jar");
        if( is == null ) {
            LOG.error("org.apache.karaf.features.core-2.4.0.redhat-620143.jar resource not found!");
            return 0;
        }

        File target = new File(dir, "org.apache.karaf.features.core-2.4.0.redhat-620143.jar");
        try ( FileOutputStream os = new FileOutputStream(target)  ) {
            pump(is, os);
        }
        is.close();

        LOG.info("Installed: " + target);
        return 1;
    }

}
