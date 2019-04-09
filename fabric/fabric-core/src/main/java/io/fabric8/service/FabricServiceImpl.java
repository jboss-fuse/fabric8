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
package io.fabric8.service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import io.fabric8.api.AutoScaleStatus;
import io.fabric8.api.Constants;
import io.fabric8.api.Container;
import io.fabric8.api.ContainerAutoScaler;
import io.fabric8.api.ContainerAutoScalerFactory;
import io.fabric8.api.ContainerProvider;
import io.fabric8.api.ContainerRegistration;
import io.fabric8.api.Containers;
import io.fabric8.api.CreateContainerBasicMetadata;
import io.fabric8.api.CreateContainerBasicOptions;
import io.fabric8.api.CreateContainerMetadata;
import io.fabric8.api.CreateContainerOptions;
import io.fabric8.api.CreationStateListener;
import io.fabric8.api.DataStore;
import io.fabric8.api.FabricException;
import io.fabric8.api.FabricRequirements;
import io.fabric8.api.FabricService;
import io.fabric8.api.FabricStatus;
import io.fabric8.api.NameValidator;
import io.fabric8.api.NullCreationStateListener;
import io.fabric8.api.PatchService;
import io.fabric8.api.PlaceholderResolver;
import io.fabric8.api.PortService;
import io.fabric8.api.Profile;
import io.fabric8.api.ProfileBuilder;
import io.fabric8.api.ProfileDependencyException;
import io.fabric8.api.ProfileRegistry;
import io.fabric8.api.ProfileRequirements;
import io.fabric8.api.ProfileService;
import io.fabric8.api.Profiles;
import io.fabric8.api.RuntimeProperties;
import io.fabric8.api.SystemProperties;
import io.fabric8.api.Version;
import io.fabric8.api.jcip.ThreadSafe;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.api.scr.Configurer;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.api.visibility.VisibleForTesting;
import io.fabric8.internal.ContainerImpl;
import io.fabric8.internal.ProfileDependencyConfig;
import io.fabric8.internal.ProfileDependencyKind;
import io.fabric8.utils.BundleUtils;
import io.fabric8.utils.FabricValidations;
import io.fabric8.utils.PasswordEncoder;
import io.fabric8.zookeeper.ZkPath;
import io.fabric8.zookeeper.bootstrap.BootstrapConfiguration;
import io.fabric8.zookeeper.utils.InterpolationHelper;
import io.fabric8.zookeeper.utils.ZooKeeperUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;
import org.apache.felix.utils.properties.Properties;
import org.jasypt.exceptions.EncryptionOperationNotPossibleException;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.fabric8.api.Profiles.assertValidProfileId;
import static io.fabric8.internal.PlaceholderResolverHelpers.getSchemesForProfileConfigurations;
import static io.fabric8.utils.DataStoreUtils.substituteBundleProperty;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.exists;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.getChildren;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.getChildrenSafe;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.getStringData;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.getSubstitutedData;
import static io.fabric8.zookeeper.utils.ZooKeeperUtils.getSubstitutedPath;
import static org.apache.felix.scr.annotations.ReferenceCardinality.OPTIONAL_MULTIPLE;

/**
 * FabricService
 * |_ ConfigurationAdmin
 * |_ PlaceholderResolver (optional,multiple)
 * |_ CuratorFramework (@see ManagedCuratorFramework)
 * |  |_ ACLProvider (@see CuratorACLManager)
 * |_ DataStore (@see GitDataStore)
 *    |_ CuratorFramework  --^
 *    |_ GitService (@see FabricGitServiceImpl)
 *    |_ ContainerProvider (optional,multiple) (@see ChildContainerProvider)
 *    |  |_ FabricService --^
 *    |_ PortService (@see ZookeeperPortService)
 *       |_ CuratorFramework --^
 */
@ThreadSafe
@Component(name = "io.fabric8.service", label = "Fabric8 Service", metatype = false)
@Service(FabricService.class)
public final class FabricServiceImpl extends AbstractComponent implements FabricService {

    public static final String REQUIREMENTS_JSON_PATH = "/fabric/configs/io.fabric8.requirements.json";
    public static final String JVM_OPTIONS_PATH = "/fabric/configs/io.fabric8.containers.jvmOptions";

    private static final Logger LOGGER = LoggerFactory.getLogger(FabricServiceImpl.class);

    // Logical Dependencies
    @Reference
    private ChecksumPlaceholderResolver checksumPlaceholderResolver;
    @Reference
    private ContainerPlaceholderResolver containerPlaceholderResolver;
    @Reference
    private EncryptedPropertyResolver encryptedPropertyResolver;
    @Reference
    private EnvPlaceholderResolver envPlaceholderResolver;
    @Reference
    private PortPlaceholderResolver portPlaceholderResolver;
    @Reference
    private ProfilePropertyPointerResolver profilePropertyPointerResolver;
    @Reference
    private VersionPropertyPointerResolver versionPropertyPointerResolver;
    @Reference
    private ZookeeperPlaceholderResolver zookeeperPlaceholderResolver;
    @Reference
    private ContainerRegistration containerRegistration;
    @Reference
    private BootstrapConfiguration bootstrapConfig;

    @Reference(referenceInterface = ConfigurationAdmin.class)
    private final ValidatingReference<ConfigurationAdmin> configAdmin = new ValidatingReference<ConfigurationAdmin>();
    @Reference(referenceInterface = org.apache.felix.scr.ScrService.class)
    private final ValidatingReference<org.apache.felix.scr.ScrService> scrService = new ValidatingReference<org.apache.felix.scr.ScrService>();
    @Reference(referenceInterface = RuntimeProperties.class)
    private final ValidatingReference<RuntimeProperties> runtimeProperties = new ValidatingReference<RuntimeProperties>();
    @Reference(referenceInterface = CuratorFramework.class)
    private final ValidatingReference<CuratorFramework> curator = new ValidatingReference<CuratorFramework>();
    @Reference(referenceInterface = DataStore.class)
    private final ValidatingReference<DataStore> dataStore = new ValidatingReference<DataStore>();
    @Reference(referenceInterface = PortService.class)
    private final ValidatingReference<PortService> portService = new ValidatingReference<PortService>();
    @Reference(referenceInterface = ProfileService.class)
    private final ValidatingReference<ProfileService> profileService = new ValidatingReference<>();
    @Reference(referenceInterface = ProfileRegistry.class)
    private final ValidatingReference<ProfileRegistry> profileRegistry = new ValidatingReference<>();
    @Reference(referenceInterface = ContainerProvider.class, bind = "bindProvider", unbind = "unbindProvider", cardinality = OPTIONAL_MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    private final Map<String, ContainerProvider> providers = new ConcurrentHashMap<String, ContainerProvider>();
    @Reference(referenceInterface = PlaceholderResolver.class, bind = "bindPlaceholderResolver", unbind = "unbindPlaceholderResolver", cardinality = OPTIONAL_MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    private final Map<String, PlaceholderResolver> placeholderResolvers = new ConcurrentHashMap<String, PlaceholderResolver>();

    @Reference
    private Configurer configurer;

    private String defaultRepo = FabricService.DEFAULT_REPO_URI;
    private BundleContext bundleContext;

    @Activate
    void activate(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
        activateComponent();
        if (encryptedPropertyResolver != null) {
            encryptedPropertyResolver.initialize(this);
        }
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T adapt(Class<T> type) {
        assertValid();
        if (type.isAssignableFrom(CuratorFramework.class)) {
            return (T) curator.get();
        } else if (type.isAssignableFrom(DataStore.class)) {
            return (T) dataStore.get();
        } else if (type.isAssignableFrom(ProfileService.class)) {
            return (T) profileService.get();
        } else if (type.isAssignableFrom(ProfileRegistry.class)) {
            return (T) profileRegistry.get();
        }
        return null;
    }

    public String getDefaultRepo() {
        synchronized (this) {
            return defaultRepo;
        }
    }

    public void setDefaultRepo(String defaultRepo) {
        synchronized (this) {
            this.defaultRepo = defaultRepo;
        }
    }

    @Override
    public PortService getPortService() {
        assertValid();
        return portService.get();
    }

    @Override
    public Container getCurrentContainer() {
        assertValid();
        String name = getCurrentContainerName();
        return getContainer(name);
    }

    @Override
    public String getEnvironment() {
        assertValid();
        String answer = runtimeProperties.get().getProperty(SystemProperties.FABRIC_ENVIRONMENT);
        if (answer == null) {
            // in case we've not updated the system properties in the JVM based on the profile
            // e.g. when adding/removing profiles
            Container currentContainer = getCurrentContainer();
            if (currentContainer != null) {
                Map<String, String> systemProperties = currentContainer.getOverlayProfile().getConfiguration(Constants.SYSTEM_PROPERTIES_PID);
                answer = systemProperties.get(SystemProperties.FABRIC_ENVIRONMENT);
            }

            // lets store the effective profile for later on
            if (answer != null) {
                System.setProperty(SystemProperties.FABRIC_PROFILE_ENVIRONMENT, answer);
            }
        }
        return answer;
    }

    @Override
    public String getCurrentContainerName() {
        assertValid();
        return runtimeProperties.get().getRuntimeIdentity();
    }

    @Override
    public void trackConfiguration(Runnable callback) {
        assertValid();
        dataStore.get().trackConfiguration(callback);
    }

    @Override
    public void untrackConfiguration(Runnable callback) {
        assertValid();
        dataStore.get().untrackConfiguration(callback);
    }

    @Override
    public Container[] getContainers() {
        assertValid();
        Map<String, Container> containers = new HashMap<String, Container>();
        List<String> containerIds = dataStore.get().getContainers();
        for (String containerId : containerIds) {
            String parentId = dataStore.get().getContainerParent(containerId);
            if (parentId.isEmpty()) {
                if (!containers.containsKey(containerId)) {
                    Container container = new ContainerImpl(null, containerId, this);
                    containers.put(containerId, container);
                }
            } else {
                Container parent = containers.get(parentId);
                if (parent == null) {
                    parent = new ContainerImpl(null, parentId, this);
                    containers.put(parentId, parent);
                }
                Container container = new ContainerImpl(parent, containerId, this);
                containers.put(containerId, container);
            }
        }
        return containers.values().toArray(new Container[containers.size()]);
    }

	@Override
	public Container[] getAssociatedContainers(String versionId, String profileId) {
		assertValid();
        List<Container> containers = new ArrayList<>();
        for (Container container : getContainers()) {
        	for (Profile profile : Arrays.asList(container.getProfiles())) {
                if (profile.getId().equals(profileId) && profile.getVersion().equals(versionId)) {
                    containers.add(container);
            	}
        	}
        }
        return containers.toArray(new Container[containers.size()]);
	}

    @Override
    public Container getContainer(String name) {
        assertValid();
        if (dataStore.get().hasContainer(name)) {
            Container parent = null;
            String parentId = dataStore.get().getContainerParent(name);
            if (parentId != null && !parentId.isEmpty()) {
                parent = getContainer(parentId);
            }
            return new ContainerImpl(parent, name, this);
        }
        throw new FabricException("Container '" + name + "' does not exist");
    }

    @Override
    public void startContainer(String containerId) {
        startContainer(containerId, false);
    }

    public void startContainer(String containerId, boolean force) {
        assertValid();
        Container container = getContainer(containerId);
        if (container != null) {
            startContainer(container, force);
        }
    }

    public void startContainer(Container container) {
        startContainer(container, true);
    }

    public void startContainer(Container container, boolean force) {
        assertValid();
        LOGGER.info("Starting container {}", container.getId());
        ContainerProvider provider = getProvider(container);
        provider.start(container);
    }

    @Override
    public void stopContainer(String containerId) {
        stopContainer(containerId, false);
    }

    public void stopContainer(String containerId, boolean force) {
        assertValid();
        Container container = getContainer(containerId);
        if (container != null) {
            stopContainer(container, force);
        }
    }

    public void stopContainer(Container container) {
        stopContainer(container, false);
    }

    public void stopContainer(Container container, boolean force) {
        assertValid();
        LOGGER.info("Stopping container {}", container.getId());
        ContainerProvider provider = getProvider(container);
        try {
            provider.stop(container);
        } catch (RuntimeException ex) {
            // if its already stopped then ignore the exception
            boolean stopped = "Instance already stopped".equals(ex.getMessage());
            if (!stopped) {
                throw ex;
            }
        }
    }

    @Override
    public void destroyContainer(String containerId) {
        destroyContainer(containerId, false);
    }

    public void destroyContainer(String containerId, boolean force) {
        assertValid();
        Container container = getContainer(containerId);
        if (container != null) {
            destroyContainer(container, force);
        }
    }

    @Override
    public void destroyContainer(Container container) {
        destroyContainer(container, false);
    }

    public void destroyContainer(Container container, boolean force) {
        assertValid();
        String containerId = container.getId();
        Exception providerException = null;
        LOGGER.info("Destroying container {}", containerId);
        boolean destroyed = false;
        try {
            ContainerProvider provider = getProvider(container, true);
            if (provider != null) {
                try {
                    provider.stop(container);
                } catch (Exception ex) {
                    // Ignore error while stopping and try to destroy.
                    // and if its already stopped then ignore the exception and do not rethrow later
                    boolean stopped = "Instance already stopped".equals(ex.getMessage());
                    if (!stopped) {
                        providerException = ex;
                    }
                }
                provider.destroy(container);
                destroyed = true;
            } else {
                throw new FabricException("Container's lifecycle not managed by Fabric8 (the container was not created by Fabric8).");
            }

        } finally {
            try {
                if (destroyed || force) {
                    try {
                        portService.get().unregisterPort(container);
                    } catch (Exception e) {
                        LOGGER.warn("Failed to cleanup container {} entries due to: {}. This will be ignored.", containerId, e.getMessage());
                    }
                    dataStore.get().deleteContainer(this, container.getId());
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to cleanup container {} entries due to: {}. This will be ignored.", containerId, e.getMessage());
            }
            if (providerException != null) {
                throw FabricException.launderThrowable(providerException);
            }
        }
    }

    private ContainerProvider getProvider(Container container) {
        return getProvider(container, false);
    }

    private ContainerProvider getProvider(Container container, boolean returnNull) {
        CreateContainerMetadata metadata = container.getMetadata();
        String type = metadata != null ? metadata.getCreateOptions().getProviderType() : null;
        if (type == null) {
            if (returnNull) {
                return null;
            }
            throw new UnsupportedOperationException("Container " + container.getId() + " has not been created using Fabric");
        }
        ContainerProvider provider = getProvider(type);
        if (provider == null) {
            if (returnNull) {
                return null;
            }
            throw new UnsupportedOperationException("Container provider " + type + " not supported");
        }
        return provider;
    }

    @Override
    public CreateContainerMetadata[] createContainers(CreateContainerOptions options) {
        return createContainers(options, null);
    }

    @Override
    public CreateContainerMetadata[] createContainers(CreateContainerOptions options, CreationStateListener listener) {
        assertValid();
        try {
            final ContainerProvider provider = getProvider(options.getProviderType());
            if (provider == null) {
                throw new FabricException("Unable to find a container provider supporting '" + options.getProviderType() + "'");
            }
            if (!provider.isValidProvider()) {
                throw new FabricException("The provider '" + options.getProviderType() + "' is not valid in current environment");
            }

            String originalName = options.getName();
            if (originalName == null || originalName.length() == 0) {
                throw new FabricException("A name must be specified when creating containers");
            }

            // Only allow containers with valid names to get created.
            FabricValidations.validateContainerName(originalName);

            if (listener == null) {
                listener = new NullCreationStateListener();
            }

            validateProfileDependencies(options);
            ObjectMapper mapper = new ObjectMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            Map optionsMap = mapper.readValue(mapper.writeValueAsString(options), Map.class);
            String versionId = options.getVersion() != null ? options.getVersion() : dataStore.get().getDefaultVersion();
            Set<String> profileIds = options.getProfiles();
            if (profileIds == null || profileIds.isEmpty()) {
                profileIds = new LinkedHashSet<String>();
                profileIds.add("default");
            }
            optionsMap.put("version", versionId);
            optionsMap.put("profiles", profileIds);
            optionsMap.put("number", 0);

            // assign parent resolver if it's a child container, else assign global resolver policy
            if(bootstrapConfig != null) {
                String configuredGlobalResolver = bootstrapConfig.getGlobalResolver();
                if (!Strings.isNullOrEmpty(configuredGlobalResolver)) {
                    optionsMap.put("globalResolver", configuredGlobalResolver);
                    if (optionsMap.get("resolver") == null) {
                        if (optionsMap.get("parent") == null) {
                            optionsMap.put("resolver", configuredGlobalResolver);
                        }
                    }
                }
            }

            final List<CreateContainerMetadata> metadatas = new CopyOnWriteArrayList<CreateContainerMetadata>();
            int orgNumber = options.getNumber();
            int number = Math.max(orgNumber, 1);
            if (orgNumber > 1) {
                originalName = originalName + "1";
            }
            final CountDownLatch latch = new CountDownLatch(number);
            Set<String> ignoreContainerNames = new HashSet<>();
            Container[] containers = getContainers();

            // check that there is no container with the given name
            for (Container container : containers) {
                if (container.getId().equals(options.getName())) {
                    throw new IllegalArgumentException("A container with name " + options.getName() + " already exists.");
                }
            }

            for (int i = 1; i <= number; i++) {
                NameValidator validator = Containers.createNameValidator(containers, ignoreContainerNames);
                final String containerName = Containers.createUniqueContainerName(containers, originalName, validator);
                ignoreContainerNames.add(containerName);

                optionsMap.put("name", containerName);

                //Check if datastore configuration has been specified and fallback to current container settings.
                if (!hasValidDataStoreProperties(optionsMap)) {
                    optionsMap.put("dataStoreProperties", profileRegistry.get().getDataStoreProperties());
                }
                Class cl = options.getClass().getClassLoader().loadClass(options.getClass().getName() + "$Builder");
                CreateContainerBasicOptions.Builder builder = (CreateContainerBasicOptions.Builder) mapper.readValue(mapper.writeValueAsString(optionsMap), cl);
                //We always want to pass the obfuscated version of the password to the container provider.
                builder = (CreateContainerBasicOptions.Builder) builder.zookeeperPassword(PasswordEncoder.encode(getZookeeperPassword()));
                final CreateContainerOptions containerOptions = builder.build();
                final CreationStateListener containerListener = listener;
                final FabricService fabricService = this;
                new Thread("Creating container " + containerName) {
                    public void run() {
                        try {
                            if (dataStore.get().hasContainer(containerName)) {
                                CreateContainerBasicMetadata metadata = new CreateContainerBasicMetadata();
                                metadata.setContainerName(containerName);
                                metadata.setCreateOptions(containerOptions);
                                metadata.setFailure(new IllegalArgumentException("A container with name " + containerName + " already exists."));
                                metadatas.add(metadata);
                                return;
                            }
                            dataStore.get().createContainerConfig(containerOptions);
                            CreateContainerMetadata metadata = provider.create(containerOptions, containerListener);
                            if (metadata.isSuccess()) {
                                Container parent = containerOptions.getParent() != null ? getContainer(containerOptions.getParent()) : null;
                                //An ensemble server can be created without an existing ensemble.
                                //In this case container config will be created by the newly created container.
                                //TODO: We need to make sure that this entries are somehow added even to ensemble servers.
                                if (!containerOptions.isEnsembleServer()) {
                                    dataStore.get().createContainerConfig(metadata);
                                }
                                ContainerImpl container = new ContainerImpl(parent, metadata.getContainerName(), FabricServiceImpl.this);
                                metadata.setContainer(container);
                                LOGGER.info("The container " + metadata.getContainerName() + " has been successfully created");
                            } else {
                                LOGGER.warn("The creation of the container " + metadata.getContainerName() + " has failed", metadata.getFailure());
                                dataStore.get().deleteContainer(fabricService, containerOptions.getName());
                            }
                            metadatas.add(metadata);
                        } catch (Throwable t) {
                            CreateContainerBasicMetadata metadata = new CreateContainerBasicMetadata();
                            metadata.setContainerName(containerName);
                            metadata.setCreateOptions(containerOptions);
                            metadata.setFailure(t);
                            metadatas.add(metadata);
                            dataStore.get().deleteContainer(fabricService, containerOptions.getName());
                        } finally {
                            latch.countDown();
                        }
                    }
                }.start();
            }
            if (!latch.await(30, TimeUnit.MINUTES)) {
                throw new FabricException("Timeout waiting for container creation");
            }
            return metadatas.toArray(new CreateContainerMetadata[metadatas.size()]);
        } catch (Exception e) {
            LOGGER.error("Failed to create containers " + e, e);
            throw FabricException.launderThrowable(e);
        }
    }

    protected void validateProfileDependencies(CreateContainerOptions options) {
        Map<String, Map<String, String>> profileDependencies = Profiles.getOverlayFactoryConfigurations(this, options.getProfiles(), options.getVersion(), ProfileDependencyConfig.PROFILE_DEPENDENCY_CONFIG_PID);
        Set<Map.Entry<String, Map<String, String>>> entries = profileDependencies.entrySet();
        for (Map.Entry<String, Map<String, String>> entry : entries) {
            String configName = entry.getKey();
            Map<String, String> exportConfig = entry.getValue();

            if (exportConfig != null && !exportConfig.isEmpty()) {
                ProfileDependencyConfig config = new ProfileDependencyConfig();
                try {
                    configurer.configure(exportConfig, config);
                } catch (Exception e) {
                    throw new FabricException("Failed to load configuration for " + configName + " of " + config + " due to: " + e, e);
                }

                // Ensure dependent container exists
                if (ProfileDependencyKind.ZOOKEEPER_SERVICE.equals(config.getKind())) {
                    try {
                        List<String> children = getChildren(this.curator.get(), config.getZookeeperPath());
                        if (children == null || children.isEmpty()) {
                            throw new ProfileDependencyException(options.getProfiles(), config.getProfileWildcards(), config.getProfileTags(), config.getSummary());
                        }

                        boolean dependencyFound = false;
                        Iterator<String> childIterator = children.iterator();

                        while (!dependencyFound && childIterator.hasNext()) {
                            String containerName = childIterator.next();
                            Container container = this.getContainer(containerName);
                            Profile[] profiles = container.getProfiles();
                            int profileCount = 0;

                            while (!dependencyFound && profileCount < profiles.length) {
                                Profile profile = profiles[profileCount];
                                if (config.getProfileWildcards() != null) {
                                    for (String profileWildcard : config.getProfileWildcards()) {
                                        if (profile.getId().contains(profileWildcard)) {
                                            dependencyFound = true;
                                            break;
                                        }
                                    }
                                }

                                if (!dependencyFound && config.getProfileTags() != null) {
                                    List<String> profileTags = profile.getTags();
                                    int foundTags = 0;

                                    for (String configProfileTag : config.getProfileTags()) {
                                        if (profileTags.contains(configProfileTag)) {
                                            foundTags++;
                                        }
                                    }

                                    if (foundTags == config.getProfileTags().length) {
                                        dependencyFound = true;
                                    }
                                }
                            }
                        }

                        if (!dependencyFound) {
                            throw new ProfileDependencyException(options.getProfiles(), config.getProfileWildcards(), config.getProfileTags(), config.getSummary());
                        }
                    } catch (Exception e) {
                        throw new ProfileDependencyException(options.getProfiles(), config.getProfileWildcards(), config.getProfileTags(), config.getSummary(), e);
                    }
                }
            }
        }
    }

    @Override
    public Set<Class<? extends CreateContainerBasicOptions>> getSupportedCreateContainerOptionTypes() {
        assertValid();
        Set<Class<? extends CreateContainerBasicOptions>> optionTypes = new HashSet<Class<? extends CreateContainerBasicOptions>>();
        for (Map.Entry<String, ContainerProvider> entry : providers.entrySet()) {
            optionTypes.add(entry.getValue().getOptionsType());
        }
        return optionTypes;
    }

    @Override
    public Set<Class<? extends CreateContainerBasicMetadata>> getSupportedCreateContainerMetadataTypes() {
        assertValid();
        Set<Class<? extends CreateContainerBasicMetadata>> metadataTypes = new HashSet<Class<? extends CreateContainerBasicMetadata>>();
        for (Map.Entry<String, ContainerProvider> entry : providers.entrySet()) {
            metadataTypes.add(entry.getValue().getMetadataType());
        }
        return metadataTypes;
    }

    @Override
    public ContainerProvider getProvider(final String scheme) {
        return providers.get(scheme);
    }

    @Override
    public Map<String, ContainerProvider> getValidProviders() {
        assertValid();
        Map<String, ContainerProvider> validProviders = new HashMap<String, ContainerProvider>();
        for (ContainerProvider cp : getProviders().values()) {
            if (cp.isValidProvider()) {
                validProviders.put(cp.getScheme(), cp);
            }
        }
        return Collections.unmodifiableMap(validProviders);
    }

    public Map<String, ContainerProvider> getProviders() {
        assertValid();
        return Collections.unmodifiableMap(providers);
    }

    @Override
    public String getRestAPI() {
        assertValid();
        String restApiFolder = ZkPath.REST_API_CLUSTERS.getPath("FabricResource/fabric8");
        try {
            CuratorFramework curatorFramework = curator.get();
            if (curatorFramework != null) {
                List<String> versions = getChildrenSafe(curatorFramework, restApiFolder);
                for (String version : versions) {
                    String versionPath = restApiFolder + "/" + version;
                    List<String> containers = getChildrenSafe(curatorFramework, versionPath);
                    for (String container : containers) {
                        String containerPath = versionPath + "/" + container;
                        String answer = getFirstService(containerPath);
                        if (!Strings.isNullOrEmpty(answer)) {
                            return answer;
                        }
                    }
                }
            }
        } catch (Exception e) {
            //On exception just return uri.
            LOGGER.warn("Failed to find API " + restApiFolder + ". " + e, e);
        }
        return null;
    }

    protected String getFirstService(String containerPath) throws Exception {
        CuratorFramework curatorFramework = curator.get();
        if (curatorFramework != null) {
            byte[] data = curatorFramework.getData().forPath(containerPath);
            if (data != null && data.length > 0) {
                String text = new String(data).trim();
                if (!text.isEmpty()) {
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> map = mapper.readValue(data, HashMap.class);
                    Object serviceValue = map.get("services");
                    if (serviceValue instanceof List) {
                        List services = (List) serviceValue;
                        if (!services.isEmpty()) {
                            List<String> serviceTexts = new ArrayList<String>();
                            for (Object service : services) {
                                String serviceText = getSubstitutedData(curatorFramework, service.toString());
                                if (io.fabric8.common.util.Strings.isNotBlank(serviceText)) {
                                    return serviceText;
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    protected String getFirstServiceName(String containerPath) throws Exception {
        CuratorFramework curatorFramework = curator.get();
        if (curatorFramework != null) {
            byte[] data = curatorFramework.getData().forPath(containerPath);
            if (data != null && data.length > 0) {
                String text = new String(data).trim();
                if (!text.isEmpty()) {
                    ObjectMapper mapper = new ObjectMapper();
                    Map<String, Object> map = mapper.readValue(data, HashMap.class);
                    Object serviceValue = map.get("services");
                    if (serviceValue instanceof List) {
                        List services = (List) serviceValue;
                        if (!services.isEmpty()) {
                            List<String> serviceTexts = new ArrayList<String>();
                            for (Object service : services) {
                                String serviceText = getSubstitutedData(curatorFramework, service.toString());
                                if (io.fabric8.common.util.Strings.isNotBlank(serviceText)) {
                                    return (String) map.get("container");
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String getGitUrl() {
        assertValid();
        String gitServers = ZkPath.GIT.getPath();
        try {
            CuratorFramework curatorFramework = curator.get();
            if (curatorFramework != null) {
                List<String> servers = getChildrenSafe(curatorFramework, gitServers);
                for (String server : servers) {
                    String serverPath = gitServers + "/" + server;
                    String answer = getFirstService(serverPath);
                    if (!Strings.isNullOrEmpty(answer)) {
                        return answer;
                    }
                }
            }
        } catch (Exception e) {
            //On exception just return uri.
            LOGGER.warn("Failed to find Git URL " + gitServers + ". " + e, e);
        }
        return null;
    }

    @Override
    public String getGitMaster() {
        assertValid();
        String gitServers = ZkPath.GIT.getPath();
        try {
            CuratorFramework curatorFramework = curator.get();
            if (curatorFramework != null) {
                List<String> servers = getChildrenSafe(curatorFramework, gitServers);
                for (String server : servers) {
                    String serverPath = gitServers + "/" + server;
                    String answer = getFirstServiceName(serverPath);
                    if (!Strings.isNullOrEmpty(answer)) {
                        return answer;
                    }
                }
            }
        } catch (Exception e) {
            //On exception just return uri.
            LOGGER.warn("Failed to find container name for Git master " + gitServers + ". " + e, e);
        }
        return null;
    }

    @Override
    public String getWebConsoleUrl() {
        Container[] containers = null;
        try {
            containers = getContainers();
        } catch (Exception e) {
            LOGGER.debug("Ignored exception trying to find containers: " + e, e);
            return null;
        }
        for (Container aContainer : containers) {
            Profile[] profiles = aContainer.getProfiles();
            for (Profile aProfile : profiles) {
                String id = aProfile.getId();
                if (id.equals("fabric")) {
                    return profileWebAppURL("io.hawt.hawtio-web", id, aProfile.getVersion());
                }
            }
        }
        return null;
    }

    @Override
    public URI getMavenRepoURI() {
        assertValid();
        URI uri = URI.create(getDefaultRepo());
        try {
            if (exists(curator.get(), ZkPath.MAVEN_PROXY.getPath("download")) != null) {
                List<String> children = getChildren(curator.get(), ZkPath.MAVEN_PROXY.getPath("download"));
                if (children != null && !children.isEmpty()) {
                    Collections.sort(children);

                    String mavenRepo = getSubstitutedPath(curator.get(), ZkPath.MAVEN_PROXY.getPath("download") + "/" + children.get(0));
                    if (mavenRepo != null && !mavenRepo.endsWith("/")) {
                        mavenRepo += "/";
                    }
                    uri = new URI(mavenRepo);
                }
            }
        } catch (Exception e) {
            //On exception just return uri.
        }
        return uri;
    }

    @Override
    public List<URI> getMavenRepoURIs() {
        assertValid();
        try {
            List<URI> uris = new ArrayList<URI>();
            if (exists(curator.get(), ZkPath.MAVEN_PROXY.getPath("download")) != null) {
                List<String> children = getChildren(curator.get(), ZkPath.MAVEN_PROXY.getPath("download"));
                if (children != null && !children.isEmpty()) {
                    Collections.sort(children);
                }
                if (children != null) {
                    for (String child : children) {
                        String mavenRepo = "";
                        try {
                            mavenRepo = getSubstitutedPath(curator.get(), ZkPath.MAVEN_PROXY.getPath("download") + "/" + child);
                        } catch (Exception e){
                            LOGGER.warn("No znodes found under path: [{}]", ZkPath.MAVEN_PROXY.getPath("download"), e);
                            return new ArrayList<URI>(0);
                        }
                        if (mavenRepo != null && !mavenRepo.endsWith("/")) {
                            mavenRepo += "/";
                        }
                        if(mavenRepo != null) {
                            mavenRepo += "@snapshots@id=fabric_internal@snapshotsUpdate=always@checksum=ignore";
                            uris.add(new URI(mavenRepo));
                        }
                    }
                }
            }
            return uris;
        } catch (Exception e) {
            throw FabricException.launderThrowable(e);
        }
    }

    @Override
    public URI getMavenRepoUploadURI() {
        assertValid();
        URI uri = URI.create(getDefaultRepo());
        try {
            if (exists(curator.get(), ZkPath.MAVEN_PROXY.getPath("upload")) != null) {
                List<String> children = getChildren(curator.get(), ZkPath.MAVEN_PROXY.getPath("upload"));
                if (children != null && !children.isEmpty()) {
                    Collections.sort(children);
    
                    String mavenRepo = getSubstitutedPath(curator.get(), ZkPath.MAVEN_PROXY.getPath("upload") + "/" + children.get(0));
                    if (mavenRepo != null && !mavenRepo.endsWith("/")) {
                        mavenRepo += "/";
                    }
                    uri = new URI(mavenRepo);
                }
            }
        } catch (Exception e) {
            //On exception just return uri.
        }
        return uri;
    }


    @Override
    public String profileWebAppURL(String webAppId, String profileId, String versionId) {
        if (versionId == null || versionId.length() == 0) {
            Version version = getDefaultVersion();
            if (version != null) {
                versionId = version.getId();
            }
        }
        List<Container> containers = Containers.containersForProfile(getContainers(), profileId, versionId);
        for (Container container : containers) {
            String url = containerWebAppURL(webAppId, container.getId());
            if (url != null && url.length() > 0) {
                return url;
            }
        }
        return null;
    }


    public String containerWebAppURL(String webAppId, String name) {
        assertValid();
        // lets try both the webapps and servlets area
        String answer = containerWebAppUrl(ZkPath.WEBAPPS_CLUSTER.getPath(webAppId), name);
        if (answer == null) {
            answer = containerWebAppUrl(ZkPath.SERVLETS_CLUSTER.getPath(webAppId), name);
        }
        return answer;

    }

    private String containerWebAppUrl(String versionsPath, String name) {
        try {
            if (exists(curator.get(), versionsPath) != null) {
                List<String> children = getChildren(curator.get(), versionsPath);
                if (children != null && !children.isEmpty()) {
                    for (String child : children) {
                        if (Strings.isNullOrEmpty(name)) {
                            // lets just use the first container we find
                            String parentPath = versionsPath + "/" + child;
                            List<String> grandChildren = getChildren(curator.get(), parentPath);
                            if (!grandChildren.isEmpty()) {
                                String containerPath = parentPath + "/" + grandChildren.get(0);
                                String answer = getWebUrl(containerPath);
                                if (!Strings.isNullOrEmpty(answer)) {
                                    return answer;
                                }
                            }
                        } else {
                            String childPath = versionsPath + "/" + child;
                            String containerPath = childPath + "/" + name;
                            String answer = getWebUrl(containerPath);
                            if (Strings.isNullOrEmpty(answer)) {
                                // lets recurse into a child folder just in case
                                // or in the case of servlet paths where there may be extra levels of depth
                                answer = containerWebAppUrl(childPath, name);
                            }
                            if (!Strings.isNullOrEmpty(answer)) {
                                return answer;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to find container Jolokia URL " + e, e);
        }
        return null;
    }

    private String getWebUrl(String containerPath) throws Exception {
        if (curator.get().checkExists().forPath(containerPath) != null) {
            byte[] bytes = ZkPath.loadURL(curator.get(), containerPath);
            String text = new String(bytes);
            // NOTE this is a bit naughty, we should probably be doing
            // Jackson parsing here; but we only need 1 String and
            // this avoids the jackson runtime dependency - its just a bit brittle
            // only finding http endpoints and all
            String prefix = "\"services\":[\"";
            int idx = text.indexOf(prefix);
            String answer = text;
            if (idx > 0) {
                int startIndex = idx + prefix.length();
                int endIdx = text.indexOf("\"]", startIndex);
                if (endIdx > 0) {
                    answer = text.substring(startIndex, endIdx);
                    if (answer.length() > 0) {
                        // lets expand any variables
                        answer = ZooKeeperUtils.getSubstitutedData(curator.get(), answer);
                        return answer;
                    }
                }
            }
        }
        return null;
    }

    private static boolean hasValidDataStoreProperties(Map options) {
        if (!options.containsKey("dataStoreProperties")) {
            return false;
        }

        Object props = options.get("dataStoreProperties");
        if (props instanceof Map) {
            return !((Map) props).isEmpty();
        } else {
            return false;
        }
    }

    // FIXME public access on the impl
    public void registerProvider(String scheme, ContainerProvider provider) {
        assertValid();
        providers.put(scheme, provider);
    }

    // FIXME public access on the impl
    public void registerProvider(ContainerProvider provider, Map<String, Object> properties) {
        assertValid();
        String scheme = (String) properties.get(io.fabric8.utils.Constants.PROTOCOL);
        registerProvider(scheme, provider);
    }

    // FIXME public access on the impl
    public void unregisterProvider(String scheme) {
        assertValid();
        providers.remove(scheme);
    }

    // FIXME public access on the impl
    public void unregisterProvider(ContainerProvider provider, Map<String, Object> properties) {
        assertValid();
        String scheme = (String) properties.get(io.fabric8.utils.Constants.PROTOCOL);
        unregisterProvider(scheme);
    }

    @Override
    public String getZookeeperUrl() {
        assertValid();
        return getZookeeperInfo("zookeeper.url");
    }

    @Override
    public String getZooKeeperUser() {
        assertValid();
        String answer = null;
        try {
            answer = getZookeeperInfo("zookeeper.user");
        } catch (Exception e) {
            LOGGER.warn("could not find zookeeper.user: " + e, e);
        }
        if (Strings.isNullOrEmpty(answer)) {
            answer = "admin";
        }
        return answer;
    }

    @Override
    public String getZookeeperPassword() {
        assertValid();
        String rawZookeeperPassword = getZookeeperInfo("zookeeper.password");
        if (rawZookeeperPassword != null) {
            return PasswordEncoder.decode(rawZookeeperPassword);
        } else {
            return null;
        }
    }

    // FIXME public access on the impl
    public String getZookeeperInfo(String name) {
        assertValid();
        String zooKeeperUrl = null;
        //We are looking directly for at the zookeeper for the url, since container might not even be mananaged.
        //Also this is required for the integration with the IDE.
        try {
            if (curator.get().getZookeeperClient().isConnected()) {
                Version defaultVersion = getDefaultVersion();
                if (defaultVersion != null) {
                    Profile profile = defaultVersion.getRequiredProfile("default");
                    if (profile != null) {
                        Map<String, String> zookeeperConfig = profile.getConfiguration(Constants.ZOOKEEPER_CLIENT_PID);
                        if (zookeeperConfig != null) {
                            zooKeeperUrl = getSubstitutedData(curator.get(), zookeeperConfig.get(name));
                        }
                    }
                }
            }
        } catch (Exception e) {
            //Ignore it.
        }

        if (zooKeeperUrl == null) {
            try {
                Configuration config = configAdmin.get().getConfiguration(Constants.ZOOKEEPER_CLIENT_PID, null);
                zooKeeperUrl = (String) config.getProperties().get(name);
            } catch (Exception e) {
                //Ignore it.
            }
        }
        return zooKeeperUrl;
    }

    @Override
    public String getDefaultVersionId() {
        assertValid();
        return dataStore.get().getDefaultVersion();
    }

    @Override
    public Version getDefaultVersion() {
        assertValid();
        String versionId = dataStore.get().getDefaultVersion();
        return profileService.get().getVersion(versionId);
    }

    @Override
    public Version getRequiredDefaultVersion() {
        assertValid();
        String versionId = dataStore.get().getDefaultVersion();
        return profileService.get().getRequiredVersion(versionId);
    }

	@Override
	public void setDefaultVersionId(String versionId) {
        assertValid();
        dataStore.get().setDefaultVersion(versionId);
	}

    @Override
    public void setRequirements(FabricRequirements requirements) throws IOException {
        assertValid();
        validateRequirements(this, requirements);

        Set<String> activeAutoScaledProfiles = new HashSet<>();
        for (Container container : getContainers()) {
            if( container.getId().startsWith("auto_") ) {
                activeAutoScaledProfiles.addAll(container.getProfileIds());
            }
        }
        requirements.removeEmptyRequirements(activeAutoScaledProfiles);

        dataStore.get().setRequirements(requirements);
    }

    /**
     * Validates the requirements to ensure the profiles exist etc. and
     * removes those for a profile that does not exist.
     */
    public static void validateRequirements(FabricService fabricService, FabricRequirements requirements) {
        ProfileService profileService = fabricService.adapt(ProfileService.class);
        String versionId = requirements.getVersion();
        Version version;
        if (!Strings.isNullOrEmpty(versionId)) {
            version = profileService.getRequiredVersion(versionId);
        } else {
            version = fabricService.getDefaultVersion();
        }
        Set<String> profileIds = new HashSet<>(Profiles.profileIds(version.getProfiles()));
        List<ProfileRequirements> profileRequirements = requirements.getProfileRequirements();
        List<String> profilesToBeRemoved = new ArrayList<>();
        for (ProfileRequirements profileRequirement : profileRequirements) {
            try {
                validateProfileRequirements(fabricService, requirements, profileRequirement, profileIds);
            } catch (IllegalArgumentException e) {
                LOGGER.info("Removing {}; {}", profileRequirement, e.getMessage());
                profilesToBeRemoved.add(profileRequirement.getProfile());
            }
        }
        for(String profile : profilesToBeRemoved){
            requirements.removeProfileRequirements(profile);
        }
    }

    protected static void validateProfileRequirements(FabricService fabricService, FabricRequirements requirements, ProfileRequirements profileRequirement, Set<String> profileIds) {
        profileRequirement.validate();
        assertValidProfileId(profileIds, profileRequirement.getProfile());
        List<String> dependentProfiles = profileRequirement.getDependentProfiles();
        if (dependentProfiles != null) {
            for (String dependentProfile : dependentProfiles) {
                assertValidProfileId(profileIds, dependentProfile);
            }
        }
    }

    @Override
    public FabricRequirements getRequirements() {
        assertValid();
        FabricRequirements requirements = dataStore.get().getRequirements();
        if (requirements.getVersion() == null || requirements.getVersion().trim().equals("") ){
            requirements.setVersion(getDefaultVersionId());
        }
        return requirements;
    }

    @Override
    public AutoScaleStatus getAutoScaleStatus() {
        assertValid();
        return dataStore.get().getAutoScaleStatus();
    }

    @Override
    public FabricStatus getFabricStatus() {
        assertValid();
        return new FabricStatus(this);
    }

    @Override
    public PatchService getPatchService() {
        assertValid();
        return new PatchServiceImpl(this);
    }

    @Override
    public String getDefaultJvmOptions() {
        assertValid();
        return dataStore.get().getDefaultJvmOptions();
    }

    @Override
    public void setDefaultJvmOptions(String jvmOptions) {
        assertValid();
        dataStore.get().setDefaultJvmOptions(jvmOptions);
    }

    @Override
    public String getConfigurationValue(String versionId, String profileId, String pid, String key) {
        assertValid();
        Profile pr = profileService.get().getRequiredProfile(versionId, profileId);
        Map<String, String> config = pr.getConfiguration(pid);

        if (config == null) {
            return null;
        }
        return config.get(key);
    }

    @Override
    public void setConfigurationValue(String versionId, String profileId, String pid, String key, String value) {
        assertValid();
        Version version = profileService.get().getRequiredVersion(versionId);
        Profile profile = version.getRequiredProfile(profileId);

        ProfileBuilder builder = ProfileBuilder.Factory.createFrom(profile);
        Map<String, String> config = builder.getConfiguration(pid);
        config.put(key, value);

        builder.addConfiguration(pid, config);
        profileService.get().updateProfile(builder.getProfile());
    }

    @Override
    public boolean scaleProfile(String profile, int numberOfInstances) throws IOException {
        if (numberOfInstances == 0) {
            throw new IllegalArgumentException("numberOfInstances should be greater or less than zero");
        }
        FabricRequirements requirements = getRequirements();
        ProfileRequirements profileRequirements = requirements.getOrCreateProfileRequirement(profile);
        Integer minimumInstances = profileRequirements.getMinimumInstances();
        List<Container> containers = Containers.containersForProfile(getContainers(), profile);
        int containerCount = containers.size();
        int newCount = containerCount + numberOfInstances;
        if (newCount < 0) {
            newCount = 0;
        }
        boolean update = minimumInstances == null || newCount != minimumInstances;
        if (update) {
            profileRequirements.setMinimumInstances(newCount);
            setRequirements(requirements);
        }
        return update;
    }

    @Override
    public ContainerAutoScaler createContainerAutoScaler(FabricRequirements requirements, ProfileRequirements profileRequirements) {
        Collection<ContainerProvider> providerCollection = getProviders().values();
        for (ContainerProvider containerProvider : providerCollection) {
            // lets pick the highest weighted autoscaler (e.g. to prefer openshift to docker to child
            SortedMap<Integer, ContainerAutoScaler> sortedAutoScalers = new TreeMap<Integer, ContainerAutoScaler>();
            if (containerProvider instanceof ContainerAutoScalerFactory) {
                ContainerAutoScalerFactory provider = (ContainerAutoScalerFactory) containerProvider;
                ContainerAutoScaler autoScaler = provider.createAutoScaler(requirements, profileRequirements);
                if (autoScaler != null) {
                    int weight = autoScaler.getWeight();
                    sortedAutoScalers.put(weight, autoScaler);
                }
            }
            if (!sortedAutoScalers.isEmpty()) {
                Integer key = sortedAutoScalers.lastKey();
                if (key != null) {
                    return sortedAutoScalers.get(key);
                }
            }
        }
        return null;
    }

    @Override
    public void leave() {
        String containerName = runtimeProperties.get().getRuntimeIdentity();

        // repeat the validation!

        boolean result = false;
        try {
            List<String> containerList = new ArrayList<String>();
            String clusterId = getStringData(curator.get(), ZkPath.CONFIG_ENSEMBLES.getPath());
            String containers = getStringData(curator.get(), ZkPath.CONFIG_ENSEMBLE.getPath(clusterId));
            Collections.addAll(containerList, containers.split(","));
            if (containerList.contains(containerName)) {
                LOGGER.warn("Current container is part of the ensemble, It can't leave the Fabric. Skipping the operation.");
                return;
            }
        } catch (Throwable ignored) {
        }

        Container c = getContainer(containerName);

        if (c.getMetadata() != null) {
            LOGGER.warn("Current container was created using Fabric. It should rather be removed using fabric:container-delete command. Skipping the operation.");
            return;
        }

        // are there any child containers of this container?
        List<String> dependent = new LinkedList<>();
        for (Container container : getContainers()) {
            while (container.getParent() != null) {
                if (containerName.equals(container.getParent().getId())) {
                    dependent.add(container.getId());
                }
                container = container.getParent();
            }
        }
        if (dependent.size() > 0) {
            LOGGER.warn("Current container has dependent containers (" + dependent + "). Can't disconnect it. Skipping the operation.");
            return;
        }

        LOGGER.info("Disconnecting current container from Fabric environment.");

        // collect bundle URIs we want to install
        Map<String, String> versions = c.getVersion().getProfile("default").getConfiguration("io.fabric8.version");
        String karafVersion = versions == null ? null : versions.get("karaf");

        if (karafVersion == null) {
            LOGGER.warn("Can't determine karaf version. Skipping the operation.");
            return;
        }

        String paxUrlLocation = null;
        int paxUrlStartLevel = -1;
        String bundleRepositoryLocation = null;
        int bundleRepositoryStartLevel = -1;
        int karafFeaturesStartLevel = -1;
        int karafObrStartLevel = -1;

        try {
            Properties startup = new Properties(new File(System.getProperty("karaf.etc"), "startup.properties"),
                    bundleContext);
            for (String key : startup.keySet()) {
                if (key.trim().startsWith("org/ops4j/pax/url/pax-url-aether/")) {
                    paxUrlStartLevel = Integer.parseInt(startup.get(key));
                    File system = new File(System.getProperty("karaf.home"), System.getProperty("karaf.default.repository"));
                    if (!system.isDirectory()) {
                        LOGGER.warn("Can't locate system repository inside " + System.getProperty("karaf.home") + ". Skipping the operation.");
                        return;
                    }
                    File paxUrlFile = new File(system, key);
                    if (!paxUrlFile.isFile()) {
                        LOGGER.warn("Can't locate " + key + " inside " + system + ". Skipping the operation.");
                        return;
                    }
                    paxUrlLocation = paxUrlFile.toURI().toURL().toString();
                } else if (key.trim().startsWith("org/apache/felix/org.apache.felix.bundlerepository/")) {
                    bundleRepositoryStartLevel = Integer.parseInt(startup.get(key));
                    String version = key.split("/")[4];
                    bundleRepositoryLocation = "mvn:org.apache.felix/org.apache.felix.bundlerepository/" + version;
                } else if (key.trim().startsWith("org/apache/karaf/features/org.apache.karaf.features.core")) {
                    karafFeaturesStartLevel = Integer.parseInt(startup.get(key));
                } else if (key.trim().startsWith("org/apache/karaf/features/org.apache.karaf.features.obr")) {
                    karafObrStartLevel = Integer.parseInt(startup.get(key));
                }
            }
        } catch (Exception e) {
            LOGGER.error("Can't process etc/startup.properties: " + e.getMessage() + " and determine bundle versions. Skipping the operation.", e);
            return;
        }

        // possible zookeeper.url in etc/system.properties
        try {
            Properties systemProperties = new Properties(new File(System.getProperty("karaf.etc"), "system.properties"),
                    bundleContext);
            if (systemProperties.containsKey("zookeeper.url") || systemProperties.containsKey("zookeeper.password")) {
                systemProperties.remove("zookeeper.url");
                systemProperties.remove("zookeeper.password");
                systemProperties.save();
            }
        } catch (Exception e) {
            LOGGER.error("Can't process etc/system.properties: " + e.getMessage() + " to remove zookeeper configuration. Skipping the operation.", e);
            return;
        }

        LOGGER.info("Deactivate io.fabric8.mbeanserver.listener");
        org.apache.felix.scr.Component[] scrs = scrService.get().getComponents("io.fabric8.mbeanserver.listener");
        if (scrs != null && scrs.length == 1) {
            scrs[0].disable();
        }
        LOGGER.info("Deactivate io.fabric8.extender.listener.blueprint");
        scrs = scrService.get().getComponents("io.fabric8.extender.listener.blueprint");
        if (scrs != null && scrs.length == 1) {
            scrs[0].disable();
        }
        LOGGER.info("Deactivate io.fabric8.extender.listener.spring");
        scrs = scrService.get().getComponents("io.fabric8.extender.listener.spring");
        if (scrs != null && scrs.length == 1) {
            scrs[0].disable();
        }

        BundleUtils bundleUtils = new BundleUtils(bundleContext);

        try {
            String sn = "io.fabric8.fabric-web";
            LOGGER.info("Stopping {}", sn);
            Bundle b = bundleUtils.findBundle(sn);
            b.stop(Bundle.STOP_TRANSIENT);

            sn = "io.fabric8.fabric-maven-proxy";
            LOGGER.info("Stopping {}", sn);
            b = bundleUtils.findBundle(sn);
            b.stop(Bundle.STOP_TRANSIENT);
        } catch (Exception e) {
            LOGGER.error("Can't stop fabric bundle: " + e.getMessage() + ". Container is not cleaned up properly.", e);
            return;
        }

        try {
            String sn = "io.fabric8.fabric-commands";
            LOGGER.info("Uninstalling {}", sn);
            Bundle b = bundleUtils.findBundle(sn);
            b.uninstall();

            sn = "io.fabric8.fabric-features-service";
            LOGGER.info("Uninstalling {}", sn);
            b = bundleUtils.findBundle(sn);
            b.uninstall();

            sn = "io.fabric8.fabric-core-agent-ssh";
            LOGGER.info("Uninstalling {}", sn);
            b = bundleUtils.findBundle(sn);
            b.uninstall();
        } catch (Exception e) {
            LOGGER.error("Can't uninstall fabric bundle: " + e.getMessage() + ". Container is not cleaned up properly.", e);
            return;
        }

        // deactivate git data store first, so we won't get anymore profile updates
        // but not entirely - leave SCR component (and its services) active, but close the ZK trigger
        LOGGER.info("Disconnecting profile registry from Git");
        profileRegistry.get().disconnect();
        LOGGER.info("Disconnecting datastore from ZooKeeper");
        dataStore.get().disconnect();

        // now we'll do something similar to this.destroyContainer() but without stopping and destroying it

        LOGGER.info("Unregistering ports of current container");
        portService.get().unregisterPort(c);

        // remove configuration/registry entries of this container from ZK - no files are removed
        LOGGER.info("Removing ZooKeeper configuration entries of current container");
        dataStore.get().deleteContainer(this, containerName);

        try {
            LOGGER.info("Installing {}", paxUrlLocation);
            Bundle b1 = bundleUtils.installBundle(paxUrlLocation);
            b1.adapt(BundleStartLevel.class).setStartLevel(paxUrlStartLevel);
            b1.start();

            String url = "mvn:org.apache.karaf.features/org.apache.karaf.features.core/" + karafVersion;
            LOGGER.info("Installing {}", url);
            Bundle b2 = bundleUtils.installBundle(url);
            b2.adapt(BundleStartLevel.class).setStartLevel(karafFeaturesStartLevel);
            b2.start();

            LOGGER.info("Installing {}", bundleRepositoryLocation);
            Bundle b3 = bundleUtils.installBundle(bundleRepositoryLocation);
            b3.adapt(BundleStartLevel.class).setStartLevel(bundleRepositoryStartLevel);
            b3.start();

            url = "mvn:org.apache.karaf.features/org.apache.karaf.features.obr/" + karafVersion;
            LOGGER.info("Installing {}", url);
            Bundle b4 = bundleUtils.installBundle(url);
            b4.adapt(BundleStartLevel.class).setStartLevel(karafObrStartLevel);
            b4.start();
        } catch (Exception e) {
            LOGGER.error("Can't install non-fabric bundles: " + e.getMessage() + ". Container is not cleaned up properly.", e);
            return;
        }

        try {
            // this will stop many fabric-related bundles
            System.getProperties().remove("zookeeper.url");
            System.getProperties().remove("zookeeper.password");
            Configuration config = configAdmin.get().getConfiguration("io.fabric8.zookeeper", null);
            LOGGER.info("Deleting {}", config);
            config.delete();
        } catch (Exception e) {
            LOGGER.error("Can't delete io.fabric8.zookeeper configuration: " + e.getMessage() + ". Container is not cleaned up properly.", e);
            return;
        }

        // now we can delete many more fabric-related configs
        try {
            Set<String> toRemove = new HashSet<>(Arrays.asList(
                    "io.fabric8.agent",
                    "io.fabric8.bootstrap.configuration",
                    "io.fabric8.configadmin.bridge.timestamp",
                    "io.fabric8.docker.provider",
                    "io.fabric8.environment",
                    "io.fabric8.jaas",
                    "io.fabric8.jolokia",
                    "io.fabric8.maven.proxy",
                    "io.fabric8.mq.fabric.template",
                    "io.fabric8.ports",
                    "io.fabric8.version"
            ));
            String[] factoryPids = new String[] {
                    "io.fabric8.mq.fabric.clustered.server",
                    "io.fabric8.mq.fabric.server",
                    "io.fabric8.zookeeper.server"
            };
            Configuration[] configs = configAdmin.get().listConfigurations(null);
            for (Configuration cfg : configs) {
                if (toRemove.contains(cfg.getPid())) {
                    LOGGER.info("Deleting {}", cfg.toString());
                    cfg.delete();
                } else {
                    try {
                        for (String factoryPid : factoryPids) {
                            if (factoryPid.equals(cfg.getFactoryPid())) {
                                LOGGER.info("Deleting {}", cfg.toString());
                                cfg.delete();
                            } else if (cfg.getFactoryPid() == null && cfg.getPid() != null
                                    && cfg.getPid().startsWith(factoryPid)) {
                                LOGGER.info("Deleting {}", cfg.toString());
                                cfg.delete();
                            }
                        }
                    } catch (java.lang.IllegalStateException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Can't process fabric-related configurations: " + e.getMessage() + ". Container is not cleaned up properly.", e);
            return;
        }

        // a bit hacky, but let's change jetty config
        try {
            Bundle fileinstall = bundleUtils.findBundle("org.apache.felix.fileinstall");
            fileinstall.stop(Bundle.STOP_TRANSIENT);
            Bundle paxWebRuntime = bundleUtils.findBundle("org.ops4j.pax.web.pax-web-runtime");
            fileinstall.stop(Bundle.STOP_TRANSIENT);

            Configuration cfg = configAdmin.get().getConfiguration("org.ops4j.pax.web", null);
            if (cfg != null && cfg.getProperties() != null) {
                cfg.getProperties().remove("org.ops4j.pax.web.config.url");
                cfg.getProperties().put("org.ops4j.pax.web.config.file", "etc/jetty.xml");
                LOGGER.info("Updating {}", cfg);
                cfg.update();
            }

            // update etc/org.ops4j.pax.web too, because fileinstall is stopped
            File paxWebConfigFile = new File(System.getProperty("karaf.etc"), "org.ops4j.pax.web.cfg");
            Properties paxWebProperties = new Properties(paxWebConfigFile, bundleContext);
            paxWebProperties.remove("org.ops4j.pax.web.config.url");
            paxWebProperties.put("org.ops4j.pax.web.config.file", "etc/jetty.xml");
            paxWebProperties.save();
        } catch (Exception e) {
            LOGGER.error("Can't process etc/org.ops4j.pax.web.cfg: " + e.getMessage() + " to restore non-fabric Jetty configuration. Skipping the operation.", e);
            return;
        }

        LOGGER.info("Restarting Fuse container.");
        try {
            System.setProperty("karaf.restart.jvm", "true");
            System.setProperty("karaf.restart", "true");
            bundleContext.getBundle(0).stop();
        } catch (BundleException e) {
            LOGGER.error("Can't restart container: " + e.getMessage(), e);
        }
    }

    public Map<String, Map<String, String>> substituteConfigurations(final Map<String, Map<String, String>> configurations) {
        return substituteConfigurations(configurations, null);
    }

    /**
     * Performs substitution to configuration based on the registered {@link PlaceholderResolver} instances.
     * @param configurations
     * @param profileId if passed, may be used to alter behavior of {@code profile:} URI handler
     */
    public Map<String, Map<String, String>> substituteConfigurations(final Map<String, Map<String, String>> configurations, final String profileId) {

        final Map<String, PlaceholderResolver> resolversSnapshot = new HashMap<String, PlaceholderResolver>(placeholderResolvers);

        // Check that all resolvers are available
        Set<String> requiredSchemes = getSchemesForProfileConfigurations(configurations);
        Set<String> availableSchemes = resolversSnapshot.keySet();
        if (!availableSchemes.containsAll(requiredSchemes)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Missing Placeholder Resolvers:");
            for (String scheme : requiredSchemes) {
                if (!availableSchemes.contains(scheme)) {
                    sb.append(" ").append(scheme);
                }
            }
            throw new FabricException(sb.toString());
        }

        final Map<String, Map<String, String>> mutableConfigurations = new HashMap<>();
        for (Entry<String, Map<String, String>> entry : configurations.entrySet()) {
            String key = entry.getKey();
            Map<String, String> value = new HashMap<>(entry.getValue());
            mutableConfigurations.put(key, value);
        }
        
        final FabricService fabricService = this;
        for (Map.Entry<String, Map<String, String>> entry : mutableConfigurations.entrySet()) {
            final String pid = entry.getKey();
            Map<String, String> props = entry.getValue();
            Map<String, String> original = new HashMap<>(props);
            for (Map.Entry<String, String> e : original.entrySet()) {
                final String key = e.getKey();
                final String value = e.getValue();
                try {
                    props.put(key, InterpolationHelper.substVars(value, key, null, props, new InterpolationHelper.ContainerAwareSubstitutionCallback() {
                        @Override
                        public String getProfileId() {
                            return profileId;
                        }

                        public String getValue(String toSubstitute) {
                            if (toSubstitute != null && toSubstitute.contains(":")) {
                                String scheme = toSubstitute.substring(0, toSubstitute.indexOf(":"));
                                if (!"profile".equals(scheme) && toSubstitute.contains("profile:") && getProfileId() != null && getProfileId().startsWith("#")) {
                                    // ENTESB-9865: if we're resolving profile: in the context of given container
                                    // we can't then use fabricService.get().getCurrentContainer()
                                    // but given container
                                    toSubstitute = toSubstitute + "?containerProfileId=" + getProfileId().substring(1);
                                }
                                return resolversSnapshot.get(scheme).resolve(fabricService, mutableConfigurations, pid, key, toSubstitute);
                            }
                            return substituteBundleProperty(toSubstitute, bundleContext);
                        }
                    }));
                } catch (EncryptionOperationNotPossibleException exception) {
                    LOGGER.warn("Error resolving " + key, exception);
                }
            }
        }
        
        return mutableConfigurations;
    }

    void bindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.bind(service);
    }
    void unbindConfigAdmin(ConfigurationAdmin service) {
        this.configAdmin.unbind(service);
    }

    @VisibleForTesting
    public void bindRuntimeProperties(RuntimeProperties service) {
        this.runtimeProperties.bind(service);
    }
    void unbindRuntimeProperties(RuntimeProperties service) {
        this.runtimeProperties.unbind(service);
    }

    @VisibleForTesting
    public void bindCurator(CuratorFramework service) {
        this.curator.bind(service);
    }
    void unbindCurator(CuratorFramework service) {
        this.curator.unbind(service);
    }

    @VisibleForTesting
    public void bindDataStore(DataStore service) {
        this.dataStore.bind(service);
    }
    void unbindDataStore(DataStore service) {
        this.dataStore.unbind(service);
    }

    void bindPortService(PortService service) {
        this.portService.bind(service);
    }
    void unbindPortService(PortService service) {
        this.portService.unbind(service);
    }

    void bindProfileService(ProfileService service) {
        profileService.bind(service);
    }
    void unbindProfileService(ProfileService service) {
        profileService.unbind(service);
    }
    
    void bindProfileRegistry(ProfileRegistry service) {
        this.profileRegistry.bind(service);
    }
    void unbindProfileRegistry(ProfileRegistry service) {
        this.profileRegistry.unbind(service);
    }
    
    void bindProvider(ContainerProvider provider) {
        providers.put(provider.getScheme(), provider);
    }
    void unbindProvider(ContainerProvider provider) {
        providers.remove(provider.getScheme());
    }

    void bindScrService(org.apache.felix.scr.ScrService service) {
        this.scrService.bind(service);
    }

    void unbindScrService(org.apache.felix.scr.ScrService service) {
        this.scrService.unbind(service);
    }

    @VisibleForTesting
    public void bindPlaceholderResolver(PlaceholderResolver resolver) {
        String resolverScheme = resolver.getScheme();
        placeholderResolvers.put(resolverScheme, resolver);
    }
    void unbindPlaceholderResolver(PlaceholderResolver resolver) {
        String resolverScheme = resolver.getScheme();
        placeholderResolvers.remove(resolverScheme);
    }
}
