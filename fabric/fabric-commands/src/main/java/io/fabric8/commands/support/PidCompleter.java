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
package io.fabric8.commands.support;

import java.util.Dictionary;
import java.util.Enumeration;
import java.util.List;

import io.fabric8.api.scr.AbstractComponent;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.karaf.shell.console.Completer;
import org.apache.karaf.shell.console.completer.StringsCompleter;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(immediate = true)
@Service({PidCompleter.class, Completer.class})
public class PidCompleter extends AbstractComponent implements Completer {

    private static final Logger LOGGER = LoggerFactory.getLogger(PidCompleter.class);

    private final StringsCompleter delegate = new StringsCompleter();

    @Reference
    private ConfigurationAdmin configurationAdmin;

    @Activate
    void activate() {
        updateAllPids();
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
    }

    public ConfigurationAdmin getConfigurationAdmin() {
        return configurationAdmin;
    }

    public void setConfigurationAdmin(ConfigurationAdmin configurationAdmin) {
        this.configurationAdmin = configurationAdmin;
    }

    public int complete(final String buffer, final int cursor, final List candidates) {
        int firstPass = delegate.complete(buffer, cursor, candidates);
        if (firstPass < 0) {
            updateAllPids();
            return delegate.complete(buffer, cursor, candidates);
        } else {
            return firstPass;
        }
    }

    /**
     * Updates all Pids.
     */
    private void updateAllPids() {
        try {
            Configuration[] configs = configurationAdmin.listConfigurations(null);
            if (configs != null) {
                for (Configuration config : configs) {
                    String pid = config.getPid();
                    Dictionary dictionary = config.getProperties();
                    if (dictionary != null) {
                        Enumeration keyEnumeration = dictionary.keys();
                        while (keyEnumeration.hasMoreElements()) {
                            String key = (String) keyEnumeration.nextElement();
                            delegate.getStrings().add(pid + "/" + key);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not lookup pids from configuration admin.");
        }
    }

}
