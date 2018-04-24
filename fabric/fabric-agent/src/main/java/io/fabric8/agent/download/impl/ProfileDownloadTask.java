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
package io.fabric8.agent.download.impl;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Download from <code>profile:</code> URL - possibly trying several times.
 */
public class ProfileDownloadTask extends SimpleDownloadTask {

    public static Logger LOG = LoggerFactory.getLogger(ProfileDownloadTask.class);

    public ProfileDownloadTask(ScheduledExecutorService executorService, String url, File basePath) {
        super(executorService, url, basePath);
    }

    @Override
    protected File download(Exception previousException) throws Exception {
        try {
            if (scheduleNbRun == 0) {
                LOG.info("Downloading {}", this.url);
            } else {
                LOG.info("Downloading {}, attempt {}", this.url, scheduleNbRun + 1);
            }
            return super.download(previousException);
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("URL [profile:")) {
                // there's tiny chance that new URL(url) was successfull, but url.openStream() - wasn't
                LOG.info("profile: URL handler is not available, will try another attempt.");
                throw new ProfileUrlHandlerNotAvailableException(e.getMessage(), e);
            }
            throw e;
        } catch (IllegalStateException e) {
            // org.apache.felix.framework.URLHandlersStreamHandlerProxy.toExternalForm(java.net.URL, java.lang.Object)
            // specific
            if (e.getMessage() != null && e.getMessage().startsWith("Unknown protocol: profile")) {
                LOG.info("profile: URL handler is not available, will try another attempt.");
                throw new ProfileUrlHandlerNotAvailableException(e.getMessage(), e);
            }
            throw e;
        }
    }

    @Override
    protected Retry isRetryable(IOException e) {
        if (e instanceof ProfileUrlHandlerNotAvailableException) {
            return Retry.DEFAULT_RETRY;
        }
        return Retry.QUICK_RETRY;
    }

    public static class ProfileUrlHandlerNotAvailableException extends IOException {
        public ProfileUrlHandlerNotAvailableException() {
        }

        public ProfileUrlHandlerNotAvailableException(String message) {
            super(message);
        }

        public ProfileUrlHandlerNotAvailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public ProfileUrlHandlerNotAvailableException(Throwable cause) {
            super(cause);
        }
    }

}
