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
package io.fabric8.git.http;

import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.api.commands.GitVersion;
import io.fabric8.api.commands.GitVersions;
import io.fabric8.git.internal.GitHelpers;
import io.fabric8.git.jmx.GitHttpEndpointMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitHttpEndpoint implements GitHttpEndpointMBean {

    public static Logger LOG = LoggerFactory.getLogger(GitHttpEndpoint.class);

    private GitHttpServerRegistrationHandler handler;

    public GitHttpEndpoint() {
    }

    public GitHttpEndpoint(GitHttpServerRegistrationHandler handler) {
        this.handler = handler;
    }

    @Override
    public String gitVersions() {
        // similar to io.fabric8.api.jmx.FabricManagerMBean.gitVersions(), but used to get version information
        // from fabric-git-servlet (only if it's master)
        if (!handler.isMaster.get()) {
            return null;
        }
        try {
            List<GitVersion> gitVersions = GitHelpers.gitVersions(handler.git);
            return getObjectMapper().writeValueAsString(new GitVersions(gitVersions));
        } catch (Exception e) {
            LOG.warn(e.getMessage(), e);
        }
        return null;
    }

    private ObjectMapper getObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        return mapper;
    }

}
