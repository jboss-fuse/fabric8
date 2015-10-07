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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Date;

import org.apache.commons.io.FileUtils;
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
        File f1 = new File("target/karaf/patches");
        File f2 = new File("target/karaf/other");
        assertThat(relative(f1, f2), equalTo("../other"));
    }

    @Test
    public void pathToMvnUris() {
        assertThat(pathToMvnurl("a/b/c"), nullValue());
        assertThat(pathToMvnurl("a/b/c/d"), nullValue());
        assertThat(pathToMvnurl("a/b/c/d/e"), nullValue());

        assertThat(pathToMvnurl("a/b/c/b-c.jar"), equalTo("mvn:a/b/c"));
        assertThat(pathToMvnurl("a/b/b/c/b-c.jar"), equalTo("mvn:a.b/b/c"));
        assertThat(pathToMvnurl("a/b/c/b-c.war"), equalTo("mvn:a/b/c/war"));

        assertThat(pathToMvnurl("a/b/c/b-c-x.war"), equalTo("mvn:a/b/c/war/x"));
        assertThat(pathToMvnurl("a/b/c/b-c-x-y.xml"), equalTo("mvn:a/b/c/xml/x-y"));
    }

    @Test
    public void testSymbolicNameStrip() {
        assertEquals("my.bundle", stripSymbolicName("my.bundle"));
        assertEquals("my.bundle", stripSymbolicName("my.bundle;singleton:=true"));
        assertEquals("my.bundle", stripSymbolicName("my.bundle;blueprint.graceperiod:=false;"));
        assertEquals("my.bundle", stripSymbolicName("my.bundle;blueprint.graceperiod:=false; blueprint.timeout=10000;"));
    }

    @Test
    public void canonicalVersions() {
        assertEquals("1.1.1.1", Utils.getFeatureVersion("1.1.1.1").toString());
        assertEquals("1.1.1", Utils.getFeatureVersion("1.1.1").toString());
        assertEquals("1.1.0", Utils.getFeatureVersion("1.1").toString());
        assertEquals("1.0.0", Utils.getFeatureVersion("1").toString());
        assertEquals("0.0.0", Utils.getFeatureVersion("").toString());
        assertEquals("0.0.0", Utils.getFeatureVersion(null).toString());

        assertEquals("1.1.1.redhat-1", Utils.getFeatureVersion("1.1.1.redhat-1").toString());
        assertEquals("1.1.0.redhat-1", Utils.getFeatureVersion("1.1.redhat-1").toString());
        assertEquals("1.0.0.redhat-1", Utils.getFeatureVersion("1.redhat-1").toString());

        assertEquals("1.1.1.redhat-1", Utils.getFeatureVersion("1.1.1.1.1.redhat-1").toString());
        assertEquals("1.1.1.redhat-1", Utils.getFeatureVersion("1.1.1.1.redhat-1").toString());
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

}
