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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import io.fabric8.patch.management.Utils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

/**
 * <p>Selected methods of {@link FileUtils} which, when copying files, use {@link EOLFixingFileOutputStream}</p>
 * <p>See: <a href="http://commons.apache.org/proper/commons-io/">Commons-IO</a></p>
 */
public class EOLFixingFileUtils {

    public static final long ONE_KB = 1024;
    public static final long ONE_MB = ONE_KB * ONE_KB;
    private static final long FILE_COPY_BUFFER_SIZE = ONE_MB * 30;

    /**
     * Just like {@link FileUtils#copyDirectory(File, File)}, but this version is aware of target <em>base</em>, so
     * it knows whether to use {@link EOLFixingFileOutputStream}.
     * @param srcDir
     * @param baseDestDir
     * @param destDir
     * @param onlyModified if source file is the same (CRC) as target file, to not change it (preserve time attrs)
     * @throws IOException
     */
    public static void copyDirectory(File srcDir, File baseDestDir, File destDir, boolean onlyModified) throws IOException {
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
        doCopyDirectory(srcDir, baseDestDir, destDir, exclusionList, onlyModified);
    }

    private static void doCopyDirectory(File srcDir, File baseDestDir, File destDir, List<String> exclusionList, boolean onlyModified) throws IOException {
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
            for (int i=0; i<10; i++) {
                if (!destDir.mkdirs()) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    break;
                }
            }
            if (!destDir.isDirectory()) {
                throw new IOException("Destination '" + destDir + "' directory cannot be created");
            }
        }
        if (destDir.canWrite() == false) {
            throw new IOException("Destination '" + destDir + "' cannot be written to");
        }
        for (File srcFile : srcFiles) {
            File dstFile = new File(destDir, srcFile.getName());
            if (exclusionList == null || !exclusionList.contains(srcFile.getCanonicalPath())) {
                if (srcFile.isDirectory()) {
                    doCopyDirectory(srcFile, baseDestDir, dstFile, exclusionList, onlyModified);
                } else {
                    if (onlyModified && dstFile.exists()) {
                        long crc1 = Utils.checksum(new FileInputStream(srcFile));
                        long crc2 = Utils.checksum(new FileInputStream(dstFile));
                        if (crc1 != crc2) {
                            doCopyFile(srcFile, baseDestDir, dstFile);
                        }
                    } else {
                        doCopyFile(srcFile, baseDestDir, dstFile);
                    }
                }
            }
        }

        // Do this last, as the above has probably affected directory metadata
        destDir.setLastModified(srcDir.lastModified());
    }

    private static void doCopyFile(File srcFile, File destDir, File destFile) throws IOException {
        if (destFile.exists() && destFile.isDirectory()) {
            throw new IOException("Destination '" + destFile + "' exists but is a directory");
        }

        FileInputStream fis = null;
        EOLFixingFileOutputStream eolFixingFos = null;
        OutputStream os = null;
        boolean skip = false;
        try {
            fis = new FileInputStream(srcFile);
            try {
                eolFixingFos = new EOLFixingFileOutputStream(destDir, destFile);
            } catch (FileNotFoundException e) {
                // ENTESB-6011: we may be getting "The process cannot access the file because it is being used by another process"
                // on Windows
                String path = Utils.relative(destDir, destFile);
                String[] tab = path.split(Pattern.quote(File.separator));
                if (tab.length >= 2 && "bin".equals(tab[0])/* && "contrib".equals(tab[1])*/) {
                    // ok - just skip the file
                    skip = true;
                } else {
                    throw e;
                }
            }
            if (!skip) {
                os = new BufferedOutputStream(eolFixingFos);
                IOUtils.copyLarge(fis, os);
            }
        } finally {
            IOUtils.closeQuietly(os);
            IOUtils.closeQuietly(fis);
        }

        if (!skip) {
            if (srcFile.length() + eolFixingFos.additionalBytesWritten() != destFile.length()) {
                throw new IOException("Failed to copy full contents from '" + srcFile + "' to '" + destFile + "'");
            }
            destFile.setLastModified(srcFile.lastModified());
        }
    }

}
