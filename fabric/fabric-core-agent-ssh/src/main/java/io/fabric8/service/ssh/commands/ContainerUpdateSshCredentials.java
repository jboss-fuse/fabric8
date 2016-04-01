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
package io.fabric8.service.ssh.commands;

import io.fabric8.api.FabricService;
import io.fabric8.api.ZooKeeperClusterService;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.boot.commands.support.*;
import io.fabric8.commands.support.RootContainerCompleter;
import io.fabric8.commands.support.StoppedContainerCompleter;
import io.fabric8.service.ssh.SshContainerCompleter;
import org.apache.felix.gogo.commands.Action;
import org.apache.felix.gogo.commands.basic.AbstractCommand;
import org.apache.felix.scr.annotations.*;
import org.apache.felix.service.command.Function;

@Component(immediate = true)
@Service({ Function.class, AbstractCommand.class })
@org.apache.felix.scr.annotations.Properties({
        @Property(name = "osgi.command.scope", value = ContainerUpdateSshCredentials.SCOPE_VALUE),
        @Property(name = "osgi.command.function", value = ContainerUpdateSshCredentials.FUNCTION_VALUE)
})
public class ContainerUpdateSshCredentials extends AbstractCommandComponent {

    public static final String SCOPE_VALUE = "fabric";
    public static final String FUNCTION_VALUE =  "container-update-ssh-credentials";
    public static final String DESCRIPTION = "Update credentials for the ssh remote container";

    @Reference(referenceInterface = FabricService.class)
    private final ValidatingReference<FabricService> fabricService = new ValidatingReference<FabricService>();

    // Completers
    @Reference(referenceInterface = SshContainerCompleter.class, bind = "bindContainerCompleter", unbind = "unbindContainerCompleter")
    private ContainerCompleter containerCompleter;

    @Activate
    void activate() {
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
    }

    @Override
    public Action createNewAction() {
        assertValid();
        return new ContainerUpdateSshCredentialsAction(fabricService.get());
    }

    void bindFabricService(FabricService fabricService) {
        this.fabricService.bind(fabricService);
    }

    void unbindFabricService(FabricService fabricService) {
        this.fabricService.unbind(fabricService);
    }

    void bindContainerCompleter(SshContainerCompleter completer) {
        bindCompleter(completer);
    }

    void unbindContainerCompleter(SshContainerCompleter completer) {
        unbindCompleter(completer);
    }

}
