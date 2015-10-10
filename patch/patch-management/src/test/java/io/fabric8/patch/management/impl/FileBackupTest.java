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
package io.fabric8.patch.management.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

import io.fabric8.patch.management.BundleUpdate;
import io.fabric8.patch.management.PatchData;
import io.fabric8.patch.management.PatchResult;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FileBackupTest {

    private BundleContext sys;
    private File karafHome;

    @Before
    public void init() throws IOException {
        sys = mock(BundleContext.class);
        karafHome = new File("target/karaf");
        FileUtils.deleteQuietly(karafHome);
        karafHome.mkdirs();

        Bundle[] b3_7 = new Bundle[5];
        String[] symbolicNames = new String[] {
                "com.irrelevant.services",
                "org.apache.karaf.features.core",
                "com.irrelevant.iot",
                "com.irrelevant.space",
                "com.irrelevant.the.final.frontier"
        };
        Version[] versions = new Version[] {
                new Version(1, 1, 0),
                new Version(1, 2, 1),
                new Version(1, 2, 5),
                new Version(1, 1, 0),
                new Version(1, 6, 7)
        };
        for (int i=3; i<=7; i++) {
            b3_7[i - 3] = mock(Bundle.class);
            when(b3_7[i - 3].getBundleId()).thenReturn((long) i);
            when(b3_7[i - 3].getSymbolicName()).thenReturn(symbolicNames[i - 3]);
            when(b3_7[i - 3].getVersion()).thenReturn(versions[i - 3]);
        }
        when(b3_7[0].getDataFile("")).thenReturn(new File(karafHome, "data/cache/bundle3/data"));
        when(b3_7[1].getDataFile("")).thenReturn(new File(karafHome, "data/cache/bundle4/data"));
        when(b3_7[2].getDataFile("")).thenReturn(new File(karafHome, "data/cache/bundle5/data"));
        when(b3_7[4].getDataFile("")).thenReturn(new File(karafHome, "data/cache/bundle7/data"));

        new File(karafHome, "data/cache/bundle3/data/x").mkdirs();
        new File(karafHome, "data/cache/bundle4/data/x/y").mkdirs();
        new File(karafHome, "data/cache/bundle5/data/z").mkdirs();
        new File(karafHome, "data/cache/bundle6").mkdirs();
        new File(karafHome, "data/cache/bundle7/data").mkdirs();

        when(sys.getProperty("org.osgi.framework.storage")).thenReturn(new File(karafHome, "data/cache").getCanonicalPath());
        when(sys.getBundles()).thenReturn(b3_7);
    }

    @Test
    public void backupSomeDataFiles() throws IOException {
        PatchData patchData = new PatchData("my-patch");
        patchData.setPatchLocation(new File(karafHome, "patches"));
        PatchResult result = new PatchResult(patchData);
        // updates installed bundle, has data dir
        BundleUpdate b3 = new BundleUpdate("com.irrelevant.services", "1.2", "file:/dev/null", "1.1.0", "file:/dev/random");
        // updates installed bundle, has data dir, but special case
        BundleUpdate b4 = new BundleUpdate("org.apache.karaf.features.core", "1.3", "file:/dev/null", "1.2.1", "file:/dev/random");
        // reinstalled bundle, has data dir
        BundleUpdate b5 = new BundleUpdate("com.irrelevant.iot", null, null, "1.2.5", "file:/dev/random");
        // reinstalled bundle, no data dir
        BundleUpdate b6 = new BundleUpdate("com.irrelevant.space", null, null, "1.1.0", "file:/dev/random");
        // update, but not for installed bundle
        BundleUpdate b7 = new BundleUpdate("com.irrelevant.the.final.frontier", "1.5", "file:/dev/null", "1.1.3", "file:/dev/random");
        result.getBundleUpdates().add(b3);
        result.getBundleUpdates().add(b4);
        result.getBundleUpdates().add(b5);
        result.getBundleUpdates().add(b6);
        result.getBundleUpdates().add(b7);

        new FileBackupService(sys).backupDataFiles(result);

        Properties props = new Properties();
        props.load(new FileInputStream(new File(karafHome, "patches/my-patch.datafiles/backup.properties")));

        assertThat(props.getProperty("com.irrelevant.services$$1.1.0"), equalTo("com.irrelevant.services$$1.1.0"));
        assertThat(props.getProperty("com.irrelevant.services$$1.2"), equalTo("com.irrelevant.services$$1.1.0"));
        assertThat(props.getProperty("com.irrelevant.iot$$1.2.5"), equalTo("com.irrelevant.iot$$1.2.5"));
        assertThat(props.stringPropertyNames().size(), equalTo(3));

        assertTrue(new File(karafHome, "patches/my-patch.datafiles/com.irrelevant.services$$1.1.0/data/x").isDirectory());
        assertTrue(new File(karafHome, "patches/my-patch.datafiles/com.irrelevant.iot$$1.2.5/data/z").isDirectory());
        assertFalse(new File(karafHome, "patches/my-patch.datafiles/com.irrelevant.the.final.frontier$$1.5").isDirectory());
    }

}
