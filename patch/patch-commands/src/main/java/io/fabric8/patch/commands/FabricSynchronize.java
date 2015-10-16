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
package io.fabric8.patch.commands;

import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.boot.commands.support.AbstractCommandComponent;
import io.fabric8.patch.FabricPatchService;
import org.apache.felix.gogo.commands.Action;
import org.apache.felix.gogo.commands.basic.AbstractCommand;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.service.command.Function;

@Component(immediate = true)
@org.apache.felix.scr.annotations.Service({ Function.class, AbstractCommand.class })
@org.apache.felix.scr.annotations.Properties({
        @Property(name = "osgi.command.scope", value = FabricSynchronize.SCOPE_VALUE),
        @Property(name = "osgi.command.function", value = FabricSynchronize.FUNCTION_VALUE)
})
public class FabricSynchronize extends AbstractCommandComponent {

    public static final String SCOPE_VALUE = "patch";
    public static final String FUNCTION_VALUE = "fabric-synchronize";
    public static final String DESCRIPTION = "Synchronize information about patches to cluster's git server";

    @Reference(referenceInterface = FabricPatchService.class)
    private final ValidatingReference<FabricPatchService> fabricPatchService = new ValidatingReference<FabricPatchService>();

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
        return new FabricSynchronizeAction(fabricPatchService.get());
    }

    void bindFabricPatchService(FabricPatchService service) {
        this.fabricPatchService.bind(service);
    }

    void unbindFabricPatchService(FabricPatchService service) {
        this.fabricPatchService.unbind(service);
    }

}
