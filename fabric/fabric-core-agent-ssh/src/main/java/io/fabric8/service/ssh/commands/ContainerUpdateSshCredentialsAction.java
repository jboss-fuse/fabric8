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
package io.fabric8.service.ssh.commands;

import io.fabric8.api.*;
import io.fabric8.boot.commands.support.AbstractContainerCreateAction;
import io.fabric8.boot.commands.support.ContainerCompleter;
import io.fabric8.boot.commands.support.FabricCommand;
import io.fabric8.commands.AbstractContainerLifecycleAction;
import io.fabric8.service.ssh.CreateSshContainerOptions;
import io.fabric8.utils.FabricValidations;
import io.fabric8.utils.Ports;
import io.fabric8.utils.shell.ShellUtils;
import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

@Command(name = "container-update-ssh-credentials", scope = "fabric", description = "Update credentials for a remote ssh node")
public class ContainerUpdateSshCredentialsAction extends AbstractContainerLifecycleAction {

    protected ContainerUpdateSshCredentialsAction(FabricService fabricService) {
        super(fabricService);
    }

    @Override
    protected Object doExecute() throws Exception {

        for(String name : containers){
            Container found = FabricCommand.getContainerIfExists(fabricService, name);
            if (found != null) {
                applyUpdatedCredentials(found);
            }
        }

        return null;
    }
}
