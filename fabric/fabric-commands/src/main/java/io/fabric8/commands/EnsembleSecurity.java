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

import java.util.Map;

import io.fabric8.api.FabricService;
import io.fabric8.api.ProfileService;
import io.fabric8.api.ZooKeeperClusterService;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.boot.commands.support.AbstractCommandComponent;
import org.apache.felix.gogo.commands.Action;
import org.apache.felix.gogo.commands.basic.AbstractCommand;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.felix.service.command.Function;

@Component(immediate = true)
@Service({ Function.class, AbstractCommand.class })
@org.apache.felix.scr.annotations.Properties({
        @Property(name = "osgi.command.scope", value = EnsembleSecurity.SCOPE_VALUE),
        @Property(name = "osgi.command.function", value = EnsembleSecurity.FUNCTION_VALUE)
})
public class EnsembleSecurity extends AbstractCommandComponent {

    public static final String SCOPE_VALUE = "fabric";
    public static final String FUNCTION_VALUE = "ensemble-security";
    public static final String DESCRIPTION = "Display or update ensemble security configuration";

    @Reference(referenceInterface = FabricService.class)
    private final ValidatingReference<FabricService> fabricService = new ValidatingReference<FabricService>();

    @Reference(referenceInterface = ProfileService.class)
    private final ValidatingReference<ProfileService> profileService = new ValidatingReference<ProfileService>();

    @Reference(referenceInterface = ZooKeeperClusterService.class)
    private final ValidatingReference<ZooKeeperClusterService> clusterService = new ValidatingReference<ZooKeeperClusterService>();

    public static EnsembleSASL isSASLEnabled(Map<String, String> configuration) {
        EnsembleSASL result = EnsembleSASL.NO_QUORUM;
        if (configuration != null) {
            String enabledInConfiguaration = configuration.get("quorum.auth.enableSasl");
            if ("true".equalsIgnoreCase(enabledInConfiguaration)) {
                result = EnsembleSASL.ENABLED;
            } else if ("false".equalsIgnoreCase(enabledInConfiguaration)) {
                result = EnsembleSASL.DISABLED;
            } else {
                // no such configuration (yet)
                if (configuration.containsKey("server.1")) {
                    // but we're in quorum mode, not single-server mode
                    result = EnsembleSASL.DISABLED;
                }
            }
        }

        return result;
    }

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
        return new EnsembleSecurityAction(fabricService.get(), clusterService.get(), profileService.get());
    }

    void bindClusterService(ZooKeeperClusterService clusterService) {
        this.clusterService.bind(clusterService);
    }

    void unbindClusterService(ZooKeeperClusterService clusterService) {
        this.clusterService.unbind(clusterService);
    }

    void bindProfileService(ProfileService profileService) {
        this.profileService.bind(profileService);
    }

    void unbindProfileService(ProfileService profileService) {
        this.profileService.unbind(profileService);
    }

    void bindFabricService(FabricService fabricService) {
        this.fabricService.bind(fabricService);
    }

    void unbindFabricService(FabricService fabricService) {
        this.fabricService.unbind(fabricService);
    }

    public enum EnsembleSASL {
        ENABLED, DISABLED, NO_QUORUM
    }

}
