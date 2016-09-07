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
package io.fabric8.groups;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 */
public class NodeState {

    @JsonProperty
    public String id;

    @JsonProperty
    public final String container;

    @JsonProperty
    public String uuid; // internal use to suppress duplicates

    public NodeState() {
        this(null);
    }

    public NodeState(String id) {
        this(id, System.getProperty("runtime.id", UUID.randomUUID().toString()));
    }

    public NodeState(String id, String container) {
        this.id = id;
        this.container = container;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContainer() {
        return container;
    }

    @Override
    public String toString() {
        try {
            return new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).writeValueAsString(this);
        } catch (Exception e) {
            return super.toString();
        }
    }
}
