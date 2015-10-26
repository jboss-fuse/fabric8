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
package io.fabric8.patch.management.io;

import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;

public class NtfsAwareCheckoutCommand extends CheckoutCommand {

    /**
     * @param repo
     */
    public NtfsAwareCheckoutCommand(Repository repo) {
        super(repo);
    }

    @Override
    public Ref call() throws GitAPIException {
        JGitInternalException lastException = null;
        for (int i=0; i<5; i++) {
            try {
                return super.call();
            } catch (JGitInternalException e) {
                if (!e.getMessage().toLowerCase().contains("Could not rename file")) {
                    throw e;
                } else {
                    lastException = e;
                }
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (lastException != null) {
            throw lastException;
        } else {
            return null;
        }
    }

}
