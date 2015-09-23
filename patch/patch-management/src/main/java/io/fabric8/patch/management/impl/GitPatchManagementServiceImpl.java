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
package io.fabric8.patch.management.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;
import org.osgi.framework.BundleContext;

public class GitPatchManagementServiceImpl implements GitPatchManagementService {

    private static final String MAIN_GIT_REPO_LOCATION = ".management/history";
    private static final String MARKER_BASELINE_COMMIT_PATTERN = "[PATCH/baseline] jboss-fuse-full-%s-baseline";
    private static final String MARKER_USER_CHANGES_COMMIT = "[PATCH] Apply user changes";
    private static final String[] MANAGED_DIRECTORIES = new String[] { "bin", "etc", "lib", "fabric", "licenses", "metatype" };

    private static final DateFormat TS = new SimpleDateFormat("yyyyMMdd-HHmmssSSS");

    private final BundleContext bundleContext;
    private final BundleContext systemContext;

    private File karafHome;
    private String patchLocation;
    // main patches directory at ${fuse.patch.location} (defaults to ${karaf.home}/patches
    private File patchesDir;

    // directory containing "reference", bare git repository to store patch management history
    private File gitPatchManagement;
    // main git (bare) repository at ${fuse.patch.location}/.management/history
    private Git mainRepository;

    // directory to store temporary forks and working copies to perform operations before pushing to
    // "reference" repository
    private File tmpPatchManagement;

    public GitPatchManagementServiceImpl(BundleContext context) {
        this.bundleContext = context;
        this.systemContext = context.getBundle(0).getBundleContext();
        karafHome = new File(System.getProperty("karaf.home"));
        patchLocation = systemContext.getProperty("fuse.patch.location");
        if (patchLocation != null) {
            patchesDir = new File(patchLocation);
        }
    }

    @Override
    public boolean isEnabled() {
        return patchesDir != null && patchesDir.isDirectory() && patchesDir.exists();
    }

    @Override
    public void start() {
        gitPatchManagement = new File(patchesDir, MAIN_GIT_REPO_LOCATION);
        if (!gitPatchManagement.exists()) {
            gitPatchManagement.mkdirs();
        }

        tmpPatchManagement = new File(patchesDir, "tmp");
        if (!tmpPatchManagement.exists()) {
            tmpPatchManagement.mkdirs();
        }
    }

    @Override
    public void stop() {
        if (mainRepository != null) {
            mainRepository.status().getRepository().close();
            mainRepository.close();
            RepositoryCache.clear();
        }
    }

    /**
     * Check if Fuse/Fabric8 installation is correctly managed by patch mechanism. Check if git repository to store
     * configuration is created and there are no conflicts, no pending updates, ...
     */
    @Override
    public void ensurePatchManagementInitialized() {
        System.out.println("[PATCH] INITIALIZING PATCH MANAGEMENT SYSTEM");
        try {
            Git git = findOrCreateGitRepository(gitPatchManagement, true);
            ensureBaselineVersionManaged(git);
            applyUserChanges(git);
        } catch (GitAPIException | IOException e) {
            System.err.println("[PATCH-error] " + e.getMessage());
            e.printStackTrace();
            // PANIC
        }
    }

    /**
     * Returns at least initialized git repository at specific location
     * @param directory
     * @param bare
     * @return
     */
    private Git findOrCreateGitRepository(File directory, boolean bare) throws IOException {
        try {
            System.out.println("[PATCH] OPENING GIT");
            return Git.open(directory);
        } catch (RepositoryNotFoundException fallback) {
            try {
                System.out.println("[PATCH] INITIALIZING GIT");
                Git git = Git.init()
                        .setBare(bare)
                        .setDirectory(directory)
                        .call();
                Git fork = cloneRepository(git, false);
                fork.commit()
                        .setMessage("[PATCH] initialization")
                        .setAuthor(karafHome.getName(), "fuse@redhat.com")
                        .call();
                fork.push()
                        .setRemote("origin")
                        .setRefSpecs(new RefSpec("master"))
                        .call();
                closeRepository(fork, true);
                return git;
            } catch (GitAPIException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }

    /**
     * Removes repository, working copy and closes {@link Git}
     * @param fork
     * @param deleteWorkingCopy
     */
    private void closeRepository(Git fork, boolean deleteWorkingCopy) {
        fork.close();
        if (deleteWorkingCopy) {
            FileUtils.deleteQuietly(fork.getRepository().getDirectory().getParentFile());
        }
    }

    /**
     * <p>Possibly adds baseline version to patch management git repository.</p>
     * <p>This repository has to have named commit which adds all managed resources from <em>baseline</em>
     * distribution - a set of bin, etc, fabric, lib, licenses, metatype content from original distribution before
     * any customization</p>
     * @param git
     */
    private void ensureBaselineVersionManaged(Git git) throws GitAPIException, IOException {
        List<Ref> branches = git.branchList().call();
        if (branches.isEmpty()) {
            throw new RuntimeException(git.getRepository().getDirectory() + " should have at least 'master' branch available");
        }

        // we need baseline distribution of Fuse/AMQ at current version
        String currentFuseVersion = determineFuseVersion();

        Git fork = null;
        try {
            // create fork, initialize it with baseline version and push to reference repo
            fork = cloneRepository(git, true);
            if (!containsCommit(fork, "master", String.format(MARKER_BASELINE_COMMIT_PATTERN, currentFuseVersion))) {
                File baselineDistribution = new File(patchesDir, String.format("jboss-fuse-full-%s-baseline.zip", currentFuseVersion));
                if (baselineDistribution.exists() && baselineDistribution.isFile()) {
                    unpackToRepository(fork, baselineDistribution);
                    fork.add()
                            .addFilepattern(".")
                            .call();
                    fork.commit()
                            .setMessage(String.format(MARKER_BASELINE_COMMIT_PATTERN, currentFuseVersion))
                            .setAuthor(karafHome.getName(), "fuse@redhat.com")
                            .call();
                    fork.push()
                            .setRemote("origin")
                            .setRefSpecs(new RefSpec("master"))
                            .call();
                } else {
                    String message = "Can't find baseline distribution \"" + baselineDistribution.getCanonicalPath() + "\".";
                    System.err.println("[PATCH-error] " + message);
                    throw new IOException(message);
                }
            } else {
                System.out.println("[PATCH] Baseline distribution already committed for version " + currentFuseVersion);
            }
        } finally {
            closeRepository(fork, true);
        }
    }

    /**
     * Checks whether a branch in repository contains named commit
     * @param fork
     * @param master
     * @param commitMessage
     * @return
     */
    private boolean containsCommit(Git fork, String master, String commitMessage) throws IOException, GitAPIException {
        ObjectId masterHEAD = fork.getRepository().resolve("master");
        Iterable<RevCommit> log = fork.log().add(masterHEAD).call();
        for (RevCommit rc : log) {
            if (rc.getFullMessage().equals(commitMessage)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unpacks a ZIP file and adds (recursively) its content to git repository
     * @param git
     * @param baselineDistribution a ZIP file to unpack and add to the repository
     * @return the same repository for method chaining
     */
    private Git unpackToRepository(Git git, File baselineDistribution) throws IOException {
        File wc = git.getRepository().getDirectory().getParentFile();
        ZipFile zf = new ZipFile(baselineDistribution);
        try {
            for (Enumeration<ZipArchiveEntry> e = zf.getEntries(); e.hasMoreElements(); ) {
                ZipArchiveEntry entry = e.nextElement();
                String name = entry.getName();
                name = name.substring(name.indexOf('/'));
                if (entry.isDirectory()) {
                    new File(wc, name).mkdirs();
                } else if (!entry.isUnixSymlink()) {
                    File file = new File(wc, name);
                    file.getParentFile().mkdirs();
                    FileOutputStream output = new FileOutputStream(file);
                    IOUtils.copyLarge(zf.getInputStream(entry), output);
                    IOUtils.closeQuietly(output);
                    Files.setPosixFilePermissions(file.toPath(), getPermissionsFromUnixMode(file, entry.getUnixMode()));
                }
            }
        } finally {
            if (zf != null) {
                zf.close();
            }
        }
        return git;
    }

    /**
     * Converts ZIP entry UNIX permissions to a set of {@link PosixFilePermission}
     * @param file
     * @param unixMode
     * @return
     */
    private Set<PosixFilePermission> getPermissionsFromUnixMode(File file, int unixMode) {
        String numeric = Integer.toOctalString(unixMode);
        if (numeric != null && numeric.length() > 3) {
            numeric = numeric.substring(numeric.length() - 3);
        }
        if (numeric == null) {
            return PosixFilePermissions.fromString(file.isDirectory() ? "rwxrwxr-x" : "rw-rw-r--");
        }

        Set<PosixFilePermission> result = new HashSet<>();
        int shortMode = Integer.parseInt(numeric, 8);
        if ((shortMode & 0400) == 0400)
            result.add(PosixFilePermission.OWNER_READ);
        if ((shortMode & 0200) == 0200)
            result.add(PosixFilePermission.OWNER_WRITE);
        if ((shortMode & 0100) == 0100)
            result.add(PosixFilePermission.OWNER_EXECUTE);
        if ((shortMode & 0040) == 0040)
            result.add(PosixFilePermission.GROUP_READ);
        if ((shortMode & 0020) == 0020)
            result.add(PosixFilePermission.GROUP_WRITE);
        if ((shortMode & 0010) == 0010)
            result.add(PosixFilePermission.GROUP_EXECUTE);
        if ((shortMode & 0004) == 0004)
            result.add(PosixFilePermission.OTHERS_READ);
        if ((shortMode & 0002) == 0002)
            result.add(PosixFilePermission.OTHERS_WRITE);
        if ((shortMode & 0001) == 0001)
            result.add(PosixFilePermission.OTHERS_EXECUTE);

        return result;
    }

    /**
     * Return version of JBoss Fuse used. When running in plain Fabric8 we fail fast and don't call this method
     * from Activator. This version is needed to retrieve baseline ZIP file for the correct version.
     * @return
     */
    private String determineFuseVersion() {
        File versions = new File(karafHome, "fabric/import/fabric/profiles/default.profile/io.fabric8.version.properties");
        if (versions.exists() && versions.isFile()) {
            Properties props = new Properties();
            try {
                props.load(new FileInputStream(versions));
                return props.getProperty("fuse");
            } catch (IOException e) {
                System.err.println("[PATCH-error] " + e.getMessage());
                return null;
            }
        } else {
            System.err.println("[PATCH-error] Can't find io.fabric8.version.properties file in default profile");
        }
        return null;
    }

    /**
     * Retrieves {@link Git} handle to temporary fork of another repository. The returned repository is connected
     * to the forked repo using "origin" remote.
     * @param git
     * @param fetchAndCheckout
     * @return
     */
    private Git cloneRepository(Git git, boolean fetchAndCheckout) throws GitAPIException, IOException {
        File tmpLocation = new File(tmpPatchManagement, TS.format(new Date()));
        Git fork = Git.init().setBare(false).setDirectory(tmpLocation).call();
        StoredConfig config = fork.getRepository().getConfig();
        config.setString("remote", "origin", "url", git.getRepository().getDirectory().getCanonicalPath());
        config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
        config.save();

        if (fetchAndCheckout) {
            fork.fetch().setRemote("origin").call();
            fork.checkout()
                    .setCreateBranch(true)
                    .setName("master")
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .setStartPoint("origin/master")
                    .call();
        }

        return fork;
    }

    /**
     * Applies existing user changes in FUSE_HOME/{bin,etc,fabric,lib,licenses,metatype} directories to patch
     * management Git repository
     * @param git
     */
    private void applyUserChanges(Git git) throws GitAPIException, IOException {
        Git fork = cloneRepository(git, true);
        File wcDir = fork.getRepository().getDirectory().getParentFile();
        File karafBase = karafHome;

        try {
            // let's simply copy all user files on top of git working copy
            // then we can check the differences simply by committing the changes
            // there should be no conflicts, because we're not merging anything
            for (String dir : MANAGED_DIRECTORIES) {
                File destDir = new File(wcDir, dir);
                // delete content of wc's copy of the dir to handle user removal of files
                FileUtils.deleteQuietly(destDir);
                FileUtils.copyDirectory(new File(karafBase, dir), destDir);
                if ("bin".equals(dir)) {
                    // repair file permissions
                    for (File script : destDir.listFiles()) {
                        if (!script.getName().endsWith(".bat")) {
                            Files.setPosixFilePermissions(script.toPath(), getPermissionsFromUnixMode(script, 0775));
                        }
                    }
                }
            }

            // commit the changes to main repository
            Status status = fork.status().call();
            if (!status.isClean()) {
                System.out.println("[PATCH] Storing user changes");
                fork.add()
                        .addFilepattern(".")
                        .call();
                for (String name : status.getMissing()) {
                    fork.rm()
                            .addFilepattern(name)
                            .call();
                }
                fork.commit()
                        .setMessage(MARKER_USER_CHANGES_COMMIT)
                        .setAuthor(karafHome.getName(), "fuse@redhat.com")
                        .call();
                fork.push()
                        .setRemote("origin")
                        .setRefSpecs(new RefSpec("master"))
                        .call();

                // now main repository has exactly the same content as ${karaf.home}
                // TODO we have two methods of synchronization wrt future rollup changes:
                // 1. we can pull from "origin" in the MAIN working copy (${karaf.hoome}) (making sure the copy is initialized
                // 2. we can apply rollup patch to temporary fork + working copy, perform merges, resolve conflicts, etc
                //    and if everything goes fine, simply override ${karaf.hoome} content with the working copy content
                // method 2. doesn't require real working copy and ${karaf.home}/.git directory
            } else {
                System.out.println("[PATCH] No user changes");
            }
        } catch (GitAPIException | IOException e) {
            System.err.println("[PATCH-error] " + e.getMessage());
        } finally {
            closeRepository(fork, true);
        }
    }

}
