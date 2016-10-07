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
package io.fabric8.agent.download.impl;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;

import io.fabric8.maven.MavenResolver;

public class MavenDownloadTask extends AbstractRetryableDownloadTask {

    private final MavenResolver resolver;

    public MavenDownloadTask(ScheduledExecutorService executor, MavenResolver resolver, String url) {
        super(executor, url);
        this.resolver = resolver;
    }

    @Override
    protected File download(Exception previousException) throws Exception {
        try {
            return resolver.download(url, previousException);
        } catch (NoSuchMethodError error) {
            // handle R patch, where agent is updated, but still wired to old fabric-maven.
            return resolver.download(url);
        }
    }

    /**
     * Maven artifact may be looked up in several repositories. Only if exception for <strong>each</strong>
     * repository is not retryable, we won't retry.
     * @param e
     * @return
     */
    @Override
    protected Retry isRetryable(IOException e) {
        try {
            // convert fabric-maven "retry" to fabric-agent "retry"
            switch (resolver.isRetryableException(e)) {
                case NEVER:
                    return Retry.NO_RETRY;
                case LOW:
                case HIGH:
                    // no need to repeat many times
                    return Retry.QUICK_RETRY;
                case UNKNOWN:
                default:
                    return Retry.DEFAULT_RETRY;
            }
        } catch (NoSuchMethodError error) {
            // handle R patch, where agent is updated, but still wired to old fabric-maven.
            return Retry.DEFAULT_RETRY;
        }
    }

}
