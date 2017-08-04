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
package io.fabric8.commands;

import io.fabric8.api.Container;
import io.fabric8.api.CreateContainerMetadata;
import io.fabric8.api.DataStore;
import io.fabric8.api.FabricService;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import io.fabric8.commands.support.ContainerGlobSupport;
import io.fabric8.utils.shell.ShellUtils;
import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Option;
import org.apache.karaf.shell.console.AbstractAction;

public abstract class AbstractContainerLifecycleAction extends AbstractAction {

    @Option(name = "--user", description = "The username to use.")
    protected String user;

    @Option(name = "--password", description = "The password to use.")
    protected String password;

    @Option(name = "--prompt", description = "Prompt the password to use. This will also hide it.")
    protected boolean prompt = false;

    @Option(name = "-f", aliases = {"--force"}, multiValued = false, required = false, description = "Force the execution of the command regardless of the known state of the container")
    protected boolean force = false;

    @Argument(index = 0, name = "container", description = "The container names", required = true, multiValued = true)
    protected List<String> containers = null;

    protected final FabricService fabricService;
    protected final DataStore dataStore;

    protected AbstractContainerLifecycleAction(FabricService fabricService) {
        this.fabricService = fabricService;
        this.dataStore = fabricService.adapt(DataStore.class);
    }

    protected void applyUpdatedCredentials(Container container) throws IOException {
        if (user != null && password == null && prompt) {
            password = ShellUtils.readLine(session, "Password for " + user+ "@" + container + ": ", true);
        }
        if (user != null || password != null) {
            CreateContainerMetadata<?> metadata = container.getMetadata();
            if (metadata != null) {
                metadata.updateCredentials(user, password);
                dataStore.setContainerMetadata(container.getMetadata());
            }
        }
    }

    /**
     * <p>Converts a list of possibly wildcard container names into list of available container names.</p>
     * <p>It also checks if the expanded list has at least one element</p>
     */
    protected Collection<String> expandGlobNames(List<String> containerNames) {
        return ContainerGlobSupport.expandGlobNames(fabricService, containerNames);
    }

}
