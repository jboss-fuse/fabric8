/**
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
package io.fabric8.maven.commands;

import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.boot.commands.support.AbstractCommandComponent;
import org.apache.felix.gogo.commands.Action;
import org.apache.felix.gogo.commands.basic.AbstractCommand;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.service.command.Function;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.plexus.components.cipher.DefaultPlexusCipher;
import org.sonatype.plexus.components.cipher.PlexusCipherException;

@Component(immediate = true)
@org.apache.felix.scr.annotations.Service({ Function.class, AbstractCommand.class })
@org.apache.felix.scr.annotations.Properties({
        @Property(name = "osgi.command.scope", value = MavenPassword.SCOPE_VALUE),
        @Property(name = "osgi.command.function", value = MavenPassword.FUNCTION_VALUE)
})
public class MavenPassword extends AbstractCommandComponent {

    public static Logger LOG = LoggerFactory.getLogger(MavenPassword.class);

    public static final String SCOPE_VALUE = "fabric";
    public static final String FUNCTION_VALUE = "maven-password";
    public static final String DESCRIPTION = "Helps with configuration and encryption of Maven passwords. Works both in fabric and standalone mode.";

    @Reference(referenceInterface = ConfigurationAdmin.class)
    private final ValidatingReference<ConfigurationAdmin> configurationAdmin = new ValidatingReference<>();

    DefaultPlexusCipher cipher;
    String cipherInitializationProblem;

    public MavenPassword() {
        try {
            cipher = new DefaultPlexusCipher();
        } catch (PlexusCipherException e) {
            cipherInitializationProblem = e.getMessage();
            LOG.warn("Can't initialize Maven Plexus cipher: " + cipherInitializationProblem, e);
        }
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
        return new MavenPasswordAction(this, configurationAdmin.get());
    }

    void bindConfigurationAdmin(ConfigurationAdmin service) {
        this.configurationAdmin.bind(service);
    }

    void unbindConfigurationAdmin(ConfigurationAdmin service) {
        this.configurationAdmin.unbind(service);
    }

}
