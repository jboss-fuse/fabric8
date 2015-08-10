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
package io.fabric8.agent.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.fabric8.agent.download.DownloadCallback;
import io.fabric8.agent.download.DownloadManager;
import io.fabric8.agent.download.Downloader;
import io.fabric8.agent.download.StreamProvider;
import io.fabric8.agent.model.Feature;
import io.fabric8.agent.model.Repository;
import io.fabric8.agent.repository.StaticRepository;
import io.fabric8.agent.resolver.ResourceBuilder;
import io.fabric8.api.gravia.ServiceLocator;
import io.fabric8.common.util.ChecksumUtils;
import io.fabric8.common.util.MultiException;
import org.apache.felix.utils.version.VersionRange;
import org.apache.karaf.util.bundles.BundleUtils;
import org.eclipse.equinox.region.Region;
import org.eclipse.equinox.region.RegionDigraph;
import org.eclipse.equinox.region.RegionFilterBuilder;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.hooks.resolver.ResolverHook;
import org.osgi.framework.hooks.resolver.ResolverHookFactory;
import org.osgi.framework.namespace.ExecutionEnvironmentNamespace;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.startlevel.FrameworkStartLevel;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.fabric8.agent.internal.MapUtils.addToMapSet;
import static io.fabric8.agent.service.Constants.DEFAULT_BUNDLE_UPDATE_RANGE;
import static io.fabric8.agent.service.Constants.DEFAULT_FEATURE_RESOLUTION_RANGE;
import static io.fabric8.agent.service.Constants.Option;
import static io.fabric8.agent.service.Constants.ROOT_REGION;
import static io.fabric8.agent.service.Constants.UPDATE_SNAPSHOTS_CRC;
import static io.fabric8.agent.utils.AgentUtils.downloadRepositories;

public class Agent {

    private static final Logger LOGGER = LoggerFactory.getLogger(Agent.class);

    private final Bundle serviceBundle;
    private final BundleContext systemBundleContext;
    private final DownloadManager manager;
    private final FeatureConfigInstaller configInstaller;
    private final RegionDigraph digraph;
    private final int bundleStartTimeout;

    /**
     * Range to use when a version is specified on a feature dependency.
     * The default is {@link Constants#DEFAULT_FEATURE_RESOLUTION_RANGE}
     */
    private final String featureResolutionRange;
    /**
     * Range to use when verifying if a bundle should be updated or
     * new bundle installed.
     * The default is {@link Constants#DEFAULT_BUNDLE_UPDATE_RANGE}
     */
    private final String bundleUpdateRange;
    /**
     * Use CRC to check snapshot bundles and update them if changed.
     * Either:
     * - none : never update snapshots
     * - always : always update snapshots
     * - crc : use CRC to detect changes
     */
    private final String updateSnaphots;

    private final StateStorage storage;
    private EnumSet<Option> options = EnumSet.noneOf(Option.class);

    public Agent(Bundle serviceBundle, BundleContext systemBundleContext, DownloadManager manager) {
        this(serviceBundle, systemBundleContext, manager, null, null, DEFAULT_FEATURE_RESOLUTION_RANGE, DEFAULT_BUNDLE_UPDATE_RANGE, UPDATE_SNAPSHOTS_CRC, null, Constants.BUNDLE_START_TIMEOUT);
    }

    public Agent(
            Bundle serviceBundle,
            BundleContext systemBundleContext,
            DownloadManager manager,
            FeatureConfigInstaller configInstaller,
            RegionDigraph digraph,
            String featureResolutionRange,
            String bundleUpdateRange,
            String updateSnaphots,
            File stateFile,
            int bundleStartTimeout) {
        this.serviceBundle = serviceBundle;
        this.systemBundleContext = systemBundleContext;
        this.manager = manager;
        this.configInstaller = configInstaller;
        this.digraph = digraph;
        this.featureResolutionRange = featureResolutionRange;
        this.bundleUpdateRange = bundleUpdateRange;
        this.updateSnaphots = updateSnaphots;
        this.bundleStartTimeout = bundleStartTimeout;

        final File file = stateFile;
        storage = new StateStorage() {
            @Override
            protected InputStream getInputStream() throws IOException {
                if (file != null && file.exists()) {
                    return new FileInputStream(file);
                } else {
                    return null;
                }
            }

            @Override
            protected OutputStream getOutputStream() throws IOException {
                if (file != null) {
                    return new FileOutputStream(file);
                } else {
                    return null;
                }
            }
        };
    }

    public void updateStatus(String status) {
    }

    public void provision(Set<String> repositories,
                          Set<String> features,
                          Set<String> bundles,
                          Set<String> reqs,
                          Set<String> overrides,
                          Set<String> optionals,
                          Map<String, Map<VersionRange, Map<String, String>>> metadata
    ) throws Exception {

        updateStatus("downloading");

        Callable<Map<String, Repository>> repos = downloadRepositories(manager, repositories);

        Map<String, Feature> allFeatures = new HashMap<>();
        for (Repository repository : repos.call().values()) {
            for (Feature f : repository.getFeatures()) {
                String id = f.getId();
                if (allFeatures.put(id, f) != null) {
                    throw new IllegalStateException("Duplicate feature found: " + id);
                }
            }
        }

        provision(allFeatures, features, bundles, reqs, overrides, optionals, metadata);
    }

    public void provision(Map<String, Feature> allFeatures,
                          Set<String> features,
                          Set<String> bundles,
                          Set<String> reqs,
                          Set<String> overrides,
                          Set<String> optionals,
                          Map<String, Map<VersionRange, Map<String, String>>> metadata
    ) throws Exception {


        Callable<Map<String, Resource>> res = loadResources(manager, metadata, optionals);

        // TODO: requirements should be able to be assigned to a region
        Map<String, Set<String>> requirements = new HashMap<>();
        for (String feature : features) {
            addToMapSet(requirements, ROOT_REGION, "feature:" + feature);
        }
        for (String bundle : bundles) {
            addToMapSet(requirements, ROOT_REGION, "bundle:" + bundle);
        }
        for (String req : reqs) {
            addToMapSet(requirements, ROOT_REGION, "req:" + req);
        }

        Deployer.DeploymentRequest request = new Deployer.DeploymentRequest();
        request.updateSnaphots = updateSnaphots;
        request.bundleUpdateRange = bundleUpdateRange;
        request.featureResolutionRange = featureResolutionRange;
        request.globalRepository = new StaticRepository(res.call().values());
        request.overrides = overrides;
        request.requirements = requirements;
        request.stateChanges = Collections.emptyMap();
        request.options = options;
        request.metadata = metadata;
        request.bundleStartTimeout = bundleStartTimeout;

        Deployer.DeploymentState dstate = new Deployer.DeploymentState();
        // Service bundle
        dstate.serviceBundle = serviceBundle;
        // Start level
        FrameworkStartLevel fsl = systemBundleContext.getBundle().adapt(FrameworkStartLevel.class);
        dstate.initialBundleStartLevel = fsl.getInitialBundleStartLevel();
        dstate.currentStartLevel = fsl.getStartLevel();
        // Bundles
        dstate.bundles = new HashMap<>();
        for (Bundle bundle : systemBundleContext.getBundles()) {
            dstate.bundles.put(bundle.getBundleId(), bundle);
        }
        // Features
        dstate.features = allFeatures;
        // Region -> bundles mapping
        // Region -> policy mapping
        dstate.bundlesPerRegion = new HashMap<>();
        dstate.filtersPerRegion = new HashMap<>();
        if (digraph == null) {
            for (Long id : dstate.bundles.keySet()) {
                addToMapSet(dstate.bundlesPerRegion, ROOT_REGION, id);
            }
        } else {
            RegionDigraph clone = digraph.copy();
            for (Region region : clone.getRegions()) {
                // Get bundles
                dstate.bundlesPerRegion.put(region.getName(), new HashSet<>(region.getBundleIds()));
                // Get policies
                Map<String, Map<String, Set<String>>> edges = new HashMap<>();
                for (RegionDigraph.FilteredRegion fr : clone.getEdges(region)) {
                    Map<String, Set<String>> policy = new HashMap<>();
                    Map<String, Collection<String>> current = fr.getFilter().getSharingPolicy();
                    for (String ns : current.keySet()) {
                        for (String f : current.get(ns)) {
                            addToMapSet(policy, ns, f);
                        }
                    }
                    edges.put(fr.getRegion().getName(), policy);
                }
                dstate.filtersPerRegion.put(region.getName(), edges);
            }
        }

        final State state = new State();
        try {
            storage.load(state);
        } catch (IOException e) {
            LOGGER.warn("Error loading agent state", e);
        }
        if (state.managedBundles.isEmpty()) {
            for (Bundle b : systemBundleContext.getBundles()) {
                if (b.getBundleId() != 0) {
                    addToMapSet(state.managedBundles, ROOT_REGION, b.getBundleId());
                }
            }
        }
        // Load bundle checksums if not already done
        // This is a bit hacky, but we can't get a hold on the real bundle location
        // in a standard way in OSGi.  Therefore, hack into Felix to obtain the
        // corresponding jar url and use that one to compute the checksum of the bundle.
        for (Map.Entry<Long, Bundle> entry : dstate.bundles.entrySet()) {
            long id = entry.getKey();
            Bundle bundle = entry.getValue();
            if (id > 0 && isUpdateable(bundle) && !state.bundleChecksums.containsKey(id)) {
                try {
                    URL url = bundle.getResource("META-INF/MANIFEST.MF");
                    URLConnection con = url.openConnection();
                    Method method = con.getClass().getDeclaredMethod("getLocalURL");
                    method.setAccessible(true);
                    String jarUrl = ((URL) method.invoke(con)).toExternalForm();
                    if (jarUrl.startsWith("jar:")) {
                        String jar = jarUrl.substring("jar:".length(), jarUrl.indexOf("!/"));
                        jar = new URL(jar).getFile();
                        long checksum = ChecksumUtils.checksumFile(new File(jar));
                        state.bundleChecksums.put(id, checksum);
                    }
                } catch (Throwable t) {
                    LOGGER.debug("Error calculating checksum for bundle: %s", bundle, t);
                }
            }
        }
        dstate.state = state;

        Set<String> prereqs = new HashSet<>();
        while (true) {
            try {
                Deployer.DeployCallback callback = new BaseDeployCallback() {
                    @Override
                    public void phase(String message) {
                        Agent.this.updateStatus(message);
                    }
                    @Override
                    public void saveState(State newState) {
                        state.replace(newState);
                        try {
                            Agent.this.saveState(newState);
                        } catch (IOException e) {
                            LOGGER.warn("Error storing agent state", e);
                        }
                    }
                    @Override
                    public void provisionList(Set<Resource> resources) {
                        Agent.this.provisionList(resources);
                    }
                };

                // FABRIC-790, FABRIC-981 - wait for ProfileUrlHandler before attempting to load bundles (in subsystem.resolve())
                // (which may be the case with bundle.xxx=blueprint:profile:xxx URLs in io.fabric8.agent PID)
                // https://developer.jboss.org/message/920681 - 30 seconds is too low sometimes
                // there was "url.handler.timeouts" option for agent, but it was removed during migration to karaf 4.x resolver
//                LOGGER.debug("Waiting for ProfileUrlHandler");
//                awaitService(URLStreamHandlerService.class, "(url.handler.protocol=profile)", 30, TimeUnit.SECONDS);
//                LOGGER.debug("Waiting for ProfileUrlHandler finished");

                Deployer deployer = new Deployer(manager, callback);
                deployer.deploy(dstate, request);
                break;
            } catch (Deployer.PartialDeploymentException e) {
                if (!prereqs.containsAll(e.getMissing())) {
                    prereqs.addAll(e.getMissing());
                } else {
                    throw new Exception("Deployment aborted due to loop in missing prerequisites: " + e.getMissing());
                }
            }
        }
    }

    protected <T> void awaitService(Class<T> serviceClass, String filterspec, int timeout, TimeUnit timeUnit) {
        ServiceLocator.awaitService(systemBundleContext, serviceClass, filterspec, timeout, timeUnit);
    }

    protected boolean isUpdateable(Bundle bundle) {
        String uri = bundle.getLocation();
        return uri.matches(Constants.UPDATEABLE_URIS);
    }

    protected void saveState(State newState) throws IOException {
        storage.save(newState);
    }

    protected void provisionList(Set<Resource> resources) {
    }

    public void setOptions(EnumSet<Option> options) {
        this.options = options;
    }

    public EnumSet<Option> getOptions() {
        return options;
    }

    abstract class BaseDeployCallback implements Deployer.DeployCallback {

        public void print(String message, int display) {
            if ((display & Deployer.DISPLAY_LOG) != 0)
                LOGGER.info(message);
            if ((display & Deployer.DISPLAY_STDOUT) != 0)
                System.out.println(message);
        }

        public void refreshPackages(Collection<Bundle> bundles) throws InterruptedException {
            final CountDownLatch latch = new CountDownLatch(1);
            FrameworkWiring fw = systemBundleContext.getBundle().adapt(FrameworkWiring.class);
            fw.refreshBundles(bundles, new FrameworkListener() {
                @Override
                public void frameworkEvent(FrameworkEvent event) {
                    if (event.getType() == FrameworkEvent.ERROR) {
                        LOGGER.error("Framework error", event.getThrowable());
                    }
                    latch.countDown();
                }
            });
            latch.await(30, TimeUnit.SECONDS);
        }

        @Override
        public void persistResolveRequest(Deployer.DeploymentRequest request) throws IOException {
            // Don't do anything here, as the resolver will start again from scratch anyway
        }

        @Override
        public void installFeatureConfigs(Feature feature) throws IOException, InvalidSyntaxException {
            if (configInstaller != null) {
                configInstaller.installFeatureConfigs(feature);
            }
        }

        @Override
        public Bundle installBundle(String region, String uri, InputStream is) throws BundleException {
            if (digraph == null) {
                if (ROOT_REGION.equals(region)) {
                    return systemBundleContext.installBundle(uri, is);
                } else {
                    throw new IllegalStateException("Can not install the bundle " + uri + " in the region " + region + " as regions are not supported");
                }
            } else {
                if (ROOT_REGION.equals(region)) {
                    return digraph.getRegion(region).installBundleAtLocation(uri, is);
                } else {
                    return digraph.getRegion(region).installBundle(uri, is);
                }
            }
        }

        @Override
        public void updateBundle(Bundle bundle, String uri, InputStream is) throws BundleException {
            // We need to wrap the bundle to insert a Bundle-UpdateLocation header
            try {
                File file = BundleUtils.fixBundleWithUpdateLocation(is, uri);
                bundle.update(new FileInputStream(file));
                file.delete();
            } catch (IOException e) {
                throw new BundleException("Unable to update bundle", e);
            }
        }

        @Override
        public void uninstall(Bundle bundle) throws BundleException {
            bundle.uninstall();
        }

        @Override
        public void startBundle(Bundle bundle) throws BundleException {
            bundle.start();
        }

        @Override
        public void stopBundle(Bundle bundle, int options) throws BundleException {
            bundle.stop(options);
        }

        @Override
        public void setBundleStartLevel(Bundle bundle, int startLevel) {
            bundle.adapt(BundleStartLevel.class).setStartLevel(startLevel);
        }

        @Override
        public void resolveBundles(Set<Bundle> bundles, final Map<Resource, List<Wire>> wiring, Map<Resource, Bundle> resToBnd) {
            // Make sure it's only used for us
            final Thread thread = Thread.currentThread();
            // Translate wiring
            final Map<Bundle, Resource> bndToRes = new HashMap<>();
            for (Resource res : resToBnd.keySet()) {
                bndToRes.put(resToBnd.get(res), res);
            }
            // Hook
            final ResolverHook hook = new ResolverHook() {
                @Override
                public void filterResolvable(Collection<BundleRevision> candidates) {
                }
                @Override
                public void filterSingletonCollisions(BundleCapability singleton, Collection<BundleCapability> collisionCandidates) {
                }
                @Override
                public void filterMatches(BundleRequirement requirement, Collection<BundleCapability> candidates) {
                    if (Thread.currentThread() == thread) {
                        // osgi.ee capabilities are provided by the system bundle, so just ignore those
                        if (ExecutionEnvironmentNamespace.EXECUTION_ENVIRONMENT_NAMESPACE
                                .equals(requirement.getNamespace())) {
                            return;
                        }
                        Bundle sourceBundle = requirement.getRevision().getBundle();
                        Resource sourceResource = bndToRes.get(sourceBundle);
                        Set<Resource> wired = new HashSet<>();
                        // Get a list of allowed wired resources
                        wired.add(sourceResource);
                        for (Wire wire : wiring.get(sourceResource)) {
                            wired.add(wire.getProvider());
                            if (HostNamespace.HOST_NAMESPACE.equals(wire.getRequirement().getNamespace())) {
                                for (Wire hostWire : wiring.get(wire.getProvider())) {
                                    wired.add(hostWire.getProvider());
                                }
                            }
                        }
                        // Remove candidates that are not allowed
                        for (Iterator<BundleCapability> candIter = candidates.iterator(); candIter.hasNext(); ) {
                            BundleCapability cand = candIter.next();
                            BundleRevision br = cand.getRevision();
                            Resource res = bndToRes.get(br.getBundle());
                            if (!wired.contains(br) && !wired.contains(res)) {
                                candIter.remove();
                            }
                        }
                    }
                }
                @Override
                public void end() {
                }
            };
            ResolverHookFactory factory = new ResolverHookFactory() {
                @Override
                public ResolverHook begin(Collection<BundleRevision> triggers) {
                    return hook;
                }
            };
            ServiceRegistration<ResolverHookFactory> registration = systemBundleContext.registerService(ResolverHookFactory.class, factory, null);
            try {
                // Given we already have computed the wiring,
                // there's no need to resolve the bundles all at the same time.
                // It's much more efficient to resolve them by small chunks.
                // We could be even smarter and order the bundles according to the
                // order given by RequirementSort to minimize the size of needed chunks.
                FrameworkWiring frameworkWiring = systemBundleContext.getBundle().adapt(FrameworkWiring.class);
                for (Bundle bundle : bundles) {
                    frameworkWiring.resolveBundles(Collections.singleton(bundle));
                }
            } finally {
                registration.unregister();
            }
        }

        @Override
        public void replaceDigraph(Map<String, Map<String, Map<String, Set<String>>>> policies, Map<String, Set<Long>> bundles) throws BundleException, InvalidSyntaxException {
            if (digraph == null) {
                if (policies.size() >= 1 && !policies.containsKey(ROOT_REGION)
                        || bundles.size() >= 1 && !bundles.containsKey(ROOT_REGION)) {
                    throw new IllegalStateException("Can not update non trivial digraph as regions are not supported");
                }
                return;
            }
            RegionDigraph temp = digraph.copy();
            // Remove everything
            for (Region region : temp.getRegions()) {
                temp.removeRegion(region);
            }
            // Re-create regions
            for (String name : policies.keySet()) {
                temp.createRegion(name);
            }
            // Dispatch bundles
            for (Map.Entry<String, Set<Long>> entry : bundles.entrySet()) {
                Region region = temp.getRegion(entry.getKey());
                for (long bundleId : entry.getValue()) {
                    region.addBundle(bundleId);
                }
            }
            // Add policies
            for (Map.Entry<String, Map<String, Map<String, Set<String>>>> entry1 : policies.entrySet()) {
                Region region1 = temp.getRegion(entry1.getKey());
                for (Map.Entry<String, Map<String, Set<String>>> entry2 : entry1.getValue().entrySet()) {
                    Region region2 = temp.getRegion(entry2.getKey());
                    RegionFilterBuilder rfb = temp.createRegionFilterBuilder();
                    for (Map.Entry<String, Set<String>> entry3 : entry2.getValue().entrySet()) {
                        for (String flt : entry3.getValue()) {
                            rfb.allow(entry3.getKey(), flt);
                        }
                    }
                    region1.connectRegion(region2, rfb.build());
                }
            }
            digraph.replace(temp);
        }
    }

    //
    // State support
    //

    public static Callable<Map<String, Resource>> loadResources(
                DownloadManager manager,
                Map<String, Map<VersionRange, Map<String, String>>> metadata,
                Set<String> uris)
            throws MultiException, InterruptedException, MalformedURLException {
        final Map<String, Resource> resources = new HashMap<>();
        final Downloader downloader = manager.createDownloader();
        final MetadataBuilder builder = new MetadataBuilder(metadata);
        final DownloadCallback callback = new DownloadCallback() {
            @Override
            public void downloaded(StreamProvider provider) throws Exception {
                String uri = provider.getUrl();
                Map<String, String> headers = builder.getMetadata(uri, provider.getFile());
                Resource resource = ResourceBuilder.build(uri, headers);
                synchronized (resources) {
                    resources.put(uri, resource);
                }
            }
        };
        for (String uri : uris) {
            downloader.download(uri, callback);
        }
        return new Callable<Map<String, Resource>>() {
            @Override
            public Map<String, Resource> call() throws Exception {
                downloader.await();
                return resources;
            }
        };
    }


}
