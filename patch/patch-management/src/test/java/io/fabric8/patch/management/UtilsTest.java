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
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.felix.utils.version.VersionCleaner;
import org.junit.Test;
import org.osgi.framework.BundleContext;

import static io.fabric8.patch.management.Utils.*;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class UtilsTest {

    @Test
    public void fromNumericUnixPermissions() {
        assertThat(getPermissionsFromUnixMode(new File("target"), 0775), equalTo(PosixFilePermissions.fromString("rwxrwxr-x")));
        assertThat(getPermissionsFromUnixMode(new File("target"), 0641), equalTo(PosixFilePermissions.fromString("rw-r----x")));
        assertThat(getPermissionsFromUnixMode(new File("target"), 00), equalTo(PosixFilePermissions.fromString("rwxrwxr-x")));
        assertThat(getPermissionsFromUnixMode(new File("target/test-classes/logback-test.xml"), 00), equalTo(PosixFilePermissions.fromString("rw-rw-r--")));
    }

    @Test
    public void toNumericUnixPermissions() {
        assertThat(getUnixModeFromPermissions(new File("target"), PosixFilePermissions.fromString("rwxrwxr-x")), equalTo(0775));
        assertThat(getUnixModeFromPermissions(new File("target"), PosixFilePermissions.fromString("rw-rw-r--")), equalTo(0664));
        assertThat(getUnixModeFromPermissions(new File("target"), PosixFilePermissions.fromString("r--------")), equalTo(0400));
    }

    @Test
    public void relativePaths() {
        assertThat(relative(new File("target/karaf/patches"), new File("target/karaf/other")), equalTo(".." + File.separatorChar + "other"));
        assertThat(relative(new File("target/karaf/patches"), new File("target/karaf/patches")), equalTo(""));
        assertThat(relative(new File("target/karaf/patches"), new File("target/karaf/patches/x")), equalTo("x"));
    }

    @Test
    public void pathToMvnUris() {
        assertThat(pathToMvnurl("a/b/c"), nullValue());
        assertThat(pathToMvnurl("a/b/c/d"), nullValue());
        assertThat(pathToMvnurl("a/b/c/d/e"), nullValue());

        assertThat(pathToMvnurl("a/b/c/b-c.jar"), equalTo("mvn:a/b/c"));
        assertThat(pathToMvnurl("a/b/b/c/b-c.jar"), equalTo("mvn:a.b/b/c"));
        assertThat(pathToMvnurl("a/b/b/c/b-c-d.jar"), equalTo("mvn:a.b/b/c/jar/d"));
        assertThat(pathToMvnurl("a/b/c/b-c.war"), equalTo("mvn:a/b/c/war"));

        assertThat(pathToMvnurl("a/b/c/b-c-x.war"), equalTo("mvn:a/b/c/war/x"));
        assertThat(pathToMvnurl("a/b/c/b-c-x-y.xml"), equalTo("mvn:a/b/c/xml/x-y"));
    }

    @Test
    public void mvnUrisToPaths() {
        assertThat(mvnurlToPath("mvn:a/b/c"), equalTo("a/b/c/b-c.jar"));
        assertThat(mvnurlToPath("mvn:a.b/b/c"), equalTo("a/b/b/c/b-c.jar"));
        assertThat(mvnurlToPath("mvn:a.b/b/c/jar/d"), equalTo("a/b/b/c/b-c-d.jar"));
        assertThat(mvnurlToPath("mvn:a/b/c/war"), equalTo("a/b/c/b-c.war"));
        assertThat(mvnurlToPath("mvn:a/b/c/war/x"), equalTo("a/b/c/b-c-x.war"));
        assertThat(mvnurlToPath("mvn:a/b/c/xml/x-y"), equalTo("a/b/c/b-c-x-y.xml"));
    }

    @Test
    public void testSymbolicNameStrip() {
        assertEquals("my.bundle", stripSymbolicName("my.bundle"));
        assertEquals("my.bundle", stripSymbolicName("my.bundle;singleton:=true"));
        assertEquals("my.bundle", stripSymbolicName("my.bundle;blueprint.graceperiod:=false;"));
        assertEquals("my.bundle", stripSymbolicName("my.bundle;blueprint.graceperiod:=false; blueprint.timeout=10000;"));
    }

    @Test
    public void canonicalVersionsFelix() {
        assertEquals("1.1.1.1", VersionCleaner.clean("1.1.1.1"));
        assertEquals("1.1.1", VersionCleaner.clean("1.1.1"));
        assertEquals("1.1.0", VersionCleaner.clean("1.1"));
        assertEquals("1.0.0", VersionCleaner.clean("1"));
        assertEquals("0.0.0", VersionCleaner.clean(""));
        assertEquals("0.0.0", VersionCleaner.clean(null));

        assertEquals("1.1.1.redhat-1", VersionCleaner.clean("1.1.1.redhat-1"));
        assertEquals("1.1.0.redhat-1", VersionCleaner.clean("1.1.redhat-1"));
        assertEquals("1.0.0.redhat-1", VersionCleaner.clean("1.redhat-1"));

        assertEquals("1.1.1.1_redhat-1", VersionCleaner.clean("1.1.1.1.redhat-1"));
        assertEquals("1.1.1.1_1_redhat-1", VersionCleaner.clean("1.1.1.1.1.redhat-1"));

        assertEquals("3.1.2.2", VersionCleaner.clean("3.1.2_2"));
        assertEquals("3.1.2.2", VersionCleaner.clean("3.1.2-2"));
        assertEquals("3.1.2.2", VersionCleaner.clean("3.1.2+2"));
        assertEquals("3.1.2.a+2", VersionCleaner.clean("3.1.2.a+2"));
    }

    @Test
    public void canonicalVersions() {
        assertEquals("1.1.1.1", Utils.getOsgiVersion("1.1.1.1").toString());
        assertEquals("1.1.1", Utils.getOsgiVersion("1.1.1").toString());
        assertEquals("1.1.0", Utils.getOsgiVersion("1.1").toString());
        assertEquals("1.0.0", Utils.getOsgiVersion("1").toString());
        assertEquals("0.0.0", Utils.getOsgiVersion("").toString());
        assertEquals("0.0.0", Utils.getOsgiVersion(null).toString());

        assertEquals("3.1.2.2", Utils.getOsgiVersion("3.1.2_2").toString());

        assertEquals("1.1.1.redhat-1", Utils.getOsgiVersion("1.1.1.redhat-1").toString());
        assertEquals("1.1.0.redhat-1", Utils.getOsgiVersion("1.1.redhat-1").toString());
        assertEquals("1.0.0.redhat-1", Utils.getOsgiVersion("1.redhat-1").toString());

        assertEquals("1.1.1.1_1_redhat-1", Utils.getOsgiVersion("1.1.1.1.1.redhat-1").toString());
        assertEquals("1.1.1.1_redhat-1", Utils.getOsgiVersion("1.1.1.1.redhat-1").toString());
    }

    @Test
    public void productBaselineLocations() throws IOException {
        BundleContext bc = mock(BundleContext.class);
        when(bc.getProperty("karaf.default.repository")).thenReturn("sys");
        File karafHome = new File("target/karaf-" + new Date().getTime());
        FileUtils.deleteDirectory(karafHome);

        FileUtils.write(new File(karafHome, "sys/a/b/1.2/b-1.2-baseline.zip"), "");
        when(bc.getProperty("fuse.patch.product")).thenReturn("a:b");
        assertThat(Utils.getBaselineLocationForProduct(karafHome, bc, "1.2"), equalTo("a/b/1.2/b-1.2-baseline.zip"));
        FileUtils.deleteQuietly(new File(karafHome, "sys/a/b/1.2/b-1.2-baseline.zip"));
        assertNull(Utils.getBaselineLocationForProduct(karafHome, bc, "1.2"));

        when(bc.getProperty("fuse.patch.product")).thenReturn(null);
        assertNull(Utils.getBaselineLocationForProduct(karafHome, bc, "1.2"));

        when(bc.getProperty("fuse.patch.product")).thenReturn("a");
        assertNull(Utils.getBaselineLocationForProduct(karafHome, bc, "1.2"));
    }

    @Test
    public void locationUpdates() throws IOException {
        List<BundleUpdate> updates = new LinkedList<>();

        updates.add(BundleUpdate.from("mvn:g/a/1.0").to("mvn:g/a/1.1"));
        updates.add(BundleUpdate.from("mvn:g/a2/1.0/jar").to("mvn:g/a2/1.1/jar"));
        updates.add(BundleUpdate.from("mvn:g/a3/1.0/jar/signed-secrets").to("mvn:g/a3/1.1/jar/signed-secrets"));
        updates.add(BundleUpdate.from("mvn:g2/a2/1.0/war").to("mvn:g2/a2/1.1/war"));
        updates.add(BundleUpdate.from("mvn:g2.g3/a2.a3/1.0/war").to("mvn:g2.g3/a2.a3/1.1/war"));
        updates.add(BundleUpdate.from("mvn:g2.g3/a2.a3/1.0/xml/sources").to("mvn:g2.g3/a2.a3/1.1/xml/sources"));
        updates.add(BundleUpdate.from("file:/fuse-6/system/org/ops4j/pax/url/pax-url-aether/2.4.0/pax-url-aether-2.4.0.jar")
                .to("file:/fuse-7.3/system/org/ops4j/pax/url/pax-url-aether/2.4.2/pax-url-aether-2.4.2.jar"));

        Map<String, String> map = Utils.collectLocationUpdates(updates);
        assertThat(map.get("g/a/1.0/a-1.0.jar"), equalTo("g/a/1.1/a-1.1.jar"));
        assertThat(map.get("g/a2/1.0/a2-1.0.jar"), equalTo("g/a2/1.1/a2-1.1.jar"));
        assertThat(map.get("g/a3/1.0/a3-1.0-signed-secrets.jar"), equalTo("g/a3/1.1/a3-1.1-signed-secrets.jar"));
        assertThat(map.get("g2/g3/a2.a3/1.0/a2.a3-1.0.war"), equalTo("g2/g3/a2.a3/1.1/a2.a3-1.1.war"));
        assertThat(map.get("g2/g3/a2.a3/1.0/a2.a3-1.0-sources.xml"), equalTo("g2/g3/a2.a3/1.1/a2.a3-1.1-sources.xml"));
        assertThat(map.get("org/ops4j/pax/url/pax-url-aether/2.4.0/pax-url-aether-2.4.0.jar"),
                equalTo("org/ops4j/pax/url/pax-url-aether/2.4.2/pax-url-aether-2.4.2.jar"));
    }

    @Test
    public void updateKarafPackages() throws IOException {
        File configProperties = new File("target/test-config.properties");
        StringWriter sw1 = new StringWriter();
        StringWriter sw2 = new StringWriter();

        sw1.write(" p.q;version=\"10.0\", \\\n");
        sw1.write(" p.q.r;version=\"10.0\", \\\n");
        sw1.write(" p.q.m;version=\"10.0\", \\\n");
        sw1.write(" x;version=\"10.0\", \\\n");
        sw1.write(" x;version=10.0, \\\n");
        sw1.write(" p.q = 3\n");
        sw1.write(" x = 3\n");

        sw2.write(" p.q;version=\"11.0\", \\\n");
        sw2.write(" p.q.r;version=\"11.0\", \\\n");
        sw2.write(" p.q.m;version=\"10.0\", \\\n");
        sw2.write(" x;version=\"12.0\", \\\n");
        sw2.write(" x;version=10.0, \\\n");
        sw2.write(" p.q = 3\n");
        sw2.write(" x = 3\n");

        FileUtils.write(configProperties, sw1.toString());

        Utils.updateKarafPackageVersion(configProperties, "11.0", "p.q", "p.q.r");
        Utils.updateKarafPackageVersion(configProperties, "12.0", "x");

        String newFile = FileUtils.readFileToString(configProperties);
        assertThat("etc/config.properties should be updated", newFile, equalTo(sw2.toString()));
    }

}
