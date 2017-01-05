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

import java.util.Collection;
import java.util.List;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.internal.impl.DefaultMetadataResolver;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.MetadataRequest;
import org.eclipse.aether.resolution.MetadataResult;

/**
 * {@link org.eclipse.aether.impl.MetadataResolver} that can resolve "remote" snapshot metadata using local repository
 */
public class SmartMetadataResolver extends DefaultMetadataResolver {

    public static final String DEFAULT_REPOSITORY = "defaultRepository";

    @Override
    public List<MetadataResult> resolveMetadata(RepositorySystemSession session, Collection<? extends MetadataRequest> requests) {
        List<MetadataResult> results = super.resolveMetadata(session, requests);

        if (session.getData().get(DEFAULT_REPOSITORY) instanceof RemoteRepository) {
            for (MetadataResult mr : results) {
                if (mr.isResolved() && mr.getMetadata().getFile().getName().equals("maven-metadata.xml")) {
                    // trick org.eclipse.aether.impl.VersionResolver so it accepts locally resolved
                    // maven-metadata.xml file even if it contains remote information,
                    // i.e., list of specific SNAPSHOT versions, like
                    // "0.1.0.BUILD-SNAPSHOT" -> "0.1.0.BUILD-20170105.081846-2"
                    mr.getRequest().setRepository((RemoteRepository) session.getData().get(DEFAULT_REPOSITORY));
                }
            }
        }

        return results;
    }
}
