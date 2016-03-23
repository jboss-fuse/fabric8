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
package io.fabric8.service.ssh;

import io.fabric8.api.Container;
import io.fabric8.api.ContainerProvider;
import io.fabric8.api.CreateContainerMetadata;
import io.fabric8.api.FabricService;
import io.fabric8.boot.commands.support.AbstractContainerCompleter;
import io.fabric8.service.FabricServiceImpl;
import org.apache.felix.scr.annotations.*;
import org.apache.karaf.shell.console.Completer;

@Component(immediate = true)
@Service({ SshContainerCompleter.class, Completer.class })
public final class SshContainerCompleter extends AbstractContainerCompleter {

    @Reference
    private FabricService fabricService;

    @Activate
    void activate() {
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
    }

    @Override
    public FabricService getFabricService() {
        return fabricService;
    }

    public void setFabricService(FabricService fabricService) {
        this.fabricService = fabricService;
    }

    @Override
    public boolean apply(Container container) {
        ContainerProvider provider = getProvider(container);
        if(provider == null)
            return false;
        else
            return "ssh".equals(provider.getScheme());
    }

    private ContainerProvider getProvider(Container container) {
        CreateContainerMetadata metadata = container.getMetadata();
        String type = metadata != null ? metadata.getCreateOptions().getProviderType() : null;
        if (type == null) {
                return null;
        }
        ContainerProvider provider = fabricService.getProvider(type);
        if (provider == null) {
                return null;
        }
        return provider;
    }
}
