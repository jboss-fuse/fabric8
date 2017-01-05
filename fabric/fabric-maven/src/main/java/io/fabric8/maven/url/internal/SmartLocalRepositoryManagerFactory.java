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

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.internal.impl.SimpleLocalRepositoryManagerFactory;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.LocalMetadataRequest;
import org.eclipse.aether.repository.LocalMetadataResult;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.NoLocalRepositoryManagerException;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Factory that creates {@link LocalRepositoryManager}-like classes that can handle SNAPSHOT
 * metadata specific to remote repositories
 */
public class SmartLocalRepositoryManagerFactory extends SimpleLocalRepositoryManagerFactory {

    @Override
    public LocalRepositoryManager newInstance(RepositorySystemSession session, LocalRepository repository) throws NoLocalRepositoryManagerException {
        LocalRepositoryManager delegate = super.newInstance(session, repository);
        return new SmartLocalRepositoryManager(delegate);
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
     * A {@link LocalRepositoryManager} that can find <code>maven-metadata.xml</code> as if they were stored
     * inside remote repository instead of local repository
     */
    private class SmartLocalRepositoryManager extends LocalRepositoryManagerWrapper {

        public SmartLocalRepositoryManager(LocalRepositoryManager delegate) {
            super(delegate);
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

    }

}
