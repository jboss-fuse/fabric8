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
package io.fabric8.api.commands;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Instruction for a container to perform specific action on JMX object (currently no-arg)
 */
public class JMXRequest {

    @JsonProperty
    private String id = UUID.randomUUID().toString();
    @JsonProperty
    private String objectName;
    @JsonProperty
    private String method;
    @JsonProperty
    private List<String[]> params = new LinkedList<>();

    /**
     * Timestamp of request creation
     */
    @JsonProperty
    private long timestamp = new Date().getTime();

    /**
     * How long should the execution of the command be delayed?
     */
    @JsonProperty
    private int delay = 0;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getObjectName() {
        return objectName;
    }

    public JMXRequest withObjectName(String objectName) {
        this.objectName = objectName;
        return this;
    }

    public String getMethod() {
        return method;
    }

    public JMXRequest withMethod(String method) {
        this.method = method;
        return this;
    }

    public JMXRequest withParam(Class<?> type, Object value) {
        params.add(new String[] { type.getName(), value.toString() });
        return this;
    }

    public List<String[]> getParams() {
        return params;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public JMXRequest withDelay(int delay) {
        this.delay = delay;
        return this;
    }

    public int getDelay() {
        return delay;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s.%s()%s", id, objectName, method, delay > 0L ? " (random delay: 0-" + delay + "s)" : "");
    }

}
