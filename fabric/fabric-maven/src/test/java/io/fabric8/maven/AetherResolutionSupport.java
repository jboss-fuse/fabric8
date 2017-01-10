/*
 *  Copyright 2005-2017 Red Hat, Inc.
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
package io.fabric8.maven;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;
import java.util.UUID;

import com.google.common.base.Joiner;
import io.fabric8.maven.url.ServiceConstants;
import io.fabric8.maven.url.internal.AetherBasedResolver;
import io.fabric8.maven.util.MavenConfigurationImpl;
import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Snapshot;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.junit.Before;
import org.ops4j.util.property.PropertiesPropertyResolver;

public class AetherResolutionSupport {

    // used only to parse dates
    private static final DateFormat HM;
    // used to format dates
    private static final DateFormat HM1;
    private static final DateFormat HM2;

    protected File localRepository;

    static {
        HM = new SimpleDateFormat("YYYY-MM-dd HH:mm:ss");
        HM.setTimeZone(TimeZone.getTimeZone("UTC"));
        HM1 = new SimpleDateFormat("YYYYMMddHHmmss");
        HM1.setTimeZone(TimeZone.getTimeZone("UTC"));
        HM2 = new SimpleDateFormat("YYYYMMdd.HHmmss");
        HM2.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    @Before
    public void init() throws IOException {
        localRepository = initFileRepository("lr");
    }

    /**
     * Creates a {@link Date} at specific hour and minute with arbitrary day/month/year
     * @param hourMinute
     * @return
     */
    protected Date at(String hourMinute) {
        try {
            return HM.parse("2017-01-01 " + hourMinute + ":00");
        } catch (ParseException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Prepares empty, fresh defaultRepository
     * @return
     */
    protected File initFileRepository(String prefix) throws IOException {
        File repository = new File("target/repositories/" + prefix + "-" + UUID.randomUUID().toString());
        FileUtils.deleteDirectory(repository);
        repository.mkdirs();
        return repository;
    }

    /**
     * <p>Simulation of <code>mvn clean install</code> inside local repository</p>
     * <p>Like <code>mvn clean install -DskipTests -Dmaven.repo.local=_repo</code></p>
     * @param repository
     * @param groupId
     * @param artifactId
     * @param version
     * @param content
     */
    protected void mvnInstall(File repository, String groupId, String artifactId, String version, Date timestamp, String content) throws IOException {
        File location = new File(repository, String.format("%s/%s/%s",
                groupId.replaceAll("\\.", "/"),
                artifactId,
                version));
        String name = String.format("%s-%s", artifactId, version);
        File jar = new File(location, name + ".jar");
        FileUtils.write(jar, content);
        jar.setLastModified(timestamp.getTime());
        File pom = new File(location, name + ".pom");
        FileUtils.touch(pom);
        pom.setLastModified(timestamp.getTime());

        String remoteRepositoriesConfig = "";
        remoteRepositoriesConfig += name + ".jar>=\n";
        remoteRepositoriesConfig += name + ".pom>=\n";
        File remoteRepositories = new File(location, "_remote.repositories");
        FileUtils.write(remoteRepositories, remoteRepositoriesConfig);
        remoteRepositories.setLastModified(timestamp.getTime());

        Metadata gamd = getOrCreateLocalMetadata(repository, groupId, artifactId);
        if (!gamd.getVersioning().getVersions().contains(version)) {
            gamd.getVersioning().addVersion(version);
        }

        if (!version.endsWith("-SNAPSHOT")) {
            gamd.getVersioning().setRelease(version);
        } else {
            Metadata gavmd = getOrCreateLocalMetadata(repository, groupId, artifactId, version);
            gavmd.getVersioning().setSnapshot(new Snapshot());
            gavmd.getVersioning().getSnapshot().setLocalCopy(true);
            gavmd.getVersioning().setLastUpdatedTimestamp(timestamp);
            gavmd.getVersioning().getSnapshotVersions().clear();
            SnapshotVersion sv1 = new SnapshotVersion();
            sv1.setUpdated(gavmd.getVersioning().getLastUpdated());
            sv1.setVersion(version);
            sv1.setExtension("jar");
            gavmd.getVersioning().getSnapshotVersions().add(sv1);
            SnapshotVersion sv2 = new SnapshotVersion();
            sv2.setUpdated(gavmd.getVersioning().getLastUpdated());
            sv2.setVersion(version);
            sv2.setExtension("pom");
            gavmd.getVersioning().getSnapshotVersions().add(sv2);

            gavmd.getVersioning().setLastUpdatedTimestamp(timestamp);
            File gavmdFile = new File(location, "maven-metadata-local.xml");
            new MetadataXpp3Writer().write(new FileWriter(gavmdFile), gavmd);
            gavmdFile.setLastModified(timestamp.getTime());
        }

        gamd.getVersioning().setLastUpdatedTimestamp(timestamp);
        File gamdFile = new File(location.getParentFile(), "maven-metadata-local.xml");
        new MetadataXpp3Writer().write(new FileWriter(gamdFile), gamd);
        gamdFile.setLastModified(timestamp.getTime());
    }

    /**
     * <p>Simulation of <code>mvn deploy</code> in remote (even if <code>file://</code>-based) repository</p>
     * <p>Like <code>mvn clean deploy -DskipTests -Dmaven.repo.local=_repo -DaltDeploymentRepository=id::default::file:_remote-repo</code></p>
     * @param localRepository repository where the artifact is first <code>mvn install</code>ed
     * @param remoteRepository repository where the artifact is <code>mvn deploy</code>ed fom <code>localRepository</code>
     * @param groupId
     * @param artifactId
     * @param version
     * @param timestamp
     * @param content
     * @throws IOException
     */
    protected void mvnDeploy(File localRepository, File remoteRepository, String groupId, String artifactId, String version, Date timestamp, String content) throws IOException {
        mvnInstall(localRepository, groupId, artifactId, version, timestamp, content);

        Metadata gamd = getOrCreateMetadata(remoteRepository, groupId, artifactId);
        Metadata gavmd = getOrCreateMetadata(remoteRepository, groupId, artifactId, version);

        File from = new File(localRepository, String.format("%s/%s/%s",
                groupId.replaceAll("\\.", "/"),
                artifactId,
                version));
        File location = new File(remoteRepository, String.format("%s/%s/%s",
                groupId.replaceAll("\\.", "/"),
                artifactId,
                version));

        location.mkdirs();

        String transformedVersion = version;

        if (!gamd.getVersioning().getVersions().contains(version)) {
            gamd.getVersioning().addVersion(version);
        }

        if (!version.endsWith("-SNAPSHOT")) {

        } else {
            if (gavmd.getVersioning().getSnapshot() == null) {
                gavmd.getVersioning().setSnapshot(new Snapshot());
                gavmd.getVersioning().getSnapshot().setBuildNumber(1);
            } else {
                gavmd.getVersioning().getSnapshot().setBuildNumber(gavmd.getVersioning().getSnapshot().getBuildNumber() + 1);
            }

            transformedVersion = version.replaceFirst("SNAPSHOT$",
                    HM2.format(timestamp) + "-" + gavmd.getVersioning().getSnapshot().getBuildNumber());

            gavmd.getVersioning().getSnapshot().setTimestamp(HM2.format(timestamp));
            gavmd.getVersioning().setLastUpdatedTimestamp(timestamp);

            gavmd.getVersioning().getSnapshotVersions().clear();
            SnapshotVersion sv1 = new SnapshotVersion();
            sv1.setUpdated(gavmd.getVersioning().getLastUpdated());
            sv1.setVersion(transformedVersion);
            sv1.setExtension("jar");
            gavmd.getVersioning().getSnapshotVersions().add(sv1);
            SnapshotVersion sv2 = new SnapshotVersion();
            sv2.setUpdated(gavmd.getVersioning().getLastUpdated());
            sv2.setVersion(transformedVersion);
            sv2.setExtension("pom");
            gavmd.getVersioning().getSnapshotVersions().add(sv2);

            gavmd.getVersioning().setLastUpdatedTimestamp(timestamp);
            File gavmdFile = new File(location, "maven-metadata.xml");
            new MetadataXpp3Writer().write(new FileWriter(gavmdFile), gavmd);
            gavmdFile.setLastModified(timestamp.getTime());

            FileUtils.copyFile(new File(location, "maven-metadata.xml"),
                    new File(from, String.format("maven-metadata-%s.xml", remoteRepository.getName())));
        }

        String jar = String.format("%s-%s.jar", artifactId, version);
        String pom = String.format("%s-%s.pom", artifactId, version);
        String rjar = String.format("%s-%s.jar", artifactId, transformedVersion);
        String rpom = String.format("%s-%s.pom", artifactId, transformedVersion);
        FileUtils.copyFile(new File(from, jar), new File(location, rjar));
        FileUtils.copyFile(new File(from, pom), new File(location, rpom));

        gamd.getVersioning().setLastUpdatedTimestamp(timestamp);
        File gamdFile = new File(location.getParentFile(), "maven-metadata.xml");
        new MetadataXpp3Writer().write(new FileWriter(gamdFile), gamd);
        gamdFile.setLastModified(timestamp.getTime());

        FileUtils.copyFile(new File(location.getParentFile(), "maven-metadata.xml"),
                new File(from.getParentFile(), String.format("maven-metadata-%s.xml", remoteRepository.getName())));
    }

    private Metadata getOrCreateLocalMetadata(File repository, String groupId, String artifactId) throws IOException {
        return getOrCreateMetadata(repository, groupId, artifactId, null, "local");
    }

    private Metadata getOrCreateLocalMetadata(File repository, String groupId, String artifactId, String version) throws IOException {
        return getOrCreateMetadata(repository, groupId, artifactId, version, "local");
    }

    private Metadata getOrCreateMetadata(File repository, String groupId, String artifactId) throws IOException {
        return getOrCreateMetadata(repository, groupId, artifactId, null, null);
    }

    private Metadata getOrCreateMetadata(File repository, String groupId, String artifactId, String version) throws IOException {
        return getOrCreateMetadata(repository, groupId, artifactId, version, null);
    }

    private Metadata getOrCreateMetadata(File repository, String groupId, String artifactId, String version, String repoId) throws IOException {
        File metadata;
        if (version == null) {
            metadata = new File(repository, String.format("%s/%s/maven-metadata%s.xml",
                    groupId.replaceAll("\\.", "/"),
                    artifactId,
                    repoId == null ? "" : "-" + repoId));
        } else {
            metadata = new File(repository, String.format("%s/%s/%s/maven-metadata%s.xml",
                    groupId.replaceAll("\\.", "/"),
                    artifactId,
                    version,
                    repoId == null ? "" : "-" + repoId));
        }
        Metadata md;
        if (metadata.isFile()) {
            try {
                md = new MetadataXpp3Reader().read(new FileReader(metadata));
            } catch (XmlPullParserException e) {
                throw new IOException(e.getMessage(), e);
            }
        } else {
            md = new Metadata();
            md.setGroupId(groupId);
            md.setArtifactId(artifactId);
            if (version != null) {
                md.setVersion(version);
            }
            md.setVersioning(new Versioning());
        }

        return md;
    }

    protected class ResolverBuilder {

        private Properties properties = new Properties();

        public ResolverBuilder() throws IOException {
            properties.setProperty("pid." + ServiceConstants.PROPERTY_LOCAL_REPOSITORY, localRepository.getCanonicalPath());
            properties.setProperty("pid." + ServiceConstants.PROPERTY_USE_FALLBACK_REPOSITORIES, "false");
            properties.setProperty("pid." + ServiceConstants.PROPERTY_GLOBAL_CHECKSUM_POLICY, "ignore");
        }

        public AetherSnapshotResolutionTest.ResolverBuilder withDefaultRepositories(List<File> defaultRepositories) {
            properties.setProperty("pid." + ServiceConstants.PROPERTY_DEFAULT_REPOSITORIES, listOfFileLocations(defaultRepositories));
            return this;
        }

        public AetherSnapshotResolutionTest.ResolverBuilder withRemoteRepositories(List<File> remoteRepositories) {
            properties.setProperty("pid." + ServiceConstants.PROPERTY_REPOSITORIES, listOfFileLocations(remoteRepositories));
            return this;
        }

        public ResolverBuilder withUpdatePolicy(String policy) {
            properties.setProperty("pid." + ServiceConstants.PROPERTY_GLOBAL_UPDATE_POLICY, policy);
            return this;
        }

        public ResolverBuilder withReleaseUpdates() {
            properties.setProperty("pid." + ServiceConstants.PROPERTY_UPDATE_RELEASES, "true");
            return this;
        }

        public MavenResolver build() {
            return new AetherBasedResolver(new MavenConfigurationImpl(new PropertiesPropertyResolver(properties), "pid"));
        }

        private String listOfFileLocations(List<File> locations) {
            List<String> uris = new ArrayList<>(locations.size());
            try {
                for (File f : locations) {
                    uris.add(String.format("%s@id=%s@snapshots", f.toURI().toURL().toString(), f.getName()));
                }
            } catch (MalformedURLException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
            return Joiner.on(", ").join(uris);
        }
    }

}
