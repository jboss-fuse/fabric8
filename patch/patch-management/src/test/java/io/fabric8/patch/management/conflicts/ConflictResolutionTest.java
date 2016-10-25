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
package io.fabric8.patch.management.conflicts;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class ConflictResolutionTest {

    private ConflictResolver cr;

    @Before
    public void init() throws IOException {
        cr = new ConflictResolver();
    }

    @Test
    public void resolveUserPropertiesConflict() throws IOException {
        String r = cr.getResolver("etc/users.properties").resolve(
                new File("src/test/resources/conflicts/users.patched.properties"),
                new File("src/test/resources/conflicts/users.base.properties"),
                new File("src/test/resources/conflicts/users.custom.properties")
        );
        Properties expected = new Properties();
        Properties resolved = new Properties();
        expected.load(new FileInputStream(new File("src/test/resources/conflicts/users.expected.properties")));
        resolved.load(new StringReader(r));

        assertThat(resolved.getProperty("admin"), equalTo(expected.getProperty("admin")));
        assertThat(resolved.getProperty("minion"), equalTo(expected.getProperty("minion")));
        assertThat(resolved.getProperty("other"), equalTo(expected.getProperty("other")));
        assertThat(resolved.stringPropertyNames().size(), equalTo(3));
    }

    @Test
    public void resolveBinSetenvConflict() throws IOException {
        String resolved = cr.getResolver("bin/setenv").resolve(
                new File("src/test/resources/conflicts/setenv.patched.txt"),
                new File("src/test/resources/conflicts/setenv.base.txt"),
                new File("src/test/resources/conflicts/setenv.custom.txt")
        );
        String expected = FileUtils.readFileToString(new File("src/test/resources/conflicts/setenv.custom.txt"));
        assertThat(resolved, equalTo(expected));
    }

    @Test
    public void resolveBinSetenvBatConflict() throws IOException {
        String resolved = cr.getResolver("bin/setenv.bat").resolve(
                new File("src/test/resources/conflicts/setenv.bat.patched.txt"),
                new File("src/test/resources/conflicts/setenv.bat.base.txt"),
                new File("src/test/resources/conflicts/setenv.bat.custom.txt")
        );
        String expected = FileUtils.readFileToString(new File("src/test/resources/conflicts/setenv.bat.custom.txt"));
        assertThat(resolved, equalTo(expected));
    }

    @Test
    public void resolveProperties() throws Exception {
        PropertiesFileResolver resolver = (PropertiesFileResolver) cr.getResolver("a.properties");

        String resolved1String = resolver.resolve(
                new File("src/test/resources/conflicts/example2/file.patched.properties"),
                new File("src/test/resources/conflicts/example2/file.base.properties"),
                new File("src/test/resources/conflicts/example2/file.custom.properties"),
                true, false
        );
        List<String> resolved1 = IOUtils.readLines(new StringReader(resolved1String));
        List<String> expected1 = FileUtils.readLines(new File("src/test/resources/conflicts/example2/file.expected-first.properties"));
        assertThat(resolved1, equalTo(expected1));

        String resolved2String = resolver.resolve(
                new File("src/test/resources/conflicts/example2/file.patched.properties"),
                new File("src/test/resources/conflicts/example2/file.base.properties"),
                new File("src/test/resources/conflicts/example2/file.custom.properties"),
                false, false
        );
        List<String> resolved2 = IOUtils.readLines(new StringReader(resolved2String));
        List<String> expected2 = FileUtils.readLines(new File("src/test/resources/conflicts/example2/file.expected-second.properties"));
        assertThat(resolved2, equalTo(expected2));

        String resolved3String = resolver.resolve(
                new File("src/test/resources/conflicts/example2/file.custom.properties"),
                new File("src/test/resources/conflicts/example2/file.base.properties"),
                new File("src/test/resources/conflicts/example2/file.patched.properties"),
                true, false
        );
        List<String> resolved3 = IOUtils.readLines(new StringReader(resolved3String));
        List<String> expected3 = FileUtils.readLines(new File("src/test/resources/conflicts/example2/file.expected2-first.properties"));
        assertThat(resolved3, equalTo(expected3));

        String resolved4String = resolver.resolve(
                new File("src/test/resources/conflicts/example2/file.custom.properties"),
                new File("src/test/resources/conflicts/example2/file.base.properties"),
                new File("src/test/resources/conflicts/example2/file.patched.properties"),
                false, false
        );
        List<String> resolved4 = IOUtils.readLines(new StringReader(resolved4String));
        List<String> expected4 = FileUtils.readLines(new File("src/test/resources/conflicts/example2/file.expected2-second.properties"));
        assertThat(resolved4, equalTo(expected4));
    }

    /**
     * Tests for resolving conflicts inside <code>etc/org.apache.karaf.features.cfg</code>
     * The rule here is: take patch version and add custom features and repositories at the end of <code>featuresBoot</code>
     * and <code>featuresRepositories</code> properties.
     * @throws Exception
     */
    @Test
    public void resolveFeatureProperties() throws Exception {
        PropertiesFileResolver resolver = (PropertiesFileResolver) cr.getResolver("etc/org.apache.karaf.features.cfg");

        // installation
        String resolved1String = resolver.resolve(
                new File("src/test/resources/conflicts/example3/org.apache.karaf.features.patched.cfg"),
                new File("src/test/resources/conflicts/example3/org.apache.karaf.features.base.cfg"),
                new File("src/test/resources/conflicts/example3/org.apache.karaf.features.after-create.cfg"),
                false, false
        );
        List<String> resolved1 = IOUtils.readLines(new StringReader(resolved1String));
        List<String> expected1 = FileUtils.readLines(new File("src/test/resources/conflicts/example3/org.apache.karaf.features.expected.cfg"));
        assertThat(resolved1, equalTo(expected1));

        // rollback
        resolved1String = resolver.resolve(
                new File("src/test/resources/conflicts/example3/org.apache.karaf.features.after-create.cfg"),
                new File("src/test/resources/conflicts/example3/org.apache.karaf.features.patched.cfg"),
                new File("src/test/resources/conflicts/example3/org.apache.karaf.features.base.cfg"),
                true, true
        );
        resolved1 = IOUtils.readLines(new StringReader(resolved1String));
        expected1 = FileUtils.readLines(new File("src/test/resources/conflicts/example3/org.apache.karaf.features.expected-after-rollback.cfg"));
        assertThat(resolved1, equalTo(expected1));
    }

    /**
     * Tests for resolving conflicts inside <code>io.fabric8.agent.properties</code> and <code>attribute.parents</code>
     * property.
     * The rule here is: take patch version and add custom parents at the end of <code>attribute.parents</code>.
     * @throws Exception
     */
    @Test
    public void resolveAgentProperties() throws Exception {
        PropertiesFileResolver resolver = (PropertiesFileResolver) cr.getResolver("io.fabric8.agent.properties");

        // installation
        String resolved1String = resolver.resolve(
                new File("src/test/resources/conflicts/example4/io.fabric8.agent.patched.properties"),
                new File("src/test/resources/conflicts/example4/io.fabric8.agent.base.properties"),
                new File("src/test/resources/conflicts/example4/io.fabric8.agent.edited.properties"),
                true, false
        );
        List<String> resolved1 = IOUtils.readLines(new StringReader(resolved1String));
        List<String> expected1 = FileUtils.readLines(new File("src/test/resources/conflicts/example4/io.fabric8.agent.expected.properties"));
        assertThat(resolved1, equalTo(expected1));

        // rollback
        resolved1String = resolver.resolve(
                new File("src/test/resources/conflicts/example4/io.fabric8.agent.edited.properties"),
                new File("src/test/resources/conflicts/example4/io.fabric8.agent.patched.properties"),
                new File("src/test/resources/conflicts/example4/io.fabric8.agent.base.properties"),
                false, true
        );
        resolved1 = IOUtils.readLines(new StringReader(resolved1String));
        expected1 = FileUtils.readLines(new File("src/test/resources/conflicts/example4/io.fabric8.agent.expected-after-rollback.properties"));
        assertThat(resolved1, equalTo(expected1));
    }

}
