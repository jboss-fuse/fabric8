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
package io.fabric8.maven.url.internal;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.impl.RemoteRepositoryManager;
import org.eclipse.aether.impl.UpdatePolicyAnalyzer;
import org.eclipse.aether.internal.impl.SimpleLocalRepositoryManagerFactory;
import org.eclipse.aether.internal.impl.SmartTrackingFileManager;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.LocalArtifactRegistration;
import org.eclipse.aether.repository.LocalArtifactRequest;
import org.eclipse.aether.repository.LocalArtifactResult;
import org.eclipse.aether.repository.LocalMetadataRequest;
import org.eclipse.aether.repository.LocalMetadataResult;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.NoLocalRepositoryManagerException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.spi.locator.ServiceLocator;
import org.eclipse.aether.spi.log.Logger;
import org.eclipse.aether.spi.log.LoggerFactory;
import org.eclipse.aether.spi.log.NullLoggerFactory;

/**
 * Factory that creates {@link LocalRepositoryManager}-like classes that can handle SNAPSHOT
 * metadata specific to remote repositories
 */
public class SmartLocalRepositoryManagerFactory extends SimpleLocalRepositoryManagerFactory {

    public static final String PROPERTY_UPDATE_RELEASES = "paxUrlAether.updateReleases";
    private static final DateFormat TS = new SimpleDateFormat("yyyyMMddHHmmss");

    static {
        TS.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private Logger logger;
    private UpdatePolicyAnalyzer updatePolicyAnalyzer;
    private RemoteRepositoryManager remoteRepositoryManager;

    @Override
    public void initService(ServiceLocator locator) {
        super.initService(locator);
        updatePolicyAnalyzer = locator.getService(UpdatePolicyAnalyzer.class);
        remoteRepositoryManager = locator.getService(RemoteRepositoryManager.class);
    }

    @Override
    public LocalRepositoryManager newInstance(RepositorySystemSession session, LocalRepository repository) throws NoLocalRepositoryManagerException {
        LocalRepositoryManager delegate = super.newInstance(session, repository);
        return new SmartLocalRepositoryManager(delegate);
    }

    @Override
    public SimpleLocalRepositoryManagerFactory setLoggerFactory(LoggerFactory loggerFactory) {
        SimpleLocalRepositoryManagerFactory managerFactory = super.setLoggerFactory(loggerFactory);
        this.logger = NullLoggerFactory.getSafeLogger(loggerFactory, SimpleLocalRepositoryManagerFactory.class);
        return managerFactory;
    }

    @Override
    public float getPriority() {
        return 20.0F;
    }

    /**
     * Returns full path of <code>maven-metadata.xml</code>. We need this, because normally, local repository
     * manager looks <strong>only</strong> for <code>maven-metadata-ID.xml</code>, where <code>ID</code> is either
     * "local" or an ID of remote repository.
     * @param metadata
     * @return
     */
    private String getMetadataPath(Metadata metadata) {
        StringBuilder path = new StringBuilder(128);

        if (metadata.getGroupId().length() > 0) {
            path.append(metadata.getGroupId().replace('.', '/')).append('/');

            if (metadata.getArtifactId().length() > 0) {
                path.append(metadata.getArtifactId()).append('/');

                if (metadata.getVersion().length() > 0) {
                    path.append(metadata.getVersion()).append('/');
                }
            }
        }

        path.append("maven-metadata.xml");

        return path.toString();
    }

    /**
     * A {@link LocalRepositoryManager} that can find <code>maven-metadata.xml</code> as if they were stored
     * inside remote repository instead of local repository. It also can handle release artifact updates.
     */
    private class SmartLocalRepositoryManager extends LocalRepositoryManagerWrapper {

        private final String trackingFilename;
        private final SmartTrackingFileManager trackingFileManager;

        public SmartLocalRepositoryManager(LocalRepositoryManager delegate) {
            super(delegate);

            trackingFilename = "_pax-url-aether-remote.repositories";
            trackingFileManager = new SmartTrackingFileManager();
            trackingFileManager.setLogger(logger);
        }

        @Override
        public LocalMetadataResult find(RepositorySystemSession session, LocalMetadataRequest request) {
            RemoteRepository remote = request.getRepository();

            LocalMetadataResult defaultResult = super.find(session, request);
            if (remote != null || defaultResult.getFile() != null) {
                // we've found either maven-metadata-<ID OF REMOTE REPO>.xml or maven-metadata-local.xml
                return defaultResult;
            }

            // we're additionally looking for maven-metadata.xml which is stored locally in true remote Maven
            // repository, because normally local repository contains only:
            //  - local metadata in maven-metadata-local.xml, or
            //  - local version of remote metadata in maven-metadata-<ID OF REPOSITORY>.xml
            // which are normally available in ~/.m2/repository
            // local repository always contains maven-metadata-*.xml with indication of metadata source
            // ("local" or remote repository's ID)
            // local/hosted storage of remote repository contains plain "maven-metadata.xml" file
            LocalMetadataResult result = new LocalMetadataResult(request);

            String path = getMetadataPath(request.getMetadata());

            File file = new File(getRepository().getBasedir(), path);
            if (file.isFile()) {
                result.setFile(file);
            }

            return result;
        }

        @Override
        public LocalArtifactResult find(RepositorySystemSession session, LocalArtifactRequest request) {
            LocalArtifactResult result = super.find(session, request);

            if (!result.isAvailable()) {
                return result;
            }

            // getArtifact().getVersion().endsWith("-SNAPSHOT") is NOT the same as
            // getArtifact().isSnapshot()!
            if (request.getArtifact().getVersion().endsWith("-SNAPSHOT")
                    && request.getRepositories() != null && request.getRepositories().size() > 0) {
                // ENTESB-6486 - we could've just downloaded maven-metadata.xml from a _remote_ repository
                // that got it from _local_ repository - i.e., it doesn't contain:
                // <version>x.y.z-yyyyMMdd.HHmmss-<build-number></version>
                // but just:
                // <version>x.y.z-SNAPSHOT</version>
                // so we have to check again metadata that may have been updated

                File metadata = new File(result.getFile().getParentFile(),
                        String.format("maven-metadata-%s.xml", request.getRepositories().get(0).getId()));
                if (metadata.isFile()) {
                    try {
                        try (FileReader reader = new FileReader(metadata)) {
                            org.apache.maven.artifact.repository.metadata.Metadata md = new MetadataXpp3Reader().read(reader);
                            if (md.getVersioning() != null
                                    && md.getVersioning().getSnapshot() != null
                                    && md.getVersioning().getSnapshotVersions() != null) {
                                if (md.getVersioning().getSnapshot().isLocalCopy()) {
                                    File artifactFile = result.getFile();
                                    String extension = request.getArtifact().getExtension();
                                    for (SnapshotVersion sv : md.getVersioning().getSnapshotVersions()) {
                                        if (sv.getExtension().equals(extension)) {
                                            String updated = sv.getUpdated(); // UTC!!!
                                            long remoteTs = TS.parse(updated).getTime();
                                            long lastUpdated = result.getFile().lastModified();
                                            // a bit delicate check, maybe another properties file should be used?
                                            if (remoteTs > lastUpdated) {
                                                unavailable(result, request.getRepositories().get(0));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (IOException | XmlPullParserException | ParseException e) {
                        logger.debug(e.getMessage(), e);
                    }
                }
            } else if (!request.getArtifact().isSnapshot()
                    && (Boolean) session.getConfigProperties().get(PROPERTY_UPDATE_RELEASES)) {
                // check if we should force download
                File trackingFile = getTrackingFile(result.getFile());
                Properties props = trackingFileManager.read(trackingFile);
                if (props != null) {
                    String localKey = result.getFile().getName() + ">";
                    if (props.get(localKey) == null) {
                        // artifact is available, but doesn't origin from local repository
                        for (RemoteRepository repo : request.getRepositories()) {
                            String remoteKey = result.getFile().getName() + ">" + repo.getId();
                            if (props.get(remoteKey) != null) {
                                // artifact origins from remote repository, check policy
                                long lastUpdated = result.getFile().lastModified();
                                RepositoryPolicy policy = remoteRepositoryManager.getPolicy(session, repo, true, false);
                                if (updatePolicyAnalyzer.isUpdatedRequired(session, lastUpdated, policy.getUpdatePolicy())) {
                                    unavailable(result, repo);
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            return result;
        }

        @Override
        public void add(RepositorySystemSession session, LocalArtifactRegistration request) {
            super.add(session, request);

            // ideally, we should touch the SNAPSHOT artifact with a timestamp from
            // /<metadata>/<versioning>/<snapshotVersions>/<snapshotVersion>/<updated>

            if (!request.getArtifact().isSnapshot()
                    && (Boolean) session.getConfigProperties().get(PROPERTY_UPDATE_RELEASES)) {
                String path;
                if (request.getRepository() == null) {
                    path = getPathForLocalArtifact(request.getArtifact());
                } else {
                    path = getPathForRemoteArtifact(request.getArtifact(), request.getRepository(), "");
                }
                File artifactFile = new File(getRepository().getBasedir(), path);
                File trackingFile = getTrackingFile(artifactFile);
                String repoId = request.getRepository() == null ? "" : request.getRepository().getId();

                Map<String, String> updates = new HashMap<String, String>();
                updates.put(artifactFile.getName() + ">" + repoId, "");
                trackingFileManager.update(trackingFile, updates);
            }
        }

        /**
         * Forget about local copy of artifact
         * @param result
         * @param repo
         */
        private void unavailable(LocalArtifactResult result, RemoteRepository repo) {
            logger.debug("Invalidating " + result.getFile() + " in local repository " + getRepository().getBasedir());
            result.setAvailable(false);
            // needed for non SNAPSHOTs.
            // If we don't null, PeekTaskRunner will be used instead of GetTaskRunner
            result.setFile(null);
            result.setRepository(repo);
        }

        private File getTrackingFile(File artifactFile) {
            return new File(artifactFile.getParentFile(), trackingFilename);
        }

    }

}
