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
package io.fabric8.api;

import java.util.List;
import java.util.Map;

import io.fabric8.api.commands.GitVersion;
import io.fabric8.api.commands.GitVersions;

/**
 * The profile data store
 */
public interface ProfileRegistry {

    /**
     * Get the data store properties.
     */
    Map<String, String> getDataStoreProperties();
    
    /**
     * Aquire a write lock for the profile datastore.
     */
    LockHandle aquireWriteLock();
    
    /**
     * Aquire a read lock for the profile datastore.
     * A read lock cannot be upgraded to a writelock
     */
    LockHandle aquireReadLock();
    
    //
    // Version management
    //

    /**
     * Create a version as a copy from the given source version identity .
     */
    String createVersion(String sourceId, String targetId, Map<String, String> attributes);

    /**
     * Create a version as a copy from the given source version identity in the provided Git context.
     */
    String createVersion(GitContext context, String sourceId, String targetId, Map<String, String> attributes);
    
    /**
     * Create the given version in the data store.
     * @return The version id
     */
    String createVersion(Version version);

    /**
     * Create the given version in the data store in the provided Git context.
     * @return The version id
     */
    String createVersion(GitContext context, Version version);

    /**
     * Get the ordered list of available versions.
     */
    List<String> getVersionIds();

    /**
     * Get the ordered list of available versions in the provided Git context.
     */
    List<String> getVersionIds(GitContext context);

    /**
     * Returns list of {@link GitVersion} for diagnostic purposes - to check if some container's git repository
     * is out of sync.
     * @return
     */
    GitVersions gitVersions();

    /**
     * Forces synchronization of local git repository - forced pull from current git master. Returns list of
     * version/branch information after synchronization.
     * @param allowPush whether local repo state is pushed to central if there are newer local changes
     * @return
     */
    GitVersions gitSynchronize(boolean allowPush);

    /**
     * True if the data store contains the given version.
     */
    boolean hasVersion(String versionId);

    /**
     * True if the data store contains the given version in the provided Git context.
     */
    boolean hasVersion(GitContext context, String versionId);

    /**
     * Get the version for the given id.
     * @return The version or null
     */
    Version getVersion(String versionId);

    /**
     * Get the version for the given id.
     * @throws IllegalStateException if the required version does not exist
     */
    Version getRequiredVersion(String versionId);

    /**
     * Delete the given version and all associated profiles.
     */
    void deleteVersion(String versionId);
    
    /**
     * Delete the given version and all associated profiles in the provided Git context.
     */
    void deleteVersion(GitContext context, String versionId);
    
    //
    // Profile management
    //
    
    /**
     * Create the given profile in the data store.
     * @return The profile id
     */
    String createProfile(Profile profile);
    
    /**
     * Create the given profile in the data store in the provided Git context.
     * @return The profile id
     */
    String createProfile(GitContext context, Profile profile);
    
    /**
     * Create the given profile in the data store.
     * @return The profile id
     */
    String updateProfile(Profile profile);

    /**
     * Create the given profile in the data store.
     * @param profile
     * @param force control whether to update profile in case when old.equals(new)
     * @return
     */
    String updateProfile(Profile profile, boolean force);

    /**
     * Create the given profile in the data store in the provided Git context.
     * @param context
     * @param profile
     * @param force update profile regardless of normal equality of new and old profile (which doesn't take into account resources contents)
     * @return The profile id
     */
    String updateProfile(GitContext context, Profile profile, boolean force);

    /**
     * True if the given profile existes in the given version.
     */
    boolean hasProfile(String versionId, String profileId);

    /**
     * Get the profile for the given version and id.
     * @return The profile or null
     */
    Profile getProfile(String versionId, String profileId);

    /**
     * Get the profile for the given version and id.
     * @throws IllegalStateException if the required profile does not exist
     */
    Profile getRequiredProfile(String versionId, String profileId);

    /** 
     * Get the list of profiles associated with the given version.
     */
    List<String> getProfiles(String versionId);

    /**
     * Delete the given profile from the data store.
     */
    void deleteProfile(String versionId, String profileId);

    /**
     * Delete the given profile from the data store in the provided Git context.
     */
    void deleteProfile(GitContext context, String versionId, String profileId);

    //
    // Import/Export 
    // [TODO] Consider utility methods for import/export that go through ProfileService 
    //
    
    void importProfiles(String versionId, List<String> profileZipUrls);

    void importFromFileSystem(String importPath);

    void exportProfiles(String versionId, String outputFileName, String wildcard);
}
