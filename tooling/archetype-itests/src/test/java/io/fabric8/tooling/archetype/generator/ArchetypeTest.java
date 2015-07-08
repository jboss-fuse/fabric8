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
package io.fabric8.tooling.archetype.generator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.fabric8.maven.MavenResolver;
import io.fabric8.maven.MavenResolvers;
import org.apache.commons.io.IOUtils;
import org.apache.maven.shared.invoker.*;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.AfterClass;
import org.junit.Ignore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ArchetypeTest {

    private boolean verbose = true;

    //    private String groupId = "myGroup";
//    private String artifactId = "myArtifact";
    private String packageName = "org.acme.mystuff";

    // lets get the versions from the pom.xml via a system property
    private String camelVersion = System.getProperty("camel-version", "2.12.0.redhat-610379");
    private String projectVersion = System.getProperty("project.version", "1.2.0-SNAPSHOT");
    private File basedir = new File(System.getProperty("basedir", "."));

    private static List<String> outDirs = new ArrayList<String>();

    private static Set<String> EXCLUDED_ARCHETYPES = new HashSet<String>(Arrays.asList(new String[] {
            "karaf-camel-dozer-wiki-archetype",
            "karaf-camel-drools-archetype",
            "karaf-camel-webservice-archetype",
            "war-camel-servlet-archetype",
	    "war-rest-archetype",
    }));

    @Test
    public void testGenerateQuickstartArchetypes() throws Exception {
        String[] dirs = new File(basedir, "../archetypes").list(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return new File(dir, name).isDirectory() && !EXCLUDED_ARCHETYPES.contains(name);
            }
        });
        for (String dir : dirs) {
            assertArchetypeCreated(dir, "io.fabric8.archetypes", projectVersion);
        }
    }

    @Test
    @Ignore
    public void testGenerateActiveMQArchetype() throws Exception {
        assertArchetypeCreated("camel-archetype-activemq");
    }

    @Test
    @Ignore
    public void testGenerateSpringArchetype() throws Exception {
        assertArchetypeCreated("camel-archetype-spring");
    }

    @Test
    @Ignore
    public void testGenerateJavaArchetype() throws Exception {
        assertArchetypeCreated("camel-archetype-java");
    }

    @Test
    @Ignore
    public void testGenerateComponentArchetype() throws Exception {
        assertArchetypeCreated("camel-archetype-component", "org.apache.camel.archetypes", camelVersion);
    }

    @Test
    @Ignore
    public void testGenerateDataformatArchetype() throws Exception {
        assertArchetypeCreated("camel-archetype-dataformat");
    }

    @Test
    @Ignore
    public void testGenerateDroolsArchetype() throws Exception {
        String artifactId = "karaf-camel-drools-archetype";
        assertArchetypeCreated(artifactId, "io.fabric8", projectVersion,
            new File(basedir, "../archetypes/" + artifactId + "/target/" + artifactId + "-" + projectVersion + ".jar"));
    }

    protected void assertArchetypeCreated(String artifactId) throws Exception {
        assertArchetypeCreated(artifactId, "org.apache.camel.archetypes");
    }

    protected void assertArchetypeCreated(String artifactId, String groupId) throws Exception {
        assertArchetypeCreated(artifactId, groupId, camelVersion);
    }

    private void assertArchetypeCreated(String artifactId, String groupId, String version) throws Exception {
        Hashtable<String, String> props = new Hashtable<>();
        props.put("offline", "true");
        MavenResolver resolver = MavenResolvers.createMavenResolver(props, null);
        File archetypejar = resolver.resolveFile(new DefaultArtifact(groupId, artifactId, null, null, version));
        assertTrue("archetype jar does not exist", archetypejar.exists());

        assertArchetypeCreated(artifactId, groupId, version, archetypejar);
    }

    private void assertArchetypeCreated(String artifactId, String groupId, String version, File archetypejar) throws Exception {
        File outDir = new File(basedir, "target/" + artifactId + "-output");

        System.out.println("Creating Archetype " + groupId + ":" + artifactId + ":" + version);
        Map<String, String> properties = new ArchetypeHelper(archetypejar, outDir, groupId, artifactId, version, null, null).parseProperties();
        System.out.println("Has preferred properties: " + properties);

        ArchetypeHelper helper = new ArchetypeHelper(archetypejar, outDir, groupId, artifactId, version, null, null);
        helper.setPackageName(packageName);

        // lets override some properties
        HashMap<String, String> overrideProperties = new HashMap<String, String>();
        // for camel-archetype-component
        overrideProperties.put("scheme", "mycomponent");
        helper.setOverrideProperties(overrideProperties);

        // this is where the magic happens
        helper.execute();

        // expected pom file
        File pom = new File(outDir, "pom.xml");
        assertFileExists(pom);

        String pomText = IOUtils.toString(new FileReader(pom));
        String badText = "${camel-";
        if (pomText.contains(badText)) {
            if (verbose) {
                System.out.println(pomText);
            }
            fail("" + pom + " contains " + badText);
        }

        outDirs.add(outDir.getPath());
    }

    @AfterClass
    public static void afterAll() throws Exception {
        // now let invoke the projects

        StringWriter sw = new StringWriter();
        Set<String> modules = new HashSet<String>();
        for (final String outDir : outDirs) {
            String module = new File(outDir).getName();
            if (modules.add(module)) {
                sw.append(String.format("        <module>%s</module>\n", module));
            }
        }
        sw.close();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        IOUtils.copy(ArchetypeTest.class.getResourceAsStream("/archetypes-test-pom.xml"), baos);
        String pom = new String(baos.toByteArray()).replace("        <!-- to be replaced -->", sw.toString());
        FileWriter modulePom = new FileWriter("target/archetypes-test-pom.xml");
        IOUtils.copy(new StringReader(pom), modulePom);
        modulePom.close();

        final String outDir = new File("target").getCanonicalPath();
        // thread locals are evil (I'm talking to you - org.codehaus.plexus.DefaultPlexusContainer#lookupRealm!)
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("Invoking projects in " + outDir);
                InvocationResult mavenInvocationResult = null;
                InvocationRequest mavenInvocationRequest = new DefaultInvocationRequest();
                mavenInvocationRequest.setPomFile(new File(outDir + "/archetypes-test-pom.xml"));
                String[] goals = {"clean", "install"};
                mavenInvocationRequest.setGoals(Arrays.asList(goals));

                Invoker mavenInvoker = new DefaultInvoker();

                try {
                    mavenInvocationResult =  mavenInvoker.execute(mavenInvocationRequest);
                } catch (MavenInvocationException e) {
                    e.printStackTrace();
                    fail(e.getMessage());
                }
                System.out.println("result: " + mavenInvocationResult.getExitCode());
                assertEquals("Build of project " + outDir + " failed. Result = " + mavenInvocationResult.getExitCode(), 0, mavenInvocationResult.getExitCode());
            }
        });
        t.start();
        t.join();
    }

    protected void assertFileExists(File file) {
        assertTrue("file should exist: " + file, file.exists());
    }

}
