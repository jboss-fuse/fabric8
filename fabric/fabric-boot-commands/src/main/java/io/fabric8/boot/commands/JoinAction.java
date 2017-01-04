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
package io.fabric8.boot.commands;

import static io.fabric8.zookeeper.utils.ZooKeeperUtils.exists;
import io.fabric8.api.Constants;
import io.fabric8.api.ContainerOptions;
import io.fabric8.api.FabricConstants;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.SystemProperties;
import io.fabric8.api.ZkDefs;
import io.fabric8.utils.BundleUtils;
import io.fabric8.utils.FabricValidations;
import io.fabric8.utils.PasswordEncoder;
import io.fabric8.utils.Ports;
import io.fabric8.utils.shell.ShellUtils;
import io.fabric8.zookeeper.ZkPath;
import io.fabric8.zookeeper.bootstrap.BootstrapConfiguration;

import io.fabric8.zookeeper.utils.ZooKeeperUtils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.felix.gogo.commands.Argument;
import org.apache.felix.gogo.commands.Command;
import org.apache.felix.gogo.commands.Option;
import org.apache.felix.utils.properties.Properties;
import org.apache.karaf.shell.console.AbstractAction;
import org.apache.zookeeper.KeeperException;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

@Command(name = "join", scope = "fabric", description = "Join a container to an existing fabric", detailedDescription = "classpath:join.txt")
final class JoinAction extends AbstractAction {

    private static final String FILEINSTALL_FILE_NAME = "felix.fileinstall.filename";

    @Option(name = "-n", aliases = "--non-managed", multiValued = false, description = "Flag to keep the container non managed")
    private boolean nonManaged;

    @Option(name = "-f", aliases = "--force", multiValued = false, description = "Forces the use of container name")
    private boolean force;

    @Option(name = "-p", aliases = "--profile", multiValued = false, description = "Chooses the profile of the container")
    private String profile = "fabric";

    @Option(name = "-v", aliases = "--version", multiValued = false, description = "Chooses the version of the container.")
    private String version = ContainerOptions.DEFAULT_VERSION;

    @Option(name = "--min-port", multiValued = false, description = "The minimum port of the allowed port range")
    private int minimumPort = Ports.MIN_PORT_NUMBER;

    @Option(name = "--max-port", multiValued = false, description = "The maximum port of the allowed port range")
    private int maximumPort = Ports.MAX_PORT_NUMBER;

    @Argument(required = true, index = 0, multiValued = false, description = "Zookeeper URL")
    private String zookeeperUrl;

    @Option(name = "-r", aliases = {"--resolver"}, description = "The resolver policy. Possible values are: localip, localhostname, publicip, publichostname, manualip. Default is localhostname.")
    String resolver;

    @Option(name = "-b", aliases = {"--bind-address"}, description = "The default bind address.")
    String bindAddress;

    @Option(name = "-m", aliases = {"--manual-ip"}, description = "An address to use, when using the manualip resolver.")
    String manualIp;

    @Option(name = "--zookeeper-password", multiValued = false, description = "The ensemble password to use.")
    private String zookeeperPassword;

    @Argument(required = false, index = 1, multiValued = false, description = "Container name to use in fabric. By default a karaf name will be used")
    private String containerName;

    private final ConfigurationAdmin configAdmin;
    private final BundleContext bundleContext;
    private final RuntimeProperties runtimeProperties;

    JoinAction(BundleContext bundleContext, ConfigurationAdmin configAdmin, RuntimeProperties runtimeProperties) {
        this.configAdmin = configAdmin;
        this.bundleContext = bundleContext;
        this.runtimeProperties = runtimeProperties;
    }

    @Override
    protected Object doExecute() throws Exception {
        if (nonManaged) {
            profile = "unmanaged";
        }
        String oldName = runtimeProperties.getRuntimeIdentity();

        if( System.getenv("OPENSHIFT_BROKER_HOST")!=null && containerName!=null ) {
            System.err.println("Containers in OpenShift cannot be renamed");
            return null;
        }

        if (containerName == null) {
            containerName = oldName;
        }

        FabricValidations.validateContainerName(containerName);
        Configuration bootConfiguration = configAdmin.getConfiguration(BootstrapConfiguration.COMPONENT_PID, null);
        Configuration dataStoreConfiguration = configAdmin.getConfiguration(Constants.DATASTORE_PID, null);
        Configuration configZook = configAdmin.getConfiguration(Constants.ZOOKEEPER_CLIENT_PID, null);

        if (configZook.getProperties() != null && configZook.getProperties().get("zookeeper.url") != null) {
           System.err.println("This container is already connected to a fabric");
           return null;
        }

        Dictionary<String, Object> bootProperties = bootConfiguration.getProperties();
        if (bootProperties == null) {
            bootProperties = new Hashtable<>();
        }

        if (resolver != null) {
            bootProperties.put(ZkDefs.LOCAL_RESOLVER_PROPERTY, resolver);
        }

        if (manualIp != null) {
            bootProperties.put(ZkDefs.MANUAL_IP, manualIp);
        }

        if (bindAddress != null) {
            bootProperties.put(ZkDefs.BIND_ADDRESS, bindAddress);
        }

        zookeeperPassword = zookeeperPassword != null ? zookeeperPassword : ShellUtils.retrieveFabricZookeeperPassword(session);

        if (zookeeperPassword == null) {
            zookeeperPassword = promptForZookeeperPassword();
        }

        if (zookeeperPassword == null || zookeeperPassword.isEmpty()) {
            System.out.println("No password specified. Cannot join fabric ensemble.");
            return null;
        }
        ShellUtils.storeZookeeperPassword(session, zookeeperPassword);

        log.debug("Encoding ZooKeeper password.");
        String encodedPassword = PasswordEncoder.encode(zookeeperPassword);

        bootProperties.put(ZkDefs.MINIMUM_PORT, String.valueOf(minimumPort));
        bootProperties.put(ZkDefs.MAXIMUM_PORT, String.valueOf(maximumPort));

        Dictionary<String, Object> dataStoreProperties = new Hashtable<String, Object>();
        augmentDataStoreProperties(zookeeperPassword, dataStoreProperties);


        if (!containerName.equals(oldName)) {
            if (force || permissionToRenameContainer()) {
                if (!registerContainer(containerName, zookeeperPassword, profile, force)) {
                    System.err.println("A container with the name: " + containerName + " is already member of the cluster. You can specify a different name as an argument.");
                    return null;
                }

                bootProperties.put(SystemProperties.KARAF_NAME, containerName);
                //Ensure that if we bootstrap CuratorFramework via RuntimeProperties password is set before the URL.
                bootProperties.put("zookeeper.password", encodedPassword);
                bootProperties.put("zookeeper.url", zookeeperUrl);
                //Rename the container
                Path propsPath = runtimeProperties.getConfPath().resolve("system.properties");
                Properties systemProps = new Properties(propsPath.toFile());
                systemProps.put(SystemProperties.KARAF_NAME, containerName);
                //Also pass zookeeper information so that the container can auto-join after the restart.
                systemProps.put("zookeeper.url", zookeeperUrl);
                systemProps.put("zookeeper.password", encodedPassword);
                systemProps.save();

                System.setProperty("runtime.id", containerName);
                System.setProperty(SystemProperties.KARAF_NAME, containerName);
                System.setProperty("karaf.restart", "true");
                System.setProperty("karaf.restart.clean", "false");

                if (!nonManaged) {
                    installBundles();
                }
                //Restart the container
                bootConfiguration.update(bootProperties);
                dataStoreConfiguration.update(dataStoreProperties);
                persistConfiguration(configAdmin, Constants.DATASTORE_PID, dataStoreProperties );
                bundleContext.getBundle(0).stop();

                return null;
            } else {
                return null;
            }
        } else {
            bootConfiguration.update(bootProperties);
            dataStoreConfiguration.update(dataStoreProperties);
            persistConfiguration(configAdmin, Constants.DATASTORE_PID, dataStoreProperties );
            if (!registerContainer(containerName, zookeeperPassword, profile, force)) {
                System.err.println("A container with the name: " + containerName + " is already member of the cluster. You can specify a different name as an argument.");
                return null;
            }
            Configuration config = configAdmin.getConfiguration(Constants.ZOOKEEPER_CLIENT_PID, null);
            Hashtable<String, Object> properties = new Hashtable<String, Object>();
            properties.put("zookeeper.url", zookeeperUrl);
            properties.put("zookeeper.password", PasswordEncoder.encode(encodedPassword));
            config.setBundleLocation(null);
            config.update(properties);
            if (!nonManaged) {
                installBundles();
            }
            return null;
        }
    }

    private void augmentDataStoreProperties(String registryPassword, Dictionary<String, Object> dataStoreProperties) throws Exception{
        boolean exists = false;
        CuratorFramework curator = null;
        try {

            CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                    .connectString(zookeeperUrl)
                    .retryPolicy(new RetryOneTime(1000))
                    .connectionTimeoutMs(60000);

            if (registryPassword != null && !registryPassword.isEmpty()) {
                builder.authorization("digest", ("fabric:" + registryPassword).getBytes());
            }

            curator = builder.build();
            curator.start();
            curator.getZookeeperClient().blockUntilConnectedOrTimedOut();
            exists = exists(curator, ZkPath.CONFIG_GIT_EXTERNAL_URL.getPath()) != null;
            if (exists) {
                String val = ZooKeeperUtils.getStringData(curator, ZkPath.CONFIG_GIT_EXTERNAL_URL.getPath());
                dataStoreProperties.put("gitRemoteUrl", val);
            }
            exists = exists(curator, ZkPath.CONFIG_GIT_EXTERNAL_USER.getPath()) != null;
            if (exists) {
                String val = ZooKeeperUtils.getStringData(curator, ZkPath.CONFIG_GIT_EXTERNAL_USER.getPath());
                dataStoreProperties.put("gitRemoteUser", val);
            }
            exists = exists(curator, ZkPath.CONFIG_GIT_EXTERNAL_PASSWORD.getPath()) != null;
            if (exists) {
                String val = ZooKeeperUtils.getStringData(curator, ZkPath.CONFIG_GIT_EXTERNAL_PASSWORD.getPath());
                dataStoreProperties.put("gitRemotePassword", val);
            }
        } finally {
            if (curator != null) {
                curator.close();
            }
        }
    }

    private String promptForZookeeperPassword() throws IOException {
        String password = ShellUtils.readLine(session, "Ensemble password: ", true);

        return password;
    }

    /**
     * Checks if there is an existing container using the same name.
     *
     * @param name
     * @return
     * @throws InterruptedException
     * @throws KeeperException
     */
    private boolean registerContainer(String name, String registryPassword, String profile, boolean force) throws Exception {
        boolean exists = false;
        CuratorFramework curator = null;
        try {

            CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                    .connectString(zookeeperUrl)
                    .retryPolicy(new RetryOneTime(1000))
                    .connectionTimeoutMs(60000);

            if (registryPassword != null && !registryPassword.isEmpty()) {
                builder.authorization("digest", ("fabric:" + registryPassword).getBytes());
            }

            curator = builder.build();
            curator.start();
            curator.getZookeeperClient().blockUntilConnectedOrTimedOut();
            exists = exists(curator, ZkPath.CONTAINER.getPath(name)) != null;
            if (!exists || force) {
                ZkPath.createContainerPaths(curator, containerName, version, profile);
            }
        } finally {
            if (curator != null) {
                curator.close();
            }
        }
        return !exists || force;
    }

    /**
     * Asks the users permission to restart the container.
     *
     * @return
     * @throws IOException
     */
    private boolean permissionToRenameContainer() throws IOException {
        System.err.println("You are about to change the container name. This action will restart the container.");
        System.err.println("The local shell will automatically restart, but ssh connections will be terminated.");
        System.err.println("The container will automatically join: " + zookeeperUrl + " the cluster after it restarts.");
        System.err.flush();

        String response = ShellUtils.readLine(session, "Do you wish to proceed (yes/no): ", false);
        return response != null && (response.toLowerCase().equals("yes") || response.toLowerCase().equals("y"));
    }

    public void installBundles() throws BundleException {
        BundleUtils bundleUtils = new BundleUtils(bundleContext);
        Bundle bundleFabricCommands = bundleUtils.findBundle("io.fabric8.fabric-commands");
        if (bundleFabricCommands == null) {
            bundleFabricCommands = bundleUtils.installBundle("mvn:io.fabric8/fabric-commands/" + FabricConstants.FABRIC_VERSION);
        }
        bundleFabricCommands.start();
        Bundle bundleFabricAgent = bundleUtils.findBundle("io.fabric8.fabric-agent");

        if (nonManaged && bundleFabricAgent == null) {
            //do nothing
        } else if (nonManaged && bundleFabricAgent != null) {
            bundleFabricAgent.stop();
        } else if (bundleFabricAgent == null) {
            bundleFabricAgent = bundleUtils.installBundle("mvn:io.fabric8/fabric-agent/" + FabricConstants.FABRIC_VERSION);
            bundleFabricAgent.start();
        } else {
            bundleFabricAgent.start();
        }
    }


    /**
     * Persists configuration to storage.
     * Original code code from org.apache.karaf.shell.config.ConfigCommandSupport#persistConfiguration(org.osgi.service.cm.ConfigurationAdmin, java.lang.String, java.util.Dictionary)
     *
     * @param admin
     * @param pid
     * @param props
     * @throws IOException
     */
    protected void persistConfiguration(ConfigurationAdmin admin, String pid, Dictionary<String, Object> props) throws IOException {
        File storageFile = new File(System.getProperty("karaf.etc"), pid + ".cfg");
        Configuration cfg = admin.getConfiguration(pid, null);
        if (cfg != null && cfg.getProperties() != null) {
            Object val = cfg.getProperties().get(FILEINSTALL_FILE_NAME);
            try {
                if (val instanceof URL) {
                    storageFile = new File(((URL) val).toURI());
                }
                if (val instanceof URI) {
                    storageFile = new File((URI) val);
                }
                if (val instanceof String) {
                    storageFile = new File(new URL((String) val).toURI());
                }
            } catch (Exception e) {
                throw (IOException) new IOException(e.getMessage()).initCause(e);
            }
        }
        Properties p = new Properties(storageFile);
        for (Enumeration<String> keys = props.keys(); keys.hasMoreElements(); ) {
            String key = keys.nextElement();
            if (!org.osgi.framework.Constants.SERVICE_PID.equals(key)
                    && !ConfigurationAdmin.SERVICE_FACTORYPID.equals(key)
                    && !FILEINSTALL_FILE_NAME.equals(key)) {
                if (props.get(key) != null) {
                    p.put(key, props.get(key).toString());
                }
            }
        }
        // remove "removed" properties from the file
        ArrayList<String> propertiesToRemove = new ArrayList<String>();
        for (String key : p.keySet()) {
            if (props.get(key) == null
                    && !org.osgi.framework.Constants.SERVICE_PID.equals(key)
                    && !ConfigurationAdmin.SERVICE_FACTORYPID.equals(key)
                    && !FILEINSTALL_FILE_NAME.equals(key)) {
                propertiesToRemove.add(key);
            }
        }
        for (String key : propertiesToRemove) {
            p.remove(key);
        }
        // save the cfg file
        p.save();
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getZookeeperUrl() {
        return zookeeperUrl;
    }

    public void setZookeeperUrl(String zookeeperUrl) {
        this.zookeeperUrl = zookeeperUrl;
    }

    public boolean isNonManaged() {
        return nonManaged;
    }

    public void setNonManaged(boolean nonManaged) {
        this.nonManaged = nonManaged;
    }

    public boolean isForce() {
        return force;
    }

    public void setForce(boolean force) {
        this.force = force;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getContainerName() {
        return containerName;
    }

    public void setContainerName(String containerName) {
        this.containerName = containerName;
    }

    public String getResolver() {
        return resolver;
    }

    public void setResolver(String resolver) {
        this.resolver = resolver;
    }

    public String getManualIp() {
        return manualIp;
    }

    public void setManualIp(String manualIp) {
        this.manualIp = manualIp;
    }

    public String getZookeeperPassword() {
        return zookeeperPassword;
    }

    public void setZookeeperPassword(String zookeeperPassword) {
        this.zookeeperPassword = zookeeperPassword;
    }
}
