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
package io.fabric8.maven;

import io.fabric8.maven.url.internal.wagon.ConfigurableHttpWagon;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.providers.file.FileWagon;
import org.apache.maven.wagon.providers.http.HttpWagon;
import org.eclipse.aether.transport.wagon.WagonProvider;

public class StaticWagonProvider implements WagonProvider {

    private CloseableHttpClient client;
    private int readTimeout;
    private int connectionTimeout;

    public StaticWagonProvider(CloseableHttpClient client, int readTimeout) {
        this(client, readTimeout, readTimeout);
    }

    public StaticWagonProvider(CloseableHttpClient client, int readTimeout, int connectionTimeout) {
        this.client = client;
        this.readTimeout = readTimeout;
        this.connectionTimeout = connectionTimeout;
    }

    public Wagon lookup(String roleHint) throws Exception {
        if ("file".equals(roleHint)) {
            return new FileWagon();
        }
        if ("http".equals(roleHint) || "https".equals(roleHint)) {
            return new ConfigurableHttpWagon(client, readTimeout, connectionTimeout);
        }
        return null;
    }

    public void release(Wagon wagon) {
    }

}
