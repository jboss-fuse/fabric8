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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import io.fabric8.patch.management.impl.Activator;
import io.fabric8.patch.management.io.EOLFixingFileOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.felix.utils.version.VersionCleaner;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Version;
import org.osgi.service.log.LogService;

public class Utils {

    private static final Pattern FEATURES_FILE = Pattern.compile(".+-features(?:-core)?$");
    private static final Pattern SYMBOLIC_NAME_PATTERN = Pattern.compile("([^;: ]+)(.*)");
    private static final Pattern KARAF_PACKAGE_VERSION = Pattern.compile(".+;version=\"([^\"]+)\"");

    private Utils() {
    }

    /**
     * When extracting patch-ZIP entry, track the item in {@link PatchData static patch data}
     * @param patchData
     * @param zip
     * @param entry
     * @param target
     */
    public static void extractAndTrackZipEntry(PatchData patchData, ZipFile zip, ZipArchiveEntry entry, File target,
                                               boolean skipRootDir) throws IOException {
        extractZipEntry(zip, entry, target);

        String name = entry.getName();
        if (skipRootDir) {
            name = name.substring(name.indexOf('/') + 1);
        }
        if (name.startsWith("system/") || name.startsWith("repository/")) {
            // Maven artifact: a bundle, feature definition file, configuration file
            if (name.startsWith("system/")) {
                name = name.substring("system/".length());
            } else if (name.startsWith("repository/")) {
                name = name.substring("repository/".length());
            }
            String fileName = FilenameUtils.getBaseName(name);
            String extension = FilenameUtils.getExtension(name);

            name = Utils.pathToMvnurl(name);
            if ("jar".equals(extension) || "war".equals(extension)) {
                patchData.getBundles().add(name);
            } else if ("xml".equals(extension) && FEATURES_FILE.matcher(fileName).matches()) {
                patchData.getFeatureFiles().add(name);
            } else {
                // must be a config, a POM (irrelevant) or other maven artifact (like ZIP)
                patchData.getOtherArtifacts().add(name);
            }
        } else {
            // ordinary entry to be applied to ${karaf.root}
            patchData.getFiles().add(name);
        }
    }

    /**
     * Exctracts ZIP entry into target file. Sets correct file permissions if found in ZIP entry.
     * @param zip
     * @param entry
     * @param target
     * @throws IOException
     */
    public static void extractZipEntry(ZipFile zip, ZipArchiveEntry entry, File target) throws IOException {
        target.getParentFile().mkdirs();
        FileOutputStream targetOutputStream = new FileOutputStream(target);
        IOUtils.copyLarge(zip.getInputStream(entry), targetOutputStream);
        IOUtils.closeQuietly(targetOutputStream);
        if (Files.getFileAttributeView(target.toPath(), PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(target.toPath(), getPermissionsFromUnixMode(target, entry.getUnixMode()));
        }
    }

    /**
     * Converts numeric UNIX permissions to a set of {@link PosixFilePermission}
     * @param file
     * @param unixMode
     * @return
     */
    public static Set<PosixFilePermission> getPermissionsFromUnixMode(File file, int unixMode) {
        String numeric = Integer.toOctalString(unixMode);
        if (numeric.length() > 3) {
            numeric = numeric.substring(numeric.length() - 3);
        }
        if (unixMode == 0) {
            return PosixFilePermissions.fromString(file.isDirectory() ? "rwxrwxr-x" : "rw-rw-r--");
        }

        Set<PosixFilePermission> result = new HashSet<>();
        int shortMode = Integer.parseInt(numeric, 8);
        if ((shortMode & 0400) == 0400)
            result.add(PosixFilePermission.OWNER_READ);
//        if ((shortMode & 0200) == 0200)
        // it was tricky ;)
        result.add(PosixFilePermission.OWNER_WRITE);
        if ((shortMode & 0100) == 0100)
            result.add(PosixFilePermission.OWNER_EXECUTE);
        if ((shortMode & 0040) == 0040)
            result.add(PosixFilePermission.GROUP_READ);
        if ((shortMode & 0020) == 0020)
            result.add(PosixFilePermission.GROUP_WRITE);
        if ((shortMode & 0010) == 0010)
            result.add(PosixFilePermission.GROUP_EXECUTE);
        if ((shortMode & 0004) == 0004)
            result.add(PosixFilePermission.OTHERS_READ);
        if ((shortMode & 0002) == 0002)
            result.add(PosixFilePermission.OTHERS_WRITE);
        if ((shortMode & 0001) == 0001)
            result.add(PosixFilePermission.OTHERS_EXECUTE);

        return result;
    }

    /**
     * Converts a set of {@link PosixFilePermission} to numeric UNIX permissions
     * @param permissions
     * @return
     */
    public static int getUnixModeFromPermissions(File file, Set<PosixFilePermission> permissions) {
        if (permissions == null) {
            return file.isDirectory() ? 0775 : 0664;
        } else {
            int result = 00;
            if (permissions.contains(PosixFilePermission.OWNER_READ)) {
                result |= 0400;
            }
            if (permissions.contains(PosixFilePermission.OWNER_WRITE)) {
                result |= 0200;
            }
            if (permissions.contains(PosixFilePermission.OWNER_EXECUTE)) {
                result |= 0100;
            }
            if (permissions.contains(PosixFilePermission.GROUP_READ)) {
                result |= 0040;
            }
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)) {
                result |= 0020;
            }
            if (permissions.contains(PosixFilePermission.GROUP_EXECUTE)) {
                result |= 0010;
            }
            if (permissions.contains(PosixFilePermission.OTHERS_READ)) {
                result |= 0004;
            }
            if (permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                result |= 0002;
            }
            if (permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
                result |= 0001;
            }
            return result;
        }
    }

    /**
     * Unpacks a ZIP file to targetDirectory
     * @param zipFile
     * @param targetDirectory
     * @param skipInitialDirectories how many levels of a path to skip when unpacking (like skipping base directory inside ZIP)
     * @throws IOException
     */
    public static void unpack(File zipFile, File targetDirectory, int skipInitialDirectories) throws IOException {
        ZipFile zf = new ZipFile(zipFile);
        try {
            for (Enumeration<ZipArchiveEntry> e = zf.getEntries(); e.hasMoreElements(); ) {
                ZipArchiveEntry entry = e.nextElement();
                String name = entry.getName();
                int skip = skipInitialDirectories;
                while (skip-- > 0) {
                    name = name.substring(name.indexOf('/') + 1);
                }
                if (entry.isDirectory()) {
                    new File(targetDirectory, name).mkdirs();
                } else /*if (!entry.isUnixSymlink())*/ {
                    File file = new File(targetDirectory, name);
                    file.getParentFile().mkdirs();
                    FileOutputStream output = new EOLFixingFileOutputStream(targetDirectory, file);
                    IOUtils.copyLarge(zf.getInputStream(entry), output);
                    IOUtils.closeQuietly(output);
                    if (Files.getFileAttributeView(file.toPath(), PosixFileAttributeView.class) != null) {
                        Files.setPosixFilePermissions(file.toPath(), getPermissionsFromUnixMode(file, entry.getUnixMode()));
                    }
                }
            }
        } finally {
            zf.close();
        }
    }

    /**
     * Retrieves location of fileinstall-managed "deploy" directory, where bundles can be dropped
     * @return
     */
    public static File getDeployDir(File karafHome) throws IOException {
        String deployDir = null;
        File fileinstallCfg = new File(System.getProperty("karaf.etc"), "org.apache.felix.fileinstall-deploy.cfg");
        if (fileinstallCfg.exists() && fileinstallCfg.isFile()) {
            Properties props = new Properties();
            FileInputStream stream = new FileInputStream(fileinstallCfg);
            props.load(stream);
            deployDir = props.getProperty("felix.fileinstall.dir");
            if (deployDir.contains("${karaf.home}")) {
                deployDir = deployDir.replace("${karaf.home}", System.getProperty("karaf.home"));
            } else if (deployDir.contains("${karaf.base}")) {
                deployDir = deployDir.replace("${karaf.base}", System.getProperty("karaf.base"));
            }
            IOUtils.closeQuietly(stream);
        } else {
            deployDir = karafHome.getAbsolutePath() + "/deploy";
        }
        return new File(deployDir);
    }

    /**
     * Returns location of system repository - by default <code>${karaf.home}/system</code>.
     * @param karafHome
     * @param systemContext
     * @return
     */
    public static File getSystemRepository(File karafHome, BundleContext systemContext) {
        return new File(karafHome, systemContext.getProperty("karaf.default.repository"));
    }

    /**
     * Returns the canonical location of the baseline distribution - it'll work in versions where baseline
     * is already shipped and correct properties are set. In distribution being just upgraded, this method returns
     * <code>null</code> which is an indication that some guessing must be performed.
     * @param karafHome
     * @param context
     * @param version
     * @return
     */
    public static String getBaselineLocationForProduct(File karafHome, BundleContext context, String version) {
        String productGA = context.getProperty("fuse.patch.product");
        if (productGA != null && !"".equals(productGA.trim())) {
            String[] ga = productGA.split(":");
            if (ga.length == 2) {
                String location = String.format("%s/%s/%s/%s-%s-baseline.zip",
                        ga[0], ga[1], version, ga[1], version);
                if (new File(getSystemRepository(karafHome, context), location).isFile()) {
                    return location;
                }
            }
        }
        return null;
    }

    /**
     * Finds relative path between two files
     * @param f1
     * @param f2
     * @return
     */
    public static String relative(File f1, File f2) {
        Path p1 = f1.toPath();
        Path p2 = f2.toPath();
        return p1.relativize(p2).toString();
    }

    /**
     * Converts file paths relative to <code>${karaf.default.repository}</code> to <code>mvn:</code> URIs
     * @param path
     * @return
     */
    public static String pathToMvnurl(String path) {
        String[] p = path.split("/");
        if (p.length >= 4 && p[p.length-1].startsWith(p[p.length-3] + "-" + p[p.length-2])) {
            String artifactId = p[p.length-3];
            String version = p[p.length-2];
            String classifier;
            String type;
            String artifactIdVersion = artifactId + "-" + version;
            StringBuffer sb = new StringBuffer();
            if (p[p.length-1].charAt(artifactIdVersion.length()) == '-') {
                classifier = p[p.length-1].substring(artifactIdVersion.length() + 1, p[p.length-1].lastIndexOf('.'));
            } else {
                classifier = null;
            }
            type = p[p.length-1].substring(p[p.length-1].lastIndexOf('.') + 1);
            sb.append("mvn:");
            for (int j = 0; j < p.length - 3; j++) {
                if (j > 0) {
                    sb.append('.');
                }
                sb.append(p[j]);
            }
            sb.append('/').append(artifactId).append('/').append(version);
            if (!"jar".equals(type) || classifier != null) {
                sb.append('/');
                if (!"jar".equals(type)) {
                    sb.append(type);
                } else if (classifier != null) {
                    sb.append(type);
                }
                if (classifier != null) {
                    sb.append('/').append(classifier);
                }
            }
            return sb.toString();
        }
        return null;
    }

    /**
     * Converts <code>mvn:</code> URIs to file paths relative to <code>${karaf.default.repository}</code>
     * @param url
     */
    public static String mvnurlToPath(String url) {
        Artifact artifact = Utils.mvnurlToArtifact(url, true);
        if (artifact == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        String[] group = artifact.getGroupId().split("\\.");
        for (String g : group) {
            sb.append('/').append(g);
        }
        sb.append('/').append(artifact.getArtifactId());
        sb.append('/').append(artifact.getVersion());
        sb.append('/').append(artifact.getArtifactId()).append("-").append(artifact.getVersion());
        if (artifact.getClassifier() != null) {
            sb.append('-').append(artifact.getClassifier());
        }
        sb.append('.').append(artifact.getType() == null || "".equals(artifact.getType().trim()) ? "jar" : artifact.getType());

        return sb.toString().substring(1);
    }

    public static Artifact mvnurlToArtifact(String resourceLocation, boolean skipNonMavenProtocols) {
        resourceLocation = resourceLocation.replace("\r\n", "").replace("\n", "").replace(" ", "").replace("\t", "");
        final int index = resourceLocation.indexOf("mvn:");
        if (index < 0) {
            if (skipNonMavenProtocols) {
                return null;
            }
            throw new IllegalArgumentException("Resource URL is not a maven URL: " + resourceLocation);
        } else {
            resourceLocation = resourceLocation.substring(index + "mvn:".length());
        }
        // Truncate the URL when a '#', a '?' or a '$' is encountered
        final int index1 = resourceLocation.indexOf('?');
        final int index2 = resourceLocation.indexOf('#');
        int endIndex = -1;
        if (index1 > 0) {
            if (index2 > 0) {
                endIndex = Math.min(index1, index2);
            } else {
                endIndex = index1;
            }
        } else if (index2 > 0) {
            endIndex = index2;
        }
        if (endIndex >= 0) {
            resourceLocation = resourceLocation.substring(0, endIndex);
        }
        final int index3 = resourceLocation.indexOf('$');
        if (index3 > 0) {
            resourceLocation = resourceLocation.substring(0, index3);
        }

        String[] parts = resourceLocation.split("/");
        if (parts.length > 2) {
            String groupId = parts[0];
            String artifactId = parts[1];
            String version = parts[2];
            String type = "jar";
            String classifier = null;
            if (parts.length > 3) {
                type = parts[3];
                if (parts.length > 4) {
                    classifier = parts[4];
                }
            }
            return new Artifact(groupId, artifactId, version, type, classifier);
        }
        throw new IllegalArgumentException("Bad maven url: " + resourceLocation);
    }


    /**
     * Strips symbolic name from directives.
     * @param symbolicName
     * @return
     */
    public static String stripSymbolicName(String symbolicName) {
        Matcher m = SYMBOLIC_NAME_PATTERN.matcher(symbolicName);
        if (m.matches() && m.groupCount() >= 1) {
            return m.group(1);
        } else {
            return symbolicName;
        }
    }

    /**
     * Feature versions may not have 4 positions. Let's make them canonical
     * @param version
     * @return
     */
    public static Version getOsgiVersion(String version) {
        if (version == null || "".equals(version.trim())) {
            return Version.emptyVersion;
        }
        return new Version(VersionCleaner.clean(version));
    }

    /**
     * Feature versions may not have 4 positions. Let's make them canonical
     * @param version
     * @return
     */
    private static Version getFeatureVersion(String version) {
        if (version == null || "".equals(version.trim())) {
            return Version.emptyVersion;
        }
        String[] vt = version.split("\\.");
        String[] nvt = new String[4];
        int[] v123 = new int[] { 0, 0, 0 };
        String v4 = null;

        // let's assume we don't parse versions like 1.3-fuse.3
        if (vt.length < 4) {
            try {
                Integer.parseInt(vt[vt.length - 1]);
                for (int i=0; i<vt.length; i++) {
                    v123[i] = Integer.parseInt(vt[i]);
                }
            } catch (NumberFormatException e) {
                for (int i=0; i<vt.length-1; i++) {
                    v123[i] = Integer.parseInt(vt[i]);
                }
                v4 = vt[vt.length - 1];
            }
        } else {
            for (int i=0; i<3; i++) {
                v123[i] = Integer.parseInt(vt[i]);
            }
            v4 = vt[vt.length - 1];
        }

        return new Version(v123[0], v123[1], v123[2], v4);
    }

    /**
     * Iterates over {@link BundleUpdate bundle updates} and returns a mapping of old filesystem location
     * to new one. All locations are relative to <code>${karaf.default.repository}</code>
     * @param bundleUpdatesInThisPatch
     * @return
     */
    public static Map<String,String> collectLocationUpdates(List<BundleUpdate> bundleUpdatesInThisPatch) {
        HashMap<String, String> locationUpdates = new HashMap<>();
        if (bundleUpdatesInThisPatch != null) {
            for (BundleUpdate update : bundleUpdatesInThisPatch) {
                if (update.getPreviousLocation() != null && update.getNewLocation() != null) {
                    String l1 = update.getPreviousLocation();
                    String l2 = update.getNewLocation();
                    if (l1.contains("org/ops4j/pax/url/pax-url-aether")) {
                        l1 = l1.substring(l1.indexOf("org/ops4j/pax/url/pax-url-aether"));
                        l2 = l2.substring(l2.indexOf("org/ops4j/pax/url/pax-url-aether"));
                        locationUpdates.put(l1, l2);
                    } else {
                        locationUpdates.put(Utils.mvnurlToPath(l1), Utils.mvnurlToPath(l2));
                    }
                }
            }
        }

        return locationUpdates;
    }

    /**
     * Updates version of exported karaf packages inside <code>etc/config.properties</code>
     * @param configProperties
     * @param newVersion
     * @param packages
     */
    public static void updateKarafPackageVersion(File configProperties, String newVersion, String ... packages) {
        BufferedReader reader = null;
        StringWriter sw = new StringWriter();
        try {
            reader = new BufferedReader(new FileReader(configProperties));
            String line = null;
            while ((line = reader.readLine()) != null) {
                for (String pkg : packages) {
                    Matcher matcher = KARAF_PACKAGE_VERSION.matcher(line);
                    if (line.contains(pkg + ";version=") && matcher.find()) {
                        StringBuffer sb = new StringBuffer();
                        sb.append(line.substring(0, matcher.start(1)));
                        sb.append(newVersion);
                        sb.append(line.substring(matcher.end(1)));
                        line = sb.toString();
                    }
                }
                sw.append(line).append("\n");
            }
            IOUtils.closeQuietly(reader);
            FileUtils.write(configProperties, sw.toString());
        } catch (Exception e) {
            Activator.log(LogService.LOG_ERROR, null, e.getMessage(), e, true);
        } finally {
            IOUtils.closeQuietly(reader);
        }
    }

    /**
     * Compute a checksum for the given stream
     *
     * @param is the input stream
     * @return a checksum identifying any change
     */
    public static long checksum(InputStream is) throws IOException {
        try {
            CRC32 crc = new CRC32();
            byte[] buffer = new byte[8192];
            int l;
            while ((l = is.read(buffer)) > 0) {
                crc.update(buffer, 0, l);
            }
            return crc.getValue();
        } finally {
            IOUtils.closeQuietly(is);
        }
    }

}
