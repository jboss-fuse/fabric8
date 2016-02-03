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
package io.fabric8.autoscale;

import io.fabric8.api.AutoScaleProfileStatus;
import io.fabric8.api.AutoScaleRequest;
import io.fabric8.api.AutoScaleStatus;
import io.fabric8.api.Container;
import io.fabric8.api.ContainerAutoScaler;
import io.fabric8.api.Containers;
import io.fabric8.api.DataStore;
import io.fabric8.api.FabricRequirements;
import io.fabric8.api.FabricService;
import io.fabric8.api.ProfileRequirements;
import io.fabric8.api.jcip.GuardedBy;
import io.fabric8.api.jcip.ThreadSafe;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.common.util.Closeables;
import io.fabric8.common.util.Strings;
import io.fabric8.groups.Group;
import io.fabric8.groups.GroupListener;
import io.fabric8.groups.internal.ZooKeeperGroup;
import io.fabric8.internal.RequirementsJson;
import io.fabric8.internal.autoscale.AutoScalers;
import io.fabric8.zookeeper.ZkPath;
import io.fabric8.zookeeper.utils.ZooKeeperMasterCache;
import org.apache.curator.framework.CuratorFramework;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Fabric auto-scaler which when it becomes the master auto-scales
 * profiles according to their requirements defined via
 * {@link FabricService#setRequirements(io.fabric8.api.FabricRequirements)}
 */
@ThreadSafe
@Component(name = "io.fabric8.autoscale", label = "Fabric8 auto scaler", immediate = true,
        policy = ConfigurationPolicy.REQUIRE, metatype = true)
public final class AutoScaleController extends AbstractComponent implements GroupListener<AutoScalerNode> {
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoScaleController.class);

    @Reference(referenceInterface = CuratorFramework.class, bind = "bindCurator", unbind = "unbindCurator")
    private final ValidatingReference<CuratorFramework> curator = new ValidatingReference<CuratorFramework>();
    @Reference(referenceInterface = FabricService.class, bind = "bindFabricService", unbind = "unbindFabricService")
    private final ValidatingReference<FabricService> fabricService = new ValidatingReference<FabricService>();

    @Property(value = "10000", label = "Poll period", description = "The number of milliseconds between polls to check if the system still has its requirements satisfied.")
    private static final String POLL_TIME = "pollTime";
    private Long pollTime;
    @Property(value = "", label = "Profile name pattern", description = "Profiles matching this regex will be autoscaled.")
    private static final String PROFILE_PATTERN = "profilePattern";
    private String profilePattern;
    @Property(value = "", label = "Container name pattern", description = "Containers matching this regex will be use for assignment autoscaling.")
    private static final String CONTAINER_PATTERN = "containerPattern";
    private String containerPattern;
    @Property(value = "true", label = "Scale containers", description = "true = scale with containers, false = scale with profile assignments.")
    private static final String SCALE_CONTAINERS = "scaleContainers";
    private Boolean scaleContainers;
    @Property(value = "1", label = "Default maximum instances per host", description = "Default maximum for instances per host when profile doesn't define it.")
    private static final String DEFAULT_MAXIMUM_INSTANCES_PER_HOST = "defaultMaximumInstancesPerHost";
    private Integer defaultMaximumInstancesPerHost;
    @Property(value = "default", label = "Autoscaler group ID", description = "ID for the autoscaler group.")
    private static final String AUTOSCALER_GROUP_ID = "autoscalerGroupId";
    private String autoscalerGroupId;
    @Property(value = "1", label = "Minimum number of containers", description = "Minimum number of applicable containers to perform autoscaling.")
    private static final String MINIMUM_CONTAINER_COUNT = "minimumContainerCount";
    private Integer minimumContainerCount;
    @Property(value = "1", label = "Maximum deviation, n * average (n >= 0)", description = "If one container has more than average + (n * average) profiles assigned, the excess will be reassigned.")
    private static final String MAXIMUM_DEVIATION = "maximumDeviation";
    private Integer maximumDeviation;

    private AtomicReference<Timer> timer = new AtomicReference<Timer>();

    @GuardedBy("volatile")
    private volatile Group<AutoScalerNode> group;

    private Runnable runnable = new Runnable() {
        @Override
        public void run() {
            onConfigurationChanged();
        }
    };
    private ZooKeeperMasterCache zkMasterCache;

    @Activate
    void activate(final Map<String, String> properties) {
        this.pollTime = Long.parseLong(properties.get(POLL_TIME));
        this.profilePattern = properties.get(PROFILE_PATTERN);
        this.containerPattern = properties.get(CONTAINER_PATTERN);
        this.scaleContainers = Boolean.parseBoolean(properties.get(SCALE_CONTAINERS));
        this.defaultMaximumInstancesPerHost = Integer.parseInt(properties.get(DEFAULT_MAXIMUM_INSTANCES_PER_HOST));
        this.autoscalerGroupId = properties.get(AUTOSCALER_GROUP_ID);
        this.minimumContainerCount = Integer.parseInt(properties.get(MINIMUM_CONTAINER_COUNT));
        this.maximumDeviation = Integer.parseInt(properties.get(MAXIMUM_DEVIATION)) >= 0 ? Integer.parseInt(properties.get(MAXIMUM_DEVIATION)) : 1;
        CuratorFramework curator = this.curator.get();
        enableMasterZkCache(curator);
        group = new ZooKeeperGroup<AutoScalerNode>(curator, ZkPath.AUTO_SCALE_CLUSTER.getPath(autoscalerGroupId), AutoScalerNode.class);
        group.add(this);
        group.update(createState());
        group.start();
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        disableMasterZkCache();
        disableTimer();
        deactivateComponent();
        group.remove(this);
        Closeables.closeQuietly(group);
        group = null;
    }

    @Override
    public void groupEvent(Group<AutoScalerNode> group, GroupEvent event) {
        DataStore dataStore = fabricService.get().adapt(DataStore.class);
        switch (event) {
            case CONNECTED:
            case CHANGED:
                if (isValid()) {
                    AutoScalerNode state = createState();
                    try {
                        if (group.isMaster()) {
                            enableMasterZkCache(curator.get());
                            LOGGER.info("AutoScaleController is the master");
                            group.update(state);
                            dataStore.trackConfiguration(runnable);
                            enableTimer();
                            onConfigurationChanged();
                        } else {
                            LOGGER.info("AutoScaleController is not the master");
                            group.update(state);
                            disableTimer();
                            dataStore.untrackConfiguration(runnable);
                            disableMasterZkCache();
                        }
                    } catch (IllegalStateException e) {
                        // Ignore
                    }
                } else {
                    LOGGER.info("Not valid with master: " + group.isMaster()
                            + " fabric: " + fabricService.get()
                            + " curator: " + curator.get());
                }
                break;
            case DISCONNECTED:
                dataStore.untrackConfiguration(runnable);
        }
    }


    protected void enableMasterZkCache(CuratorFramework curator) {
        zkMasterCache = new ZooKeeperMasterCache(curator);
    }

    protected void disableMasterZkCache() {
        if (zkMasterCache != null) {
            zkMasterCache = null;
        }
    }

    protected void enableTimer() {
        Timer newTimer = new Timer("fabric8-autoscaler");
        if (timer.compareAndSet(null, newTimer)) {
            TimerTask timerTask = new TimerTask() {
                @Override
                public void run() {
                    LOGGER.debug("autoscale timer");
                    autoScale();
                }
            };
            newTimer.schedule(timerTask, pollTime, pollTime);
        }
    }

    protected void disableTimer() {
        Timer oldValue = timer.getAndSet(null);
        if (oldValue != null) {
            oldValue.cancel();
        }
    }


    private void onConfigurationChanged() {
        LOGGER.debug("Configuration has changed; so checking the auto-scaling requirements");
        autoScale();
    }

    private void autoScale() {
        FabricService service = fabricService.get();
        FabricRequirements requirements = service.getRequirements();
        List<ProfileRequirements> profileRequirements = requirements.getProfileRequirements();
        // Filter profiles according to the profile pattern
        if (!profilePattern.isEmpty()) {
            Matcher matcher = Pattern.compile(profilePattern).matcher("");
            Iterator<ProfileRequirements> it = profileRequirements.iterator();
            while (it.hasNext()) {
                ProfileRequirements p = it.next();
                if (!matcher.reset(p.getProfile()).matches()) {
                    it.remove();
                }
            }
        }
        if (profileRequirements != null && !profileRequirements.isEmpty()) {
            AutoScaleStatus status = new AutoScaleStatus();
            if (scaleContainers) { // Scale with containers
                for (ProfileRequirements profileRequirement : profileRequirements) {
                    ContainerAutoScaler autoScaler = createAutoScaler(requirements, profileRequirement);
                    if (autoScaler != null) {
                        autoScaleProfile(service, autoScaler, requirements, profileRequirement, status);
                    } else {
                        LOGGER.warn("No ContainerAutoScaler available for profile " + profileRequirement.getProfile());
                    }
                }
            } else { // Scale with profile assignments
                autoScaleProfileAssignments(service, requirements, profileRequirements);
            }
            if (zkMasterCache != null) {
                try {
                    String json = RequirementsJson.toJSON(status);
                    String zkPath = ZkPath.AUTO_SCALE_STATUS.getPath(autoscalerGroupId);
                    zkMasterCache.setStringData(zkPath, json, CreateMode.EPHEMERAL);
                } catch (Exception e) {
                    LOGGER.warn("Failed to write autoscale status " + e, e);
                }
            } else {
                LOGGER.warn("No ZooKeeperMasterCache!");
            }
        }
    }

    private ContainerAutoScaler createAutoScaler(FabricRequirements requirements, ProfileRequirements profileRequirements) {
        FabricService service = fabricService.getOptional();
        if (service != null) {
            return service.createContainerAutoScaler(requirements, profileRequirements);
        } else {
            LOGGER.warn("No FabricService available so cannot autoscale");
            return null;
        }
    }

    private void autoScaleProfile(FabricService service, final ContainerAutoScaler autoScaler, FabricRequirements requirements, ProfileRequirements profileRequirement, AutoScaleStatus status) {
        final String profile = profileRequirement.getProfile();
        Integer minimumInstances = profileRequirement.getMinimumInstances();
        Integer maximumInstances = profileRequirement.getMaximumInstances();
        if (maximumInstances != null) {
            List<Container> containers = Containers.aliveAndSuccessfulContainersForProfile(profile, service);
            int count = containers.size();
            int delta = count - maximumInstances;
            if (delta > 0) {
                stopContainers(containers, autoScaler, requirements, profileRequirement, status, delta);
            }
        }
        if (minimumInstances != null) {
            // lets check if we need to provision more
            List<Container> containers = Containers.aliveOrPendingContainersForProfile(profile, service);
            int count = containers.size();
            int delta = minimumInstances - count;
            try {
                AutoScaleProfileStatus profileStatus = status.profileStatus(profile);
                if (delta < 0) {
                    profileStatus.destroyingContainer();
                    autoScaler.destroyContainers(profile, -delta, containers);
                } else if (delta > 0) {
                    if (AutoScalers.requirementsSatisfied(service, requirements, profileRequirement, status)) {
                        profileStatus.creatingContainer();
                        String requirementsVersion = requirements.getVersion();
                        final String version = Strings.isNotBlank(requirementsVersion) ? requirementsVersion : service.getDefaultVersionId();
                        final AutoScaleRequest command = new AutoScaleRequest(service, version, profile, delta, requirements, profileRequirement, status);
                        new Thread("Creating container for " + command.getProfile()) {
                            @Override
                            public void run() {
                                try {
                                    autoScaler.createContainers(command);
                                } catch (Exception e) {
                                    LOGGER.error("Failed to create container of profile: " + profile + ". Caught: " + e, e);
                                }
                            }
                        }.start();
                    }
                } else {
                    profileStatus.provisioned();
                }
            } catch (Exception e) {
                LOGGER.error("Failed to auto-scale " + profile + ". Caught: " + e, e);
            }
        }
    }

    // Return the preferred maximum assignment count for a single container
    private static int getMaxAssignmentsPerContainer(int containerCount, int profileCount, double factor) {
        int profilesPerContainerAverage = (int)Math.ceil(Math.abs(profileCount) / Math.abs(containerCount));
        return profilesPerContainerAverage + (int)Math.round(Math.abs(factor) * profilesPerContainerAverage);
    }

    private void autoScaleProfileAssignments(FabricService service, FabricRequirements requirements, List<ProfileRequirements> profileRequirements) {
        final Map<Container, ContainerJob> allContainerJobs = new HashMap<>();

        // Create matcher for the container pattern
        final Matcher containerMatcher;
        if (!containerPattern.isEmpty()) {
            containerMatcher = Pattern.compile(containerPattern).matcher("");
        } else {
            containerMatcher = Pattern.compile(".*").matcher("");
        }

        // Create matcher for the profile pattern
        final Matcher profileMatcher;
        if (!profilePattern.isEmpty()) {
            profileMatcher = Pattern.compile(profilePattern).matcher("");
        } else {
            profileMatcher = Pattern.compile(".*").matcher("");
        }

        try {
            // Collect all applicable containers
            for (Container container : Arrays.asList(service.getContainers())) {
                if (containerMatcher.reset(container.getId()).matches() && container.isAlive()) {
                    allContainerJobs.put(container, new ContainerJob(container));
                }
            }

            // Exit if we don't have enough containers
            if (allContainerJobs.size() < minimumContainerCount) {
                LOGGER.error("{} containers required for autoscaling, {} available.", minimumContainerCount, allContainerJobs.size());
                return;
            }

            // Collect current profile assignments
            for (ProfileRequirements profileRequirement : profileRequirements) {
                final String profileId = profileRequirement.getProfile();
                final List<Container> containers = Containers.aliveOrPendingContainersForProfile(profileId, service);
                for (Container container : containers) {
                    if (allContainerJobs.containsKey(container)) {
                        allContainerJobs.get(container).addProfile(profileId);
                    }
                }
            }

            // Calculate max profile assignments per container
            final int maxProfilesPerContainer = getMaxAssignmentsPerContainer(allContainerJobs.size(), profileRequirements.size(), maximumDeviation);

            // Check against max profile assignments per container
            for (ContainerJob containerJob : allContainerJobs.values()) {
                containerJob.removeProfiles(containerJob.getProfileCount() - maxProfilesPerContainer);
            }

            for (ProfileRequirements profileRequirement : profileRequirements) {
                final String profileId = profileRequirement.getProfile();
                final Integer minimumInstances = profileRequirement.getMinimumInstances();
                final Integer maximumInstances = profileRequirement.getMaximumInstances();
                final Integer maximumInstancesPerHost = (profileRequirement.getMaximumInstancesPerHost() != null) ? profileRequirement.getMaximumInstancesPerHost() : defaultMaximumInstancesPerHost;

                // Get containers with the profile
                List<ContainerJob> containerJobsForProfile = getContainerJobsForProfile(allContainerJobs.values(), profileId);

                // Container with least profiles first
                Collections.sort(containerJobsForProfile, new SortContainerJobsByProfileCount());

                // Check against max profile instances per host
                Map<String, Integer> instancesPerHosts = new HashMap<>();
                for (ContainerJob containerJob : containerJobsForProfile) {
                    int instancesPerHost = 0;
                    if (instancesPerHosts.get(containerJob.getIp()) != null) {
                        instancesPerHost = instancesPerHosts.get(containerJob.getIp());
                    }
                    if (instancesPerHost >= maximumInstancesPerHost) {
                        containerJob.removeProfile(profileId); // Remove profile instances that violate maximumInstancesPerHost
                    } else {
                        instancesPerHosts.put(containerJob.getIp(), instancesPerHost + 1);
                    }
                }

                if (maximumInstances != null) {
                    // Get containers with the profile
                    containerJobsForProfile = getContainerJobsForProfile(allContainerJobs.values(), profileId);
                    int delta = containerJobsForProfile.size() - maximumInstances;
                    if (delta > 0) {
                        // Container with most profiles first
                        Collections.sort(containerJobsForProfile, Collections.reverseOrder(new SortContainerJobsByProfileCount()));
                        for (ContainerJob containerJob : containerJobsForProfile.subList(0, delta)) {
                            containerJob.removeProfile(profileId); // Remove profile instances that violate maximumInstances
                            instancesPerHosts.put(containerJob.getIp(), instancesPerHosts.get(containerJob.getIp()) - 1);
                        }
                    }
                }

                if (minimumInstances != null) {
                    // Get containers with the profile
                    containerJobsForProfile = getContainerJobsForProfile(allContainerJobs.values(), profileId);
                    int delta = minimumInstances - containerJobsForProfile.size();
                    if (delta > 0) {
                        List<ContainerJob> applicableContainers = new ArrayList<>(allContainerJobs.values());
                        // Container with least profiles first
                        Collections.sort(applicableContainers, new SortContainerJobsByProfileCount());
                        for (ContainerJob containerJob : applicableContainers) {
                            int instancesPerHost = 0;
                            if (instancesPerHosts.get(containerJob.getIp()) != null) {
                                instancesPerHost = instancesPerHosts.get(containerJob.getIp());
                            }
                            if (instancesPerHost < maximumInstancesPerHost) { // Watch for maximumInstancesPerHost
                                containerJob.addProfile(profileId); // Add missing profile instances
                                instancesPerHosts.put(containerJob.getIp(), instancesPerHost + 1);
                                delta--;
                            }
                        }
                        if (delta > 0) {
                            LOGGER.error("Failed to assign the requested number of instances for profile: {}. {} assignments pending.", profileId, delta);
                        }
                    }
                }
            }
            // Apply changes to containers
            for (ContainerJob containerJob : allContainerJobs.values()) {
                new Thread(containerJob, "ContainerJob for container " + containerJob.getId()).start();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to auto-scale with profiles. Caught: " + e, e);
        }
    }

    private static List<ContainerJob> getContainerJobsForProfile(Collection<ContainerJob> containerJobs, String profileId) {
        List<ContainerJob> result = new ArrayList<>();
        for (ContainerJob containerJob : containerJobs) {
            if (containerJob.hasProfile(profileId)) {
                result.add(containerJob);
            }
        }
        return result;
    }

    protected void stopContainers(List<Container> containers, ContainerAutoScaler autoScaler, FabricRequirements requirements, ProfileRequirements profileRequirement, AutoScaleStatus status, int delta) {
        final String profile = profileRequirement.getProfile();
        AutoScaleProfileStatus profileStatus = status.profileStatus(profile);

        // TODO sort the containers using some kind of requirements sorting order
        List<Container> sorted = new ArrayList<>(containers);

        // lets stop the ones at the end of the list by default
        Collections.reverse(sorted);

        List<String> stoppingContainerIds = new ArrayList<>();
        for (int i = 0; i < delta; i++) {
            if (i >= sorted.size()) {
                break;
            }
            Container container = sorted.get(i);
            stoppingContainerIds.add(container.getId());
            profileStatus.stoppingContainers(stoppingContainerIds);
            container.stop(true);

        }
    }

    private AutoScalerNode createState() {
        AutoScalerNode state = new AutoScalerNode();
        return state;
    }

    void bindFabricService(FabricService fabricService) {
        this.fabricService.bind(fabricService);
    }

    void unbindFabricService(FabricService fabricService) {
        this.fabricService.unbind(fabricService);
    }

    void bindCurator(CuratorFramework curator) {
        this.curator.bind(curator);
    }

    void unbindCurator(CuratorFramework curator) {
        this.curator.unbind(curator);
    }

    private class SortContainerJobsByProfileCount implements Comparator<ContainerJob> {
        @Override
        public int compare(ContainerJob containerJob, ContainerJob t1) {
            return containerJob.getProfileCount() - t1.getProfileCount();
        }
    }
}
