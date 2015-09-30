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
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;

public class UtilsTest {

    @Test
    public void fromNumericUnixPermissions() {
        assertThat(Utils.getPermissionsFromUnixMode(new File("target"), 0775), equalTo(PosixFilePermissions.fromString("rwxrwxr-x")));
        assertThat(Utils.getPermissionsFromUnixMode(new File("target"), 0641), equalTo(PosixFilePermissions.fromString("rw-r----x")));
    }

    @Test
    public void toNumericUnixPermissions() {
        assertThat(Utils.getUnixModeFromPermissions(new File("target"), PosixFilePermissions.fromString("rwxrwxr-x")), equalTo(0775));
        assertThat(Utils.getUnixModeFromPermissions(new File("target"), PosixFilePermissions.fromString("rw-rw-r--")), equalTo(0664));
        assertThat(Utils.getUnixModeFromPermissions(new File("target"), PosixFilePermissions.fromString("r--------")), equalTo(0400));
    }

    @Test
    public void relativePaths() {
        File f1 = new File("target/karaf/patches");
        File f2 = new File("target/karaf/other");
        assertThat(Utils.relative(f1, f2), equalTo("../other"));
    }

    @Test
    public void pathToMvnUris() {
        assertThat(Utils.pathToMvnurl("a/b/c"), nullValue());
    }

}
