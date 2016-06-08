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
package io.fabric8.service.child;

import io.fabric8.api.*;
import io.fabric8.api.jcip.ThreadSafe;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.common.util.Strings;
import io.fabric8.internal.ContainerImpl;
import io.fabric8.internal.Objects;
import io.fabric8.service.ContainerTemplate;
import io.fabric8.utils.AuthenticationUtils;
import io.fabric8.utils.Ports;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;
import org.apache.karaf.admin.management.AdminServiceMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.TabularData;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static io.fabric8.utils.Ports.mapPortToRange;

@ThreadSafe
@Component(name = "io.fabric8.container.provider.child", label = "Fabric8 Child Container Provider", immediate = true, metatype = false)
@Service({ ContainerProvider.class, ChildContainerProvider.class })
@Properties(
        @Property(name = "fabric.container.protocol", value = ChildContainerProvider.SCHEME)
)
public final class ChildContainerProvider extends AbstractComponent implements ContainerProvider<CreateChildContainerOptions, CreateChildContainerMetadata>, ContainerAutoScalerFactory {
    private static final transient Logger LOG = LoggerFactory.getLogger(ChildContainerProvider.class);

    static final String SCHEME = "child";

    @Reference(referenceInterface = FabricService.class)
    private final ValidatingReference<FabricService> fabricService = new ValidatingReference<>();
    // [TODO] #1916 Migrate process-manager to SCR
    @Reference(referenceInterface = ProcessControllerFactory.class, cardinality = ReferenceCardinality.OPTIONAL_UNARY, policy = ReferencePolicy.DYNAMIC, bind = "bindProcessControllerFactory", unbind = "unbindProcessControllerFactory")
    private final ValidatingReference<ProcessControllerFactory> processControllerFactory = new ValidatingReference<>();

    @Activate
    void activate() {
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
    }


    @Override
    public CreateChildContainerOptions.Builder newBuilder() {
        assertValid();
        return CreateChildContainerOptions.builder();
    }

    @Override
    public CreateChildContainerMetadata create(final CreateChildContainerOptions options, final CreationStateListener listener) throws Exception {
        assertValid();
        ChildContainerController controller = createController(options);
        return controller.create(options, listener);
    }

    @Override
    public void start(final Container container) {
        assertValid();
        getContainerController(container).start(container);
    }

    @Override
    public void stop(final Container container) {
        assertValid();
        getContainerController(container).stop(container);
    }

    @Override
    public void destroy(final Container container) {
        assertValid();
        container.setProvisionResult(Container.PROVISION_DELETING);
        getContainerController(container).destroy(container);
    }

    @Override
    public String getScheme() {
        return SCHEME;
    }

    @Override
    public Class<CreateChildContainerOptions> getOptionsType() {
        assertValid();
        return CreateChildContainerOptions.class;
    }

    @Override
    public Class<CreateChildContainerMetadata> getMetadataType() {
        assertValid();
        return CreateChildContainerMetadata.class;
    }

    @Override
    public boolean isValidProvider() {
        // child provider isn't valid in OpenShift environment
        FabricService service = getFabricService();
        if (service != null) {
            // lets disable child if in docker or openshift environments
            String environment = service.getEnvironment();
            if (Objects.equal(environment, "docker") || Objects.equal(environment, "openshift") || Objects.equal(environment, "kubernetes")) {
                return false;
            }
        }
        boolean openshiftFuseEnv = Strings.notEmpty(System.getenv("OPENSHIFT_FUSE_DIR"));
        boolean openshiftAmqEnv = Strings.notEmpty(System.getenv("OPENSHIFT_AMQ_DIR"));
        return !(openshiftFuseEnv || openshiftAmqEnv);
    }

    @Override
    public ContainerAutoScaler createAutoScaler(FabricRequirements requirements, ProfileRequirements profileRequirements) {
        assertValid();
        return new ChildAutoScaler(this);
    }

    private ChildContainerController createController(CreateChildContainerOptions options) throws Exception {
        ChildContainerController answer = null;
        boolean isJavaContainer = ChildContainers.isJavaContainer(getFabricService(), options);
        boolean isProcessContainer = ChildContainers.isProcessContainer(getFabricService(), options);
        ProcessControllerFactory factory = processControllerFactory.getOptional();
        if (factory != null) {
            answer = factory.createController(options);
        } else if (isJavaContainer || isProcessContainer) {
            throw new Exception("No ProcessControllerFactory is available to create a ProcessManager based child container");
        }
        if (answer == null) {
            answer = createKarafContainerController();
        }
        LOG.info("Using container controller " + answer);
        return answer;
    }

    private ChildContainerController getContainerController(Container container) {
        assertValid();
        ChildContainerController answer = null;
        try {
            ProcessControllerFactory factory = processControllerFactory.getOptional();
            if (factory != null) {
                answer = factory.getControllerForContainer(container);
            }
        } catch (Exception e) {
            LOG.warn("Caught: " + e, e);
        }
        if (answer == null) {
            // lets assume if there is no installation then we are a basic karaf container
            answer = createKarafContainerController();
        }
        LOG.info("Using container controller " + answer);
        return answer;
    }

    private ChildContainerController createKarafContainerController() {
        return new ChildContainerController() {
            @Override
            public CreateChildContainerMetadata create(final CreateChildContainerOptions options, final CreationStateListener listener) {
                final Container parent = fabricService.get().getContainer(options.getParent());
                ContainerTemplate containerTemplate = new ContainerTemplate(parent, options.getJmxUser(), options.getJmxPassword(), false);

                return containerTemplate.execute(new ContainerTemplate.AdminServiceCallback<CreateChildContainerMetadata>() {
                    public CreateChildContainerMetadata doWithAdminService(AdminServiceMBean adminService) throws Exception {
                        return doCreateKaraf(adminService, options, listener, parent);
                    }
                });
            }

            @Override
            public void start(final Container container) {
                getContainerTemplateForChild(container).execute(new ContainerTemplate.AdminServiceCallback<Object>() {
                    public Object doWithAdminService(AdminServiceMBean adminService) throws Exception {
                        //update jvm options if they have have changed
                        CreateChildContainerMetadata metadata = (CreateChildContainerMetadata) container.getMetadata();
                        CreateChildContainerOptions createOptions = metadata.getCreateOptions();
                        String jvmOpts = createOptions.getJvmOpts();
                        TabularData instances = adminService.getInstances();
                        Collection<CompositeDataSupport> values = (Collection<CompositeDataSupport>) instances.values();
                        for(CompositeDataSupport o : values){
                            if(container.getId().equals(o.get("Name"))){
                                if(o.containsKey("JavaOpts")){
                                    String oldJavaOpts = (String) o.get("JavaOpts");
                                    StringBuilder stringBuilder = ChildContainerProvider.buildJvmOpts(createOptions);
                                    String extendendJvmOpts = stringBuilder.toString();
                                    if (jvmOpts != null && !extendendJvmOpts.equals(oldJavaOpts)){
                                        adminService.changeJavaOpts(container.getId(), extendendJvmOpts);
                                    }
                                }
                                break;
                            }
                        }

                        adminService.startInstance(container.getId(), null);
                        return null;
                    }
                });
            }

            @Override
            public void stop(final Container container) {
                getContainerTemplateForChild(container).execute(new ContainerTemplate.AdminServiceCallback<Object>() {
                    public Object doWithAdminService(AdminServiceMBean adminService) throws Exception {
                        String prevProvisionResult = container.getProvisionResult();
                        container.setProvisionResult(Container.PROVISION_STOPPING);
                        try {
                            adminService.stopInstance(container.getId());
                            container.setProvisionResult(Container.PROVISION_STOPPED);
                        } catch (Throwable t){
                            container.setProvisionResult(prevProvisionResult);
                            LOG.error("Failed to stop container: " + container.getId(), t);
                            throw t;
                        }
                        return null;
                    }
                });
            }

            @Override
            public void destroy(final Container container) {
                getContainerTemplateForChild(container).execute(new ContainerTemplate.AdminServiceCallback<Object>() {
                    public Object doWithAdminService(AdminServiceMBean adminService) throws Exception {
                        adminService.destroyInstance(container.getId());
                        return null;
                    }
                });
            }
        };
    }


    private CreateChildContainerMetadata doCreateKaraf(AdminServiceMBean adminService,
                                                       CreateChildContainerOptions options,
                                                       CreationStateListener listener,
                                                       final Container parent) throws Exception {
        StringBuilder jvmOptsBuilder = ChildContainerProvider.buildJvmOpts(options);

        DataStore dataStore = fabricService.get().adapt(DataStore.class);
        ProfileService profileService = fabricService.get().adapt(ProfileService.class);

        Profile profile = parent.getVersion().getRequiredProfile("default");
        Profile effectiveProfile = Profiles.getEffectiveProfile(fabricService.get(), profileService.getOverlayProfile(profile));
        String featuresUrls = collectionAsString(effectiveProfile.getRepositories());
        Set<String> features = new LinkedHashSet<String>();

        features.add("fabric-core");
        //features.addAll(defaultProfile.getFeatures());
        String containerName = options.getName();

        PortService portService = fabricService.get().getPortService();
        Set<Integer> usedPorts = portService.findUsedPortByHost(parent);

        CreateChildContainerMetadata metadata = new CreateChildContainerMetadata();

        metadata.setCreateOptions(options);
        metadata.setContainerName(containerName);
        int minimumPort = parent.getMinimumPort();
        int maximumPort = parent.getMaximumPort();

        dataStore.setContainerAttribute(containerName, DataStore.ContainerAttribute.PortMin, String.valueOf(minimumPort));
        dataStore.setContainerAttribute(containerName, DataStore.ContainerAttribute.PortMax, String.valueOf(maximumPort));
        inheritAddresses(fabricService.get(), parent.getId(), containerName, options);

        //We are creating a container instance, just for the needs of port registration.
        Container child = new ContainerImpl(parent, containerName, fabricService.get()) {
            @Override
            public String getIp() {
                return parent.getIp();
            }
        };

        int sshFrom = mapPortToRange(Ports.DEFAULT_KARAF_SSH_PORT, minimumPort, maximumPort);
        int sshTo = mapPortToRange(Ports.DEFAULT_KARAF_SSH_PORT + 100, minimumPort, maximumPort);
        int sshPort = -1;
        try {
            // we first try to find a port within a range around the original port
            sshPort = portService.registerPort(child, "org.apache.karaf.shell", "sshPort", Math.min(sshFrom, sshTo), Math.max(sshFrom, sshTo), usedPorts);
        } catch(FabricException e){
            // if we failed to do so, we try to find a port in any position within the user specified range
            sshPort = portService.registerPort(child, "org.apache.karaf.shell", "sshPort", minimumPort, maximumPort, usedPorts);
        }

        int httpFrom = mapPortToRange(Ports.DEFAULT_HTTP_PORT, minimumPort, maximumPort);
        int httpTo = mapPortToRange(Ports.DEFAULT_HTTP_PORT + 100, minimumPort, maximumPort);

        try {
            // we first try to find a port within a range around the original port
            portService.registerPort(child, "org.ops4j.pax.web", "org.osgi.service.http.port", Math.min(httpFrom, httpTo), Math.max(httpFrom, httpTo), usedPorts);
        } catch(FabricException e){
            // if we failed to do so, we try to find a port in any position within the user specified range
            portService.registerPort(child, "org.ops4j.pax.web", "org.osgi.service.http.port", minimumPort, maximumPort, usedPorts);
        }

        int rmiServerFrom = mapPortToRange(Ports.DEFAULT_RMI_SERVER_PORT, minimumPort, maximumPort);
        int rmiServerTo = mapPortToRange(Ports.DEFAULT_RMI_SERVER_PORT + 100, minimumPort, maximumPort);
        int rmiServerPort = -1;
        try {
            rmiServerPort = portService.registerPort(child, "org.apache.karaf.management", "rmiServerPort", Math.min(rmiServerFrom, rmiServerTo), Math.max(rmiServerFrom, rmiServerTo), usedPorts);
        } catch(FabricException e){
            // if we failed to do so, we try to find a port in any position within the user specified range
            rmiServerPort = portService.registerPort(child, "org.apache.karaf.management", "rmiServerPort", minimumPort, maximumPort, usedPorts);
        }

        int rmiRegistryFrom = mapPortToRange(Ports.DEFAULT_RMI_REGISTRY_PORT, minimumPort, maximumPort);
        int rmiRegistryTo = mapPortToRange(Ports.DEFAULT_RMI_REGISTRY_PORT + 100, minimumPort, maximumPort);
        int rmiRegistryPort = -1;
        try {
            rmiRegistryPort = portService.registerPort(child, "org.apache.karaf.management", "rmiRegistryPort", Math.min(rmiRegistryFrom, rmiRegistryTo), Math.max(rmiRegistryFrom, rmiRegistryTo), usedPorts);
        } catch(FabricException e){
            // if we failed to do so, we try to find a port in any position within the user specified range
            rmiRegistryPort = portService.registerPort(child, "org.apache.karaf.management", "rmiRegistryPort", minimumPort, maximumPort, usedPorts);
        }
        try {
            adminService.createInstance(containerName,
                    sshPort,
                    rmiRegistryPort,
                    rmiServerPort, null, jvmOptsBuilder.toString(), collectionAsString(features), featuresUrls);

            // copy some properties from root to child before starting it
            File rootBase = new File(System.getProperty("karaf.base"));
            File childBase = new File(System.getProperty("karaf.instances"), containerName);
            copyProperties(rootBase, childBase, "etc/org.apache.karaf.management.cfg",
                    "rmiRegistryPort",
                    "rmiRegistryHost",
                    "rmiServerPort",
                    "rmiServerHost",
                    "serviceUrl");
            copyProperties(rootBase, childBase, "etc/org.ops4j.pax.url.mvn.cfg");

            adminService.startInstance(containerName, null);
        } catch (Throwable t) {
            metadata.setFailure(t);
        }
        return metadata;
    }

    /**
     * Copies all properties from <code>source</code> to <code>destination</code> except those specified in
     * <code>preserve</code>
     * @param source
     * @param destination
     * @param fileName
     * @param preserve
     */
    private void copyProperties(File source, File destination, String fileName, String ... preserve) throws IOException {
        org.apache.felix.utils.properties.Properties s = new org.apache.felix.utils.properties.Properties(false);
        File sourceFile = new File(source, fileName);
        s.load(sourceFile);
        org.apache.felix.utils.properties.Properties d = new org.apache.felix.utils.properties.Properties(false);
        File destFile = new File(destination, fileName);
        d.load(destFile);
        Set<String> except = new HashSet<>(Arrays.asList(preserve));

        for (String key : s.keySet()) {
            if (!except.contains(key)) {
                d.setProperty(key, s.getProperty(key));
            }
        }

        d.save(destFile);
    }

    private static StringBuilder buildJvmOpts(CreateChildContainerOptions options) {
        StringBuilder jvmOptsBuilder = new StringBuilder();
        
        String jvmVersion = System.getProperty("java.version");
        boolean isJdk8 = false;
        if (jvmVersion.startsWith("1.8")) isJdk8 = true;

        String zkPasswordEncode = System.getProperty("zookeeper.password.encode", "true");
        jvmOptsBuilder.append("-server -Dcom.sun.management.jmxremote -Dorg.jboss.gravia.repository.storage.dir=data/repository")
                .append(options.getZookeeperUrl() != null ? " -Dzookeeper.url=\"" + options.getZookeeperUrl() + "\"" : "")
                .append(zkPasswordEncode != null ? " -Dzookeeper.password.encode=\"" + zkPasswordEncode + "\"" : "")
                .append(options.getZookeeperPassword() != null ? " -Dzookeeper.password=\"" + options.getZookeeperPassword() + "\"" : "");


        if (options.getJvmOpts() == null || !options.getJvmOpts().contains("-Xmx")) {
            jvmOptsBuilder.append(" -Xmx768m");
        }
        if ((options.getJvmOpts() == null || !options.getJvmOpts().contains("-XX:MaxPermSize=")) && !isJdk8)  {
            jvmOptsBuilder.append(" -XX:MaxPermSize=256m");
        }
        if (options.isEnsembleServer()) {
            jvmOptsBuilder.append(" -D").append(CreateEnsembleOptions.ENSEMBLE_AUTOSTART + "=true");
        }

        if (options.getJvmOpts() != null && !options.getJvmOpts().isEmpty()) {
            jvmOptsBuilder.append(" ").append(options.getJvmOpts());
        }

        if (options.getJvmOpts() == null || !options.getJvmOpts().contains("-XX:+UnlockDiagnosticVMOptions -XX:+UnsyncloadClass")) {
            jvmOptsBuilder.append(" -XX:+UnlockDiagnosticVMOptions -XX:+UnsyncloadClass");
        }

        if (options.getBindAddress() != null && !options.getBindAddress().isEmpty()) {
            jvmOptsBuilder.append(" -D" + ZkDefs.BIND_ADDRESS + "=" + options.getBindAddress());
        }

        if (options.getResolver() != null && !options.getResolver().isEmpty()) {
            jvmOptsBuilder.append(" -D" + ZkDefs.LOCAL_RESOLVER_PROPERTY + "=" + options.getResolver());
        }

        if (options.getManualIp() != null && !options.getManualIp().isEmpty()) {
            jvmOptsBuilder.append(" -D" + ZkDefs.MANUAL_IP + "=" + options.getManualIp());
        }

        for (Map.Entry<String, String> dataStoreEntries : options.getDataStoreProperties().entrySet()) {
            String key = dataStoreEntries.getKey();
            String value = dataStoreEntries.getValue();
            jvmOptsBuilder.append(" -D" + Constants.DATASTORE_PID + "." + key + "=" + value);
        }
        return jvmOptsBuilder;
    }

    /**
     * Returns the {@link ContainerTemplate} of the parent of the specified child {@link Container}.
     */
    private ContainerTemplate getContainerTemplateForChild(Container container) {
        CreateChildContainerOptions options = (CreateChildContainerOptions) container.getMetadata().getCreateOptions();

        String username = AuthenticationUtils.retrieveJaasUser();
        String password = AuthenticationUtils.retrieveJaasPassword();

        if (username != null && password != null) {
            options = (CreateChildContainerOptions) options.updateCredentials(username, password);
        }

        return new ContainerTemplate(container.getParent(), options.getJmxUser(), options.getJmxPassword(), false);
    }

    /**
     * Links child container resolver and addresses to its parents resolver and addresses.
     */
    private void inheritAddresses(FabricService fabricService, String parent, String name, CreateChildContainerOptions options) throws Exception {
        DataStore dataStore = fabricService.adapt(DataStore.class);
        if (options.getManualIp() != null) {
            dataStore.setContainerAttribute(name, DataStore.ContainerAttribute.ManualIp, options.getManualIp());
        } else {
            dataStore.setContainerAttribute(name, DataStore.ContainerAttribute.ManualIp, "${zk:" + parent + "/manualip}");
        }

        //Link to the addresses from the parent container.
        dataStore.setContainerAttribute(name, DataStore.ContainerAttribute.LocalHostName, "${zk:" + parent + "/localhostname}");
        dataStore.setContainerAttribute(name, DataStore.ContainerAttribute.LocalIp, "${zk:" + parent + "/localip}");
        dataStore.setContainerAttribute(name, DataStore.ContainerAttribute.PublicIp, "${zk:" + parent + "/publicip}");

        if (options.getResolver() != null) {
            dataStore.setContainerAttribute(name, DataStore.ContainerAttribute.Resolver, options.getResolver());
        } else {
            dataStore.setContainerAttribute(name, DataStore.ContainerAttribute.Resolver, "${zk:" + parent + "/resolver}");
        }

        if (options.getBindAddress() != null) {
            dataStore.setContainerAttribute(name, DataStore.ContainerAttribute.BindAddress, options.getBindAddress());
        } else {
            dataStore.setContainerAttribute(name, DataStore.ContainerAttribute.BindAddress, "${zk:" + parent + "/bindaddress}");
        }

        dataStore.setContainerAttribute(name, DataStore.ContainerAttribute.Ip, "${zk:" + name + "/${zk:" + name + "/resolver}}");
    }

    FabricService getFabricService() {
        return fabricService.get();
    }

    private static String collectionAsString(Collection<String> value) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        if (value != null) {
            for (String el : value) {
                if (first) {
                    first = false;
                } else {
                    sb.append(",");
                }
                sb.append(el);
            }
        }
        return sb.toString();
    }

    void bindFabricService(FabricService service) {
        fabricService.bind(service);
    }

    void unbindFabricService(FabricService service) {
        fabricService.unbind(service);
    }


    void bindProcessControllerFactory(ProcessControllerFactory service) {
        processControllerFactory.bind(service);
    }

    void unbindProcessControllerFactory(ProcessControllerFactory service) {
        processControllerFactory.unbind(service);
    }
}
