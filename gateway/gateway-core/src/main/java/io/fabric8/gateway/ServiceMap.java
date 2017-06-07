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
package io.fabric8.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Maintains a mapping of services which is then use by the proxy to update in process
 * proxy handlers, or used to create new proxy handers
 */
public class ServiceMap {
    private Logger LOG = LoggerFactory.getLogger(ServiceMap.class);
    private final ConcurrentMap<String, PathMap> map = new ConcurrentHashMap<>();

    /**
     * Returns a list of all the current services for the given path
     */
    public List<ServiceDetails> getServices(String path) {
        return getPathMap(path).getServices();
    }

    /**
     * Returns a list of all the current paths for the services
     */
    public List<String> getPaths() {
        return new ArrayList<>(map.keySet());
    }

    /**
     * When a service is added or updated
     */
    public void serviceUpdated(String path, ServiceDetails service) {
        // ignore services with empty services
        if (!service.getServices().isEmpty()) {
            getPathMap(path).update(service);
        }
        logCurrentConfiguration();
    }

    /**
     * When a service is removed
     */
    public void serviceRemoved(String path, ServiceDetails service) {
        getPathMap(path).remove(service);
        logCurrentConfiguration();
        // lets update any in progress proxy handlers using this service
    }

    protected PathMap getPathMap(String path) {
        PathMap initial = new PathMap(path);
        PathMap answer = map.putIfAbsent(path, initial);
        if (answer == null) {
            answer = initial;
        }
        return answer;
    }

    public void logCurrentConfiguration(){
        if(LOG.isTraceEnabled()){
            StringBuilder output = new StringBuilder();
            for(Map.Entry<String, PathMap> e : map.entrySet()){
                String service = e.getKey().toString();
                output.append("Service: " + service );
                output.append(getServices(service));
            }
            LOG.trace(output.toString());
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + ": " + map.toString();
    }

    protected static class PathMap {

        private final String path;
        /**
         * Map: Service ID -> Container -> ServiceDetails
         */
        private final ConcurrentMap<String, Map<String, ServiceDetails>> pathMap = new ConcurrentHashMap<>();

        public PathMap(String path) {
            this.path = path;
        }

        public List<ServiceDetails> getServices() {
            List<ServiceDetails> answer = new ArrayList<>();
            for (Map<String, ServiceDetails> containerMap : pathMap.values()) {
                answer.addAll(containerMap.values());
            }
            return answer;
        }

        public void update(ServiceDetails service) {
            Map<String, ServiceDetails> initial = new ConcurrentHashMap<>();
            Map<String, ServiceDetails> containerMap = pathMap.putIfAbsent(service.getId(), initial);
            if (containerMap == null) {
                containerMap = initial;
            }
            containerMap.put(service.getContainer(), service);
        }

        public void remove(ServiceDetails service) {
            Map<String, ServiceDetails> containerMap = pathMap.get(service.getId());
            if (containerMap != null) {
                containerMap.remove(service.getContainer());
            }
        }



        @Override
        public String toString() {
            return getClass().getSimpleName() + ": " + pathMap.toString();
        }
    }
}
