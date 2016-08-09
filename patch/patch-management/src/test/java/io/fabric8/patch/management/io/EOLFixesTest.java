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
package io.fabric8.patch.management.io;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

public class EOLFixesTest {

    private File target = new File("target/karaf-eol");

    @Before
    public void init() throws IOException {
        FileUtils.deleteDirectory(target);
    }

    @Test
    public void fixInCriticalDirs() throws IOException {
        {
            File t = target;
            File f = new File(t, "bin/x");
            f.getParentFile().mkdirs();
            EOLFixingFileOutputStream output = new EOLFixingFileOutputStream(t, f);
            IOUtils.write("test", output);
            IOUtils.closeQuietly(output);
            assertThat(FileUtils.readFileToString(f), equalTo("test\n"));
        }
        {
            File t = target;
            File f = new File(t, "bin/y");
            f.getParentFile().mkdirs();
            EOLFixingFileOutputStream output = new EOLFixingFileOutputStream(t, f);
            IOUtils.write("test\n", output);
            IOUtils.closeQuietly(output);
            assertThat(FileUtils.readFileToString(f), equalTo("test\n"));
        }
        {
            File t = target;
            File f = new File(t, "bin/y.bat");
            f.getParentFile().mkdirs();
            EOLFixingFileOutputStream output = new EOLFixingFileOutputStream(t, f);
            IOUtils.write("test", output);
            IOUtils.closeQuietly(output);
            assertThat(FileUtils.readFileToString(f), equalTo("test\r\n"));
        }
        {
            File t = new File(target, "y");
            File f = new File(t, "bin/x");
            f.getParentFile().mkdirs();
            EOLFixingFileOutputStream output = new EOLFixingFileOutputStream(t, f);
            IOUtils.write("test", output);
            IOUtils.closeQuietly(output);
            assertThat(FileUtils.readFileToString(f), equalTo("test\n"));
        }
        {
            File t = target;
            File f = new File(t, "y/bin/x");
            f.getParentFile().mkdirs();
            EOLFixingFileOutputStream output = new EOLFixingFileOutputStream(t, f);
            IOUtils.write("test", output);
            IOUtils.closeQuietly(output);
            assertThat(FileUtils.readFileToString(f), not(equalTo("test\n")));
        }
    }

    @Test
    public void lineConversionInEtc() throws IOException {
        {
            File t = target;
            File f = new File(t, "etc/x1.cfg");
            f.getParentFile().mkdirs();
            EOLFixingFileOutputStream output = new EOLFixingFileOutputStream(t, f);
            IOUtils.write("test\r\n", output);
            IOUtils.closeQuietly(output);
            assertThat(FileUtils.readFileToString(f), equalTo("test\n"));
        }
        {
            File t = target;
            File f = new File(t, "etc/x2.cfg");
            f.getParentFile().mkdirs();
            EOLFixingFileOutputStream output = new EOLFixingFileOutputStream(t, f);
            IOUtils.write("test", output);
            IOUtils.closeQuietly(output);
            assertThat(FileUtils.readFileToString(f), equalTo("test\n"));
        }
        {
            File t = target;
            File f = new File(t, "etc/x2.cfg2");
            f.getParentFile().mkdirs();
            EOLFixingFileOutputStream output = new EOLFixingFileOutputStream(t, f);
            IOUtils.write("test\r\n", output);
            IOUtils.closeQuietly(output);
            assertThat(FileUtils.readFileToString(f), equalTo("test\r\n"));
        }
    }

    @Test
    public void copyDir() throws IOException {
        FileUtils.write(new File(target, "src/bin/admin1"), "test");
        FileUtils.write(new File(target, "src/bin/admin1.bat"), "test");
        FileUtils.write(new File(target, "src/bin/admin2"), "test\n");
        FileUtils.write(new File(target, "src/bin/admin2.bat"), "test\r\n");
        FileUtils.write(new File(target, "src/bin/x/admin3"), "test");
        FileUtils.write(new File(target, "src/bin/x/admin3.bat"), "test");
        FileUtils.write(new File(target, "src/x/bin/admin1"), "test");
        FileUtils.write(new File(target, "src/x/bin/admin1.bat"), "test");
        FileUtils.write(new File(target, "src/x/bin/admin2"), "test\n");
        FileUtils.write(new File(target, "src/x/bin/admin2.bat"), "test\r\n");

        new File(target, "dest").mkdirs();
        EOLFixingFileUtils.copyDirectory(new File(target, "src"), new File(target, "dest"), new File(target, "dest"), false);

        assertThat(FileUtils.readFileToString(new File(target, "dest/bin/admin1")), equalTo("test\n"));
        assertThat(FileUtils.readFileToString(new File(target, "dest/bin/admin1.bat")), equalTo("test\r\n"));
        assertThat(FileUtils.readFileToString(new File(target, "dest/bin/admin2")), equalTo("test\n"));
        assertThat(FileUtils.readFileToString(new File(target, "dest/bin/admin2.bat")), equalTo("test\r\n"));
        assertThat(FileUtils.readFileToString(new File(target, "dest/bin/x/admin3")), equalTo("test\n"));
        assertThat(FileUtils.readFileToString(new File(target, "dest/bin/x/admin3.bat")), equalTo("test\r\n"));
        assertThat(FileUtils.readFileToString(new File(target, "dest/x/bin/admin1")), equalTo("test"));
        assertThat(FileUtils.readFileToString(new File(target, "dest/x/bin/admin1.bat")), equalTo("test"));
        assertThat(FileUtils.readFileToString(new File(target, "dest/x/bin/admin2")), equalTo("test\n"));
        assertThat(FileUtils.readFileToString(new File(target, "dest/x/bin/admin2.bat")), equalTo("test\r\n"));
    }

}
