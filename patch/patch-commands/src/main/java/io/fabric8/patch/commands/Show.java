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
import io.fabric8.patch.Service;
import io.fabric8.patch.commands.support.PatchCompleter;
import io.fabric8.patch.commands.support.UninstallPatchCompleter;
import io.fabric8.patch.management.PatchManagement;
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
        @Property(name = "osgi.command.scope", value = Show.SCOPE_VALUE),
        @Property(name = "osgi.command.function", value = Show.FUNCTION_VALUE)
})
public class Show extends AbstractCommandComponent {

    public static final String SCOPE_VALUE = "patch";
    public static final String FUNCTION_VALUE = "show";
    public static final String DESCRIPTION = "Shows the content of a patch in different formats";

    @Reference(referenceInterface = Service.class)
    private final ValidatingReference<Service> service = new ValidatingReference<Service>();

    @Reference(referenceInterface = PatchManagement.class)
    private final ValidatingReference<PatchManagement> patchManagement = new ValidatingReference<PatchManagement>();

    // Completers
    @Reference(referenceInterface = PatchCompleter.class, bind = "bindPatchCompleter", unbind = "unbindPatchCompleter")
    private PatchCompleter uninstallCompleter; // dummy field

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
        return new ShowAction(service.get(), patchManagement.get());
    }

    void bindService(Service service) {
        this.service.bind(service);
    }

    void unbindService(Service service) {
        this.service.unbind(service);
    }

    void bindPatchManagement(PatchManagement patchManagement) {
        this.patchManagement.bind(patchManagement);
    }

    void unbindPatchManagement(PatchManagement patchManagement) {
        this.patchManagement.unbind(patchManagement);
    }

    void bindPatchCompleter(PatchCompleter completer) {
        bindCompleter(completer);
    }

    void unbindPatchCompleter(PatchCompleter completer) {
        unbindCompleter(completer);
    }

}
