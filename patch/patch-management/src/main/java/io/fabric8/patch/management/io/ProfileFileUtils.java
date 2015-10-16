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
package io.fabric8.patch.management.io;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import io.fabric8.patch.management.ProfileUpdateStrategy;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

/**
 * <p>Selected methods of {@link FileUtils} which, when copying files, use {@link EOLFixingFileOutputStream} and
 * know that they'reused to copy profile directories</p>
 * <p>See: <a href="http://commons.apache.org/proper/commons-io/">Commons-IO</a></p>
 */
public class ProfileFileUtils {

    public static final long ONE_KB = 1024;
    public static final long ONE_MB = ONE_KB * ONE_KB;
    private static final long FILE_COPY_BUFFER_SIZE = ONE_MB * 30;

    private static final Set<String> PREFIXES_TO_REMOVE = new HashSet<>(Arrays.asList(
            "bundle.",
            "feature.",
            "repository.",
            "override."

    ));

    /**
     * Just like {@link FileUtils#copyDirectory(File, File)}, but this version is aware of target <em>base</em>, so
     * it knows whether to use {@link EOLFixingFileOutputStream}.
     * @param srcDir
     * @param destDir
     * @param strategy
     * @throws IOException
     */
    public static void copyDirectory(File srcDir, File destDir, ProfileUpdateStrategy strategy) throws IOException {
        if (srcDir == null) {
            throw new NullPointerException("Source must not be null");
        }
        if (destDir == null) {
            throw new NullPointerException("Destination must not be null");
        }
        if (srcDir.exists() == false) {
            throw new FileNotFoundException("Source '" + srcDir + "' does not exist");
        }
        if (srcDir.isDirectory() == false) {
            throw new IOException("Source '" + srcDir + "' exists but is not a directory");
        }
        if (srcDir.getCanonicalPath().equals(destDir.getCanonicalPath())) {
            throw new IOException("Source '" + srcDir + "' and destination '" + destDir + "' are the same");
        }

        // Cater for destination being directory within the source directory (see IO-141)
        List<String> exclusionList = null;
        if (destDir.getCanonicalPath().startsWith(srcDir.getCanonicalPath())) {
            File[] srcFiles = srcDir.listFiles();
            if (srcFiles != null && srcFiles.length > 0) {
                exclusionList = new ArrayList<String>(srcFiles.length);
                for (File srcFile : srcFiles) {
                    File copiedFile = new File(destDir, srcFile.getName());
                    exclusionList.add(copiedFile.getCanonicalPath());
                }
            }
        }
        doCopyDirectory(srcDir, destDir, exclusionList, strategy);
    }

    private static void doCopyDirectory(File srcDir, File destDir, List<String> exclusionList, ProfileUpdateStrategy strategy) throws IOException {
        // recurse
        File[] srcFiles = srcDir.listFiles();
        if (srcFiles == null) {  // null if abstract pathname does not denote a directory, or if an I/O error occurs
            throw new IOException("Failed to list contents of " + srcDir);
        }
        if (destDir.exists()) {
            if (destDir.isDirectory() == false) {
                throw new IOException("Destination '" + destDir + "' exists but is not a directory");
            }
        } else {
            if (!destDir.mkdirs() && !destDir.isDirectory()) {
                throw new IOException("Destination '" + destDir + "' directory cannot be created");
            }
        }
        if (destDir.canWrite() == false) {
            throw new IOException("Destination '" + destDir + "' cannot be written to");
        }
        for (File srcFile : srcFiles) {
            if (srcFile.isFile() && ".skipimport".equals(srcFile.getName())) {
                return;
            }
        }
        for (File srcFile : srcFiles) {
            File dstFile = new File(destDir, srcFile.getName());
            if (exclusionList == null || !exclusionList.contains(srcFile.getCanonicalPath())) {
                if (srcFile.isDirectory()) {
                    doCopyDirectory(srcFile, dstFile, exclusionList, strategy);
                } else {
                    doCopyFile(srcFile, dstFile, strategy);
                }
            }
        }

        // Do this last, as the above has probably affected directory metadata
        destDir.setLastModified(srcDir.lastModified());
    }

    private static void doCopyFile(File srcFile, File destFile, ProfileUpdateStrategy strategy) throws IOException {
        if (destFile.exists() && destFile.isDirectory()) {
            throw new IOException("Destination '" + destFile + "' exists but is a directory");
        }

        String ext = FilenameUtils.getExtension(srcFile.getName());

        FileInputStream fis = null;
        FileOutputStream fos = null;
        OutputStream os = null;
        try {
            if (strategy == ProfileUpdateStrategy.GIT || !ext.equals("properties")) {
                // normal copy
                fis = new FileInputStream(srcFile);
                fos = new FileOutputStream(destFile);
                os = new BufferedOutputStream(fos);
                IOUtils.copyLarge(fis, os);
            } else {
                // read existing and new as properties
                FileInputStream is1 = null, is2 = null;
                try {
                    // property files may contain multiline values, so it's safest to treat them not as lists of lines
                    // but as properties
                    Properties existing = new Properties();
                    Properties newProperties = new Properties();
                    is1 = new FileInputStream(destFile); // existing file
                    is2 = new FileInputStream(srcFile);  // new file
                    existing.load(is1);
                    newProperties.load(is2);

                    Set<String> existingProperties = existing.stringPropertyNames();
                    // lets assume we're operating on "official" profiles - these are shipped in patch
                    // so we simply remote existing "override.", "bundle." and "feature." properties
                    for (String key : existingProperties) {
                        for (String prefix : PREFIXES_TO_REMOVE) {
                            if (key.startsWith(prefix)) {
                                existing.remove(key);
                            }
                        }
                    }

                    for (String key : newProperties.stringPropertyNames()) {
                        if (!existing.containsKey(key)) {
                            existing.setProperty(key, newProperties.getProperty(key));
                        } else {
                            if (strategy == ProfileUpdateStrategy.PROPERTIES_PREFER_EXISTING) {
                                // NOOP
                            } else {
                                existing.setProperty(key, newProperties.getProperty(key));
                            }
                        }
                    }

                    fos = new FileOutputStream(destFile);
                    existing.store(fos, null);

                } finally {
                    IOUtils.closeQuietly(fos);
                    IOUtils.closeQuietly(is1);
                    IOUtils.closeQuietly(is2);
                }
            }
        } finally {
            IOUtils.closeQuietly(os);
            FileInputStream is1 = new FileInputStream(srcFile);
            IOUtils.closeQuietly(fis);
        }

        if (srcFile.length() != destFile.length()) {
            throw new IOException("Failed to copy full contents from '" + srcFile + "' to '" + destFile + "'");
        }
        destFile.setLastModified(srcFile.lastModified());
    }

}
