package io.fabric8.autoscale;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.fabric8.api.Container;
import io.fabric8.api.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContainerJob implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AutoScaleController.class);
    private final Container container;
    private final Map<Profile, Boolean> profiles = new HashMap<>();

    public ContainerJob(Container container) {
        this.container = container;
    }

    public Container getContainer() {
        return container;
    }

    public int getProfileCount() {
        int count = 0;
        for (Boolean assigned : profiles.values()) {
            if (assigned) {
                count++;
            }
        }
        return count;
    }

    public String getIp() {
        return container.getIp();
    }

    public String getId() {
        return container.getId();
    }

    public void addProfile(Profile profile) {
        profiles.put(profile, true);
    }

    public void addProfile(String profileId) {
        addProfile(container.getVersion().getProfile(profileId));
    }

    public void removeProfile(Profile profile) {
        profiles.put(profile, false);
    }

    public void removeProfile(String profileId) {
        removeProfile(container.getVersion().getProfile(profileId));
    }

    public void removeProfiles(int count) {
        Profile[] ps = profiles.keySet().toArray(new Profile[profiles.keySet().size()]);
        for (int i = 0; i < count; i++) {
            removeProfile(ps[i]);
        }
    }

    public boolean hasProfile(Profile profile) {
        return profiles.containsKey(profile) && profiles.get(profile);
    }

    public boolean hasProfile(String profileId) {
        return hasProfile(container.getVersion().getProfile(profileId));
    }

    @Override
    public void run() {
        final Set<Profile> currentProfiles = new HashSet<>(Arrays.asList(container.getProfiles()));
        final Set<Profile> resultProfiles = new HashSet<>(currentProfiles);
        for (Map.Entry<Profile, Boolean> entry : profiles.entrySet()) {
            final Profile profile = entry.getKey();
            final Boolean assigned = entry.getValue();
            if (assigned) {
                resultProfiles.add(profile);
            } else {
                resultProfiles.remove(profile);
            }
        }
        if (!resultProfiles.equals(currentProfiles)) {
            Profile[] sortedResult = resultProfiles.toArray(new Profile[resultProfiles.size()]);
            Arrays.sort(sortedResult, new Comparator<Profile>() {
                @Override
                public int compare(Profile profile, Profile t1) {
                    return profile.getId().compareToIgnoreCase(t1.getId());
                }
            });
            LOGGER.info("Setting profiles for container {}", container.getId());
            container.setProfiles(sortedResult);
        }
    }
}
