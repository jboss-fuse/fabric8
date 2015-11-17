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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import io.fabric8.patch.management.Utils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;

/**
 * {@link FileOutputStream} replacement which ensures that for critical files (inside <code>bin</code> or
 * <code>etc</code> dirs), each text file ends with new line (or \r\n in case of *.bat).
 */
public class EOLFixingFileOutputStream extends FileOutputStream {

    private boolean needsChecking = false;
    private boolean probablyNeedsFixing = false;
    private String EOL = "\n";
    private int EOLLength = 1;
    private long additionalBytes = 0L;
    private boolean fixCRLF;

    private boolean lineConversionMode = false;
    private ByteArrayOutputStream conversionBuffer = null;

    private volatile boolean closed = false;

    private static final Set<String> IMPORTANT_DIRECTORIES = new HashSet<>(Arrays.asList(
            "bin", "etc", "fabric", "licenses", "metatype"
    ));

    private static final Set<String> IMPORTANT_EXTENSIONS = new HashSet<>(Arrays.asList(
            "md", "properties", "xml", "json", "txt", "cfg", "config"
    ));

    /**
     * Creates an enhanced {@link FileOutputStream} that writes to a file residing inside <code>targetDirectory</code>
     * @param targetDirectory is used to find relative path of the <code>file</code>
     * @param file
     * @throws IOException
     */
    public EOLFixingFileOutputStream(File targetDirectory, File file) throws IOException {
        super(file);
        String path = Utils.relative(targetDirectory, file);
        String[] tab = path.split(Pattern.quote(File.separator));
        if (tab.length >= 2) {
            String firstDirectory = tab[0];
            String ext = FilenameUtils.getExtension(file.getName());
            if ("etc".equals(firstDirectory) && "cfg".equals(ext)) {
                // ENTESB-4367: fileinstall converts all LF to CRLF on Windows when etc/**/*.cfg files are
                // changed. It doesn't happen initially - only after first change!
                lineConversionMode = true;
                conversionBuffer = new ByteArrayOutputStream();
            } else {
                if (IMPORTANT_DIRECTORIES.contains(firstDirectory.toLowerCase())) {
                    if (ext.indexOf('#') > 0) {
                        ext = ext.substring(ext.indexOf('#'));
                    }
                    if (!"fabric".equals(firstDirectory.toLowerCase()) || IMPORTANT_EXTENSIONS.contains(ext)) {
                        needsChecking = true;
                    }
                }
                if (path.endsWith(".bat")) {
                    EOL = "\r\n";
                    EOLLength = 2;
                }
            }
        }
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (lineConversionMode) {
            conversionBuffer.write(b, off, len);
        } else {
            super.write(b, off, len);
            if (!needsChecking) {
                return;
            }
            if (EOLLength == 2 && len >= 2 && b[off + len - 2] != (byte) '\r' && b[off + len - 1] != (byte) '\n') {
                probablyNeedsFixing = true;
            } else if (EOLLength == 1 && len >= 1 && b[off + len - 1] != (byte) '\n') {
                probablyNeedsFixing = true;
            } else {
                probablyNeedsFixing = false;
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        if (lineConversionMode) {
            byte[] bytes = conversionBuffer.toByteArray();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] != (byte) '\r') {
                    buffer.write((int) bytes[i]);
                } else {
                    additionalBytes -= 1L;
                }
            }
            if (bytes[bytes.length - 1] != (byte) '\n') {
                additionalBytes += 1L;
                buffer.write((int) bytes[bytes.length - 1]);
            }
            super.write(buffer.toByteArray());
        } else {
            if (needsChecking && probablyNeedsFixing) {
                if (EOL.length() == 2) {
                    super.write(new byte[] { (byte) '\r', (byte) '\n' }, 0, 2);
                    additionalBytes = 2L;
                } else {
                    super.write(new byte[] { (byte) '\n' }, 0, 1);
                    additionalBytes = 1L;
                }
            }
        }
        closed = true;
        super.close();
    }

    /**
     * Returns the number of bytes written when closing stream.
     * @return
     */
    public long additionalBytesWritten() {
        return additionalBytes;
    }

    public void setFixCRLF(boolean fixCRLF) {
        this.fixCRLF = fixCRLF;
    }
}
