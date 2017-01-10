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
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
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
     * Returns full path of <code>maven-metadata.xml</code>
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
     * See org.eclipse.aether.internal.impl.SimpleLocalRepositoryManager#getPathForArtifact()
     * @param artifact
     * @param local
     * @return
     */
    private String getPathForArtifact(Artifact artifact, boolean local) {
        StringBuilder path = new StringBuilder(128);

        path.append(artifact.getGroupId().replace('.', '/')).append('/');

        path.append(artifact.getArtifactId()).append('/');

        path.append(artifact.getBaseVersion()).append('/');

        path.append(artifact.getArtifactId()).append('-');
        if (local) {
            path.append(artifact.getBaseVersion());
        } else {
            path.append(artifact.getVersion());
        }

        if (artifact.getClassifier().length() > 0) {
            path.append('-').append(artifact.getClassifier());
        }

        if (artifact.getExtension().length() > 0) {
            path.append('.').append(artifact.getExtension());
        }

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
            // local repository always contain maven-metadata.xml with indication of metadata source
            // ("local" or remote repository's ID)
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

            if (result.isAvailable()
                    && !request.getArtifact().isSnapshot()
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
                                    result.setAvailable(false);
                                    // needed for non SNAPSHOTs.
                                    // If we don't null, PeekTaskRunner will be used instead of GetTaskRunner
                                    result.setFile(null);
                                    result.setRepository(repo);
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
            if (!request.getArtifact().isSnapshot()
                    && (Boolean) session.getConfigProperties().get(PROPERTY_UPDATE_RELEASES)) {
                String path = getPathForArtifact(request.getArtifact(), request.getRepository() == null);
                File artifactFile = new File(getRepository().getBasedir(), path);
                File trackingFile = getTrackingFile(artifactFile);
                String repoId = request.getRepository() == null ? "" : request.getRepository().getId();

                Map<String, String> updates = new HashMap<String, String>();
                updates.put(artifactFile.getName() + ">" + repoId, "");
                trackingFileManager.update(trackingFile, updates);
            }
        }

        private File getTrackingFile(File artifactFile) {
            return new File(artifactFile.getParentFile(), trackingFilename);
        }

    }

}
