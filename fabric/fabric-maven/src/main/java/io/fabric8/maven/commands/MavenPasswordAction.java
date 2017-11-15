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

import java.io.File;
import java.io.IOException;

import io.fabric8.common.util.Strings;
import io.fabric8.utils.shell.ShellUtils;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;
import org.apache.karaf.shell.console.AbstractAction;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.sonatype.plexus.components.cipher.PlexusCipherException;
import org.sonatype.plexus.components.sec.dispatcher.DefaultSecDispatcher;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcherException;
import org.sonatype.plexus.components.sec.dispatcher.SecUtil;
import org.sonatype.plexus.components.sec.dispatcher.model.SettingsSecurity;

@Command(scope = MavenPassword.SCOPE_VALUE, name = MavenPassword.FUNCTION_VALUE, description = MavenPassword.DESCRIPTION)
public class MavenPasswordAction extends AbstractAction {

    @Option(name = "-emp", aliases =  { "--encrypt-master-password" }, description = "Encrypts master Maven password that's used to encrypt server/proxy passwords.")
    boolean emp;

    @Option(name = "-ep", aliases =  { "--encrypt-password" }, description = "Encrypts Maven password that's used as server/proxy passwords. Requires master Maven password in known location.")
    boolean ep;

    @Option(name = "-d", aliases =  { "--decrypt" }, description = "Decrypts master password when displaying summary.")
    boolean decrypt;

    private final MavenPassword command;
    private final ConfigurationAdmin configurationAdmin;

    MavenPasswordAction(MavenPassword command, ConfigurationAdmin cm) {
        this.command = command;
        this.configurationAdmin = cm;
    }

    @Override
    protected Object doExecute() throws Exception {
        if (emp && ep) {
            System.out.println("Only one option for password encryption should be specified.");
            return null;
        }

        if (emp) {
            // ask user for plain text password and output encrypted master Maven password to be used
            // in settings-security.xml file
            String password = fetchPassword("Master Maven password: ", "Verify master Maven password: ");
            if (password == null) {
                return null;
            }

            String encrypted = command.cipher.encryptAndDecorate(password, DefaultSecDispatcher.SYSTEM_PROPERTY_SEC_LOCATION);
            System.out.println("Encrypted master Maven password to use in security-settings.xml: " + encrypted);
            return null;
        }

        // find master Maven password - both for summary and encrypting non-master Maven password
        Configuration[] configs = configurationAdmin.listConfigurations("(|(service.pid=io.fabric8.maven)(service.pid=io.fabric8.agent)(service.pid=org.ops4j.pax.url.mvn))");
        String securitySettingsInMavenConfig = null;
        String securitySettingsInAgentConfig = null;
        String securitySettingsInPaxConfig = null;
        String securitySettingsInImplicitLocation = new File(System.getProperty("user.home"), ".m2/settings-security.xml").getCanonicalPath();

        for (Configuration c : configs) {
            if (c.getProperties() == null) {
                continue;
            }
            if ("io.fabric8.maven".equals(c.getPid())) {
                String security = (String) c.getProperties().get("io.fabric8.maven.security");
                if (!Strings.isEmpty(security)) {
                    securitySettingsInMavenConfig = security.trim();
                }
            } else if ("io.fabric8.agent".equals(c.getPid())) {
                String security = (String) c.getProperties().get("org.ops4j.pax.url.mvn.security");
                if (!Strings.isEmpty(security)) {
                    securitySettingsInAgentConfig = security.trim();
                }
            } else if ("org.ops4j.pax.url.mvn".equals(c.getPid())) {
                String security = (String) c.getProperties().get("org.ops4j.pax.url.mvn.security");
                if (!Strings.isEmpty(security)) {
                    securitySettingsInPaxConfig = security.trim();
                }
            }
        }

        if (ep) {
            String master = findMasterMavenPassword(securitySettingsInMavenConfig, securitySettingsInAgentConfig, securitySettingsInPaxConfig, securitySettingsInImplicitLocation);
            if (master == null) {
                System.out.println("Can't find master Maven password in any of configured and implicit locations. Please configure master Maven password.");
                return null;
            }

            // ask user for plain text password and output encrypted Maven password to be used in settings.xml file
            String password = fetchPassword("Maven password: ", "Verify Maven password: ");
            if (password == null) {
                return null;
            }

            String encrypted = command.cipher.encryptAndDecorate(password, master);
            System.out.println("Encrypted Maven password to use in settings.xml for server and proxy authentication: " + encrypted);
            return null;
        }

        // Just print summary

        if (securitySettingsInMavenConfig != null && securitySettingsInMavenConfig.equals(securitySettingsInAgentConfig)) {
            System.out.println("Maven security configuration in Fabric environment defined in io.fabric8.maven and io.fabric8.agent PID.");
            securityInfo(securitySettingsInAgentConfig);
        } else if (securitySettingsInMavenConfig != null) {
            System.out.println("Maven security configuration in Fabric environment defined in io.fabric8.maven PID.");
            securityInfo(securitySettingsInMavenConfig);
        } else if (securitySettingsInAgentConfig != null) {
            System.out.println("Maven security configuration in Fabric environment defined in io.fabric8.agent PID.");
            securityInfo(securitySettingsInAgentConfig);
        } else if (securitySettingsInPaxConfig != null) {
            System.out.println("Maven security configuration in Standalone environment defined in org.ops4j.pax.url.mvn PID.");
            securityInfo(securitySettingsInPaxConfig);
        } else {
            System.out.println("No explicit Maven security configuration is defined.");
            securityInfo(securitySettingsInImplicitLocation);
        }

        return null;
    }

    private String fetchPassword(String phrase1, String phrase2) throws IOException {
        String password1 = null;
        String password2 = null;
        while (password1 == null || !password1.equals(password2)) {
            password1 = ShellUtils.readLine(session, phrase1, true);
            password2 = ShellUtils.readLine(session, phrase2, true);
            if (password1 == null || password2 == null) {
                break;
            }
            if (password1.equals(password2)) {
                break;
            } else {
                System.out.println("Passwords did not match. Please try again!");
            }
        }
        return password1;
    }

    /**
     * Searches for master Maven password configured in <code>settings-security.xml</code>
     * @param securitySettingsInMavenConfig
     * @param securitySettingsInAgentConfig
     * @param securitySettingsInPaxConfig
     * @param securitySettingsInImplicitLocation
     * @return
     */
    private String findMasterMavenPassword(String securitySettingsInMavenConfig, String securitySettingsInAgentConfig, String securitySettingsInPaxConfig, String securitySettingsInImplicitLocation) throws SecDispatcherException, PlexusCipherException {
        if (command.cipher == null) {
            System.out.println("Can't decrypt Maven master password: " + command.cipherInitializationProblem);
            return null;
        }

        for (String loc : new String[] { securitySettingsInMavenConfig, securitySettingsInAgentConfig, securitySettingsInPaxConfig, securitySettingsInImplicitLocation }) {
            if (loc == null) {
                continue;
            }
            System.out.print("Looking up master Maven password in " + loc + "...");
            if (new File(loc).isFile()) {
                String decrypted = null;
                try {
                    SettingsSecurity settingsSecurity = SecUtil.read(loc, true);
                    decrypted = command.cipher.decryptDecorated(settingsSecurity.getMaster(), DefaultSecDispatcher.SYSTEM_PROPERTY_SEC_LOCATION);
                    System.out.println(" Done!");
                    return decrypted;
                } catch (Exception e) {
                    System.out.println(" Failure! (" + e.getMessage() + ")");
                }
            } else {
                System.out.println(" Not found.");
            }
        }

        return null;
    }

    private void securityInfo(String settings) {
        System.out.println("  Security settings file: " + settings);
        File securityFile = new File(settings);
        if (!securityFile.isFile()) {
            System.out.println("  Can't read security settings file. File is not readable...");
            return;
        }
        try {
            SettingsSecurity settingsSecurity = SecUtil.read(securityFile.getAbsolutePath(), true);
            if (decrypt) {
                if (command.cipher != null) {
                    String decrypted = command.cipher.decryptDecorated(settingsSecurity.getMaster(), DefaultSecDispatcher.SYSTEM_PROPERTY_SEC_LOCATION);
                    System.out.println("  Decrypted Maven master password: " + decrypted);
                } else {
                    System.out.println("  Can't decrypt Maven master password: " + command.cipherInitializationProblem);
                }
            } else {
                System.out.println("  Encrypted Maven master password: " + settingsSecurity.getMaster());
            }
        } catch (Exception e) {
            System.err.println("  Problem reading security settings file: " + e.getMessage());
        }
    }

}
