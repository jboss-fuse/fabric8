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
package io.fabric8.core.jmx;

import java.io.IOException;
import java.util.Arrays;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.api.commands.GitVersion;
import io.fabric8.api.commands.GitVersions;
import io.fabric8.api.commands.JMXRequest;
import io.fabric8.api.commands.JMXResult;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNotNull;

public class FabricManagerTest {

    @Test
    public void toJson() throws JsonProcessingException {
        JMXRequest r = new JMXRequest();
        r.setId("id");
        r.withObjectName("io.fabric8:type=manager");
        System.out.println(om().writeValueAsString(r));

        JMXResult res = new JMXResult();
        res.setCode(0);
        res.setMessage("OK");
        res.setCorrelationId("id");
        GitVersion v = new GitVersion("1.0");
        v.setSha1("SHA1");
        GitVersions versions = new GitVersions();
        versions.getVersions().addAll(Arrays.asList(v, v, v));
        res.setResponse(versions);
        System.out.println(om().writeValueAsString(res));
        System.out.println(om().writeValueAsString(versions));
    }

    @Test
    public void fromJson() throws IOException {
        JMXResult res = om().readValue("{\"correlationId\":\"id\",\"code\":0,\"message\":\"OK\",\"response\":{\"@class\":\"io.fabric8.api.commands.GitVersions\",\"versions\":[{\"version\":\"1.0\",\"sha1\":\"SHA1\",\"timestamp\":null,\"message\":null},{\"version\":\"1.0\",\"sha1\":\"SHA1\",\"timestamp\":null,\"message\":null},{\"version\":\"1.0\",\"sha1\":\"SHA1\",\"timestamp\":null,\"message\":null}]}}",
                JMXResult.class);
        assertNotNull(res);
        assertThat(res.getMessage(), equalTo("OK"));
        assertThat(((GitVersions)res.getResponse()).getVersions().get(2).getSha1(), equalTo("SHA1"));
        assertThat(((GitVersions) res.getResponse()).getVersions().size(), equalTo(3));
    }

    private ObjectMapper om() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        return mapper;
    }

}
