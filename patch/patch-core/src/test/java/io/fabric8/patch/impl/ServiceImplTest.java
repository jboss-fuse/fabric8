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
package io.fabric8.patch.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLStreamHandlerFactory;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.fabric8.patch.Service;
import io.fabric8.patch.management.BundleUpdate;
import io.fabric8.patch.management.Patch;
import io.fabric8.patch.management.PatchData;
import io.fabric8.patch.management.PatchException;
import io.fabric8.patch.management.PatchKind;
import io.fabric8.patch.management.PatchManagement;
import io.fabric8.patch.management.PatchResult;
import io.fabric8.patch.management.Utils;
import io.fabric8.patch.management.impl.GitPatchManagementServiceImpl;
import io.fabric8.patch.management.impl.GitPatchRepository;
import org.apache.aries.util.io.IOUtils;
import org.apache.commons.io.FileUtils;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.Version;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.component.ComponentContext;

import static io.fabric8.patch.impl.PatchTestSupport.getDirectoryForResource;
import static io.fabric8.patch.management.Utils.stripSymbolicName;
import static org.easymock.EasyMock.*;
import static org.junit.Assert.*;

public class ServiceImplTest {

    File baseDir;

    File karaf;
    File storage;
    File bundlev131;
    File bundlev132;
    File bundlev140;
    File bundlev200;
    File patch132;
    File patch140;
    File patch200;
    private String oldName;

    @Before
    public void setUp() throws Exception {
        baseDir = getDirectoryForResource("log4j.properties");

        URL.setURLStreamHandlerFactory(new CustomBundleURLStreamHandlerFactory());
        generateData();
        oldName = System.setProperty("karaf.name", "x");
    }

    @After
    public void tearDown() throws Exception {
        Field field = URL.class.getDeclaredField("factory");
        field.setAccessible(true);
        field.set(null, null);
        if (oldName != null) {
            System.setProperty("karaf.name", oldName);
        }
    }

    @Test
    @Ignore
    public void testOfflineOverrides() throws IOException {
        Offline offline = new Offline(karaf);
        String startup;
        String overrides;

        offline.apply(patch132);
        startup = FileUtils.readFileToString(new File(karaf, "etc/startup.properties"));
        overrides = FileUtils.readFileToString(new File(karaf, "etc/overrides.properties"));

        assertEquals("", startup.trim());
        assertEquals("mvn:foo/my-bsn/1.3.2", overrides.trim());
        assertTrue(new File(karaf, "system/foo/my-bsn/1.3.2/my-bsn-1.3.2.jar").exists());
        assertFalse(new File(karaf, "system/foo/my-bsn/1.4.0/my-bsn-1.4.0.jar").exists());
        assertFalse(new File(karaf, "system/foo/my-bsn/2.0.0/my-bsn-2.0.0.jar").exists());

        offline.apply(patch140);
        startup = FileUtils.readFileToString(new File(karaf, "etc/startup.properties"));
        overrides = FileUtils.readFileToString(new File(karaf, "etc/overrides.properties"));

        assertEquals("", startup.trim());
        assertEquals("mvn:foo/my-bsn/1.4.0;range=[1.3.0,1.5.0)", overrides.trim());
        assertFalse(new File(karaf, "system/foo/my-bsn/1.3.2/my-bsn-1.3.2.jar").exists());
        assertTrue(new File(karaf, "system/foo/my-bsn/1.4.0/my-bsn-1.4.0.jar").exists());
        assertFalse(new File(karaf, "system/foo/my-bsn/2.0.0/my-bsn-2.0.0.jar").exists());

        offline.apply(patch200);
        startup = FileUtils.readFileToString(new File(karaf, "etc/startup.properties"));
        overrides = FileUtils.readFileToString(new File(karaf, "etc/overrides.properties"));

        assertEquals("", startup.trim());
        assertEquals("mvn:foo/my-bsn/1.4.0;range=[1.3.0,1.5.0)\nmvn:foo/my-bsn/2.0.0", overrides.trim().replaceAll("\r", ""));
        assertFalse(new File(karaf, "system/foo/my-bsn/1.3.2/my-bsn-1.3.2.jar").exists());
        assertTrue(new File(karaf, "system/foo/my-bsn/1.4.0/my-bsn-1.4.0.jar").exists());
        assertTrue(new File(karaf, "system/foo/my-bsn/2.0.0/my-bsn-2.0.0.jar").exists());
    }

    @Test
    @Ignore
    public void testOfflineStartup() throws IOException {
        Offline offline = new Offline(karaf);
        String startup;
        String overrides;

        FileUtils.write(new File(karaf, "etc/startup.properties"), "foo/my-bsn/1.3.1/my-bsn-1.3.1.jar=1");

        offline.apply(patch132);
        startup = FileUtils.readFileToString(new File(karaf, "etc/startup.properties"));
        overrides = FileUtils.readFileToString(new File(karaf, "etc/overrides.properties"));

        assertEquals("foo/my-bsn/1.3.2/my-bsn-1.3.2.jar=1", startup.trim());
        assertEquals("mvn:foo/my-bsn/1.3.2", overrides.trim());
        assertTrue(new File(karaf, "system/foo/my-bsn/1.3.2/my-bsn-1.3.2.jar").exists());
        assertFalse(new File(karaf, "system/foo/my-bsn/1.4.0/my-bsn-1.4.0.jar").exists());
        assertFalse(new File(karaf, "system/foo/my-bsn/2.0.0/my-bsn-2.0.0.jar").exists());

        offline.apply(patch140);
        startup = FileUtils.readFileToString(new File(karaf, "etc/startup.properties"));
        overrides = FileUtils.readFileToString(new File(karaf, "etc/overrides.properties"));

        assertEquals("foo/my-bsn/1.4.0/my-bsn-1.4.0.jar=1", startup.trim());
        assertEquals("mvn:foo/my-bsn/1.4.0;range=[1.3.0,1.5.0)", overrides.trim());
        assertFalse(new File(karaf, "system/foo/my-bsn/1.3.2/my-bsn-1.3.2.jar").exists());
        assertTrue(new File(karaf, "system/foo/my-bsn/1.4.0/my-bsn-1.4.0.jar").exists());
        assertFalse(new File(karaf, "system/foo/my-bsn/2.0.0/my-bsn-2.0.0.jar").exists());

        offline.apply(patch200);
        startup = FileUtils.readFileToString(new File(karaf, "etc/startup.properties"));
        overrides = FileUtils.readFileToString(new File(karaf, "etc/overrides.properties"));

        assertEquals("foo/my-bsn/1.4.0/my-bsn-1.4.0.jar=1", startup.trim());
        assertEquals("mvn:foo/my-bsn/1.4.0;range=[1.3.0,1.5.0)\nmvn:foo/my-bsn/2.0.0", overrides.trim().replaceAll("\r", ""));
        assertFalse(new File(karaf, "system/foo/my-bsn/1.3.2/my-bsn-1.3.2.jar").exists());
        assertTrue(new File(karaf, "system/foo/my-bsn/1.4.0/my-bsn-1.4.0.jar").exists());
        assertTrue(new File(karaf, "system/foo/my-bsn/2.0.0/my-bsn-2.0.0.jar").exists());
    }

    @Test
    public void testLoadWithoutRanges() throws IOException, GitAPIException {
        BundleContext bundleContext = createMock(BundleContext.class);
        ComponentContext componentContext = createMock(ComponentContext.class);
        Bundle sysBundle = createMock(Bundle.class);
        BundleContext sysBundleContext = createMock(BundleContext.class);
        Bundle bundle = createMock(Bundle.class);
        Bundle bundle2 = createMock(Bundle.class);
        FrameworkWiring wiring = createMock(FrameworkWiring.class);
        GitPatchRepository repository = createMock(GitPatchRepository.class);

        //
        // Create a new service, download a patch
        //
        expect(componentContext.getBundleContext()).andReturn(bundleContext);
        expect(bundleContext.getBundle(0)).andReturn(sysBundle).anyTimes();
        expect(sysBundle.getBundleContext()).andReturn(sysBundleContext).anyTimes();
        expect(sysBundleContext.getProperty(Service.NEW_PATCH_LOCATION))
                .andReturn(storage.toString()).anyTimes();
        expect(repository.getManagedPatch(anyString())).andReturn(null).anyTimes();
        expect(repository.findOrCreateMainGitRepository()).andReturn(null).anyTimes();
        expect(sysBundleContext.getProperty("karaf.default.repository")).andReturn("system").anyTimes();
        expect(sysBundleContext.getProperty("karaf.home"))
                .andReturn(karaf.getCanonicalPath()).anyTimes();
        expect(sysBundleContext.getProperty("karaf.base"))
                .andReturn(karaf.getCanonicalPath()).anyTimes();
        expect(sysBundleContext.getProperty("karaf.name"))
                .andReturn("root").anyTimes();
        expect(sysBundleContext.getProperty("karaf.instances"))
                .andReturn(karaf.getCanonicalPath() + "/instances").anyTimes();
        expect(sysBundleContext.getProperty("karaf.data")).andReturn(karaf.getCanonicalPath() + "/data").anyTimes();
        replay(componentContext, sysBundleContext, sysBundle, bundleContext, bundle, repository);

        PatchManagement pm = new GitPatchManagementServiceImpl(bundleContext);
        ((GitPatchManagementServiceImpl)pm).setGitPatchRepository(repository);

        ServiceImpl service = new ServiceImpl();
        setField(service, "patchManagement", pm);
        service.activate(componentContext);

        PatchData pd = PatchData.load(getClass().getClassLoader().getResourceAsStream("test1.patch"));
        assertEquals(2, pd.getBundles().size());
        assertTrue(pd.getRequirements().isEmpty());
    }

    @Test
    public void testLoadWithRanges() throws IOException {
        ServiceImpl service = createMockServiceImpl();

        PatchData pd = PatchData.load(getClass().getClassLoader().getResourceAsStream("test2.patch"));
        assertEquals(2, pd.getBundles().size());
        assertEquals("[1.0.0,2.0.0)", pd.getVersionRange("mvn:io.fabric8.test/test1/1.0.0"));
        assertNull(pd.getVersionRange("mvn:io.fabric8.test/test2/1.0.0"));
        assertTrue(pd.getRequirements().isEmpty());
    }

    @Test
    public void testLoadWithPrereqs() throws IOException {
        ServiceImpl service = createMockServiceImpl();

        PatchData pd = PatchData.load(getClass().getClassLoader().getResourceAsStream("test-with-prereq.patch"));
        assertEquals(2, pd.getBundles().size());
        assertEquals(1, pd.getRequirements().size());
        assertTrue(pd.getRequirements().contains("prereq1"));
        assertNull(pd.getVersionRange("mvn:io.fabric8.test/test2/1.0.0"));
    }

    @Test
    public void testCheckPrerequisitesMissing() throws IOException {
        ServiceImpl service = createMockServiceImpl(getDirectoryForResource("prereq/patch1.patch"));

        Patch patch = service.getPatch("patch1");
        assertNotNull(patch);
        try {
            service.checkPrerequisites(patch);
            fail("Patch will missing prerequisites should not pass check");
        } catch (PatchException e) {
            assertTrue(e.getMessage().toLowerCase().contains("required patch 'prereq1' is missing"));
        }
    }

    @Test
    public void testCheckPrerequisitesNotInstalled() throws IOException {
        ServiceImpl service = createMockServiceImpl(getDirectoryForResource("prereq/patch2.patch"));

        Patch patch = service.getPatch("patch2");
        assertNotNull(patch);
        try {
            service.checkPrerequisites(patch);
            fail("Patch will prerequisites that are not yet installed should not pass check");
        } catch (PatchException e) {
            assertTrue(e.getMessage().toLowerCase().contains("required patch 'prereq2' is not installed"));
        }
    }

    @Test
    public void testCheckPrerequisitesSatisfied() throws IOException {
        ServiceImpl service = createMockServiceImpl(getDirectoryForResource("prereq/patch3.patch"));

        Patch patch = service.getPatch("patch3");
        assertNotNull(patch);
        // this should not throw a PatchException
        service.checkPrerequisites(patch);
    }

    @Test
    public void testCheckPrerequisitesMultiplePatches() throws IOException {
        ServiceImpl service = createMockServiceImpl(getDirectoryForResource("prereq/patch1.patch"));

        Collection<Patch> patches = new LinkedList<Patch>();
        patches.add(service.getPatch("patch3"));
        // this should not throw a PatchException
        service.checkPrerequisites(patches);

        patches.add(service.getPatch("patch2"));
        try {
            service.checkPrerequisites(patches);
            fail("Should not pass check if one of the patches is missing a requirement");
        } catch (PatchException e) {
            // graciously do nothing, this is OK
        }

    }

    /*
     * Create a mock patch service implementation with access to the generated data directory
     */
    private ServiceImpl createMockServiceImpl() throws IOException {
        return createMockServiceImpl(storage);
    }

    /*
     * Create a mock patch service implementation with a provided patch storage location
     */
    private ServiceImpl createMockServiceImpl(File patches) throws IOException {
        ComponentContext componentContext = createMock(ComponentContext.class);
        BundleContext bundleContext = createMock(BundleContext.class);
        Bundle sysBundle = createMock(Bundle.class);
        BundleContext sysBundleContext = createMock(BundleContext.class);
        Bundle bundle = createMock(Bundle.class);
        GitPatchRepository repository = createMock(GitPatchRepository.class);

        //
        // Create a new service, download a patch
        //
        expect(bundle.getVersion()).andReturn(new Version(1, 2, 0)).anyTimes();
        expect(componentContext.getBundleContext()).andReturn(bundleContext);
        expect(bundleContext.getBundle(0)).andReturn(sysBundle).anyTimes();
        expect(bundleContext.getBundle()).andReturn(bundle).anyTimes();
        expect(sysBundle.getBundleContext()).andReturn(sysBundleContext).anyTimes();
        expect(sysBundleContext.getProperty(Service.NEW_PATCH_LOCATION))
                .andReturn(patches.toString()).anyTimes();
        expect(sysBundleContext.getProperty("karaf.default.repository")).andReturn("system").anyTimes();
        expect(sysBundleContext.getProperty("karaf.data")).andReturn(patches.getParent() + "/data").anyTimes();
        try {
            expect(repository.getManagedPatch(anyString())).andReturn(null).anyTimes();
            expect(repository.findOrCreateMainGitRepository()).andReturn(null).anyTimes();
            expect(sysBundleContext.getProperty("karaf.home"))
                    .andReturn(karaf.getCanonicalPath()).anyTimes();
            expect(sysBundleContext.getProperty("karaf.base"))
                    .andReturn(karaf.getCanonicalPath()).anyTimes();
            expect(sysBundleContext.getProperty("karaf.name"))
                    .andReturn("root").anyTimes();
            expect(sysBundleContext.getProperty("karaf.instances"))
                    .andReturn(karaf.getCanonicalPath() + "/instances").anyTimes();
        } catch (GitAPIException | IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        replay(componentContext, sysBundleContext, sysBundle, bundleContext, bundle, repository);

        PatchManagement pm = new GitPatchManagementServiceImpl(bundleContext);
        ((GitPatchManagementServiceImpl)pm).setGitPatchRepository(repository);

        ServiceImpl service = new ServiceImpl();
        setField(service, "patchManagement", pm);
        service.activate(componentContext);
        return service;
    }

    private void setField(ServiceImpl service, String fieldName, Object value) {
        Field f = null;
        try {
            f = service.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(service, value);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Test
    public void testPatch() throws Exception {
        ComponentContext componentContext = createMock(ComponentContext.class);
        BundleContext bundleContext = createMock(BundleContext.class);
        Bundle sysBundle = createMock(Bundle.class);
        BundleContext sysBundleContext = createMock(BundleContext.class);
        Bundle bundle = createMock(Bundle.class);
        Bundle bundle2 = createMock(Bundle.class);
        FrameworkWiring wiring = createMock(FrameworkWiring.class);
        GitPatchRepository repository = createMock(GitPatchRepository.class);

        //
        // Create a new service, download a patch
        //
        expect(componentContext.getBundleContext()).andReturn(bundleContext);
        expect(bundleContext.getBundle(0)).andReturn(sysBundle).anyTimes();
        expect(sysBundle.getBundleContext()).andReturn(sysBundleContext).anyTimes();
        expect(sysBundleContext.getProperty(Service.NEW_PATCH_LOCATION))
                .andReturn(storage.toString()).anyTimes();
        expect(repository.getManagedPatch(anyString())).andReturn(null).anyTimes();
        expect(repository.findOrCreateMainGitRepository()).andReturn(null).anyTimes();
        expect(sysBundleContext.getProperty("karaf.default.repository")).andReturn("system").anyTimes();
        expect(sysBundleContext.getProperty("karaf.home"))
                .andReturn(karaf.getCanonicalPath()).anyTimes();
        expect(sysBundleContext.getProperty("karaf.base"))
                .andReturn(karaf.getCanonicalPath()).anyTimes();
        expect(sysBundleContext.getProperty("karaf.name"))
                .andReturn("root").anyTimes();
        expect(sysBundleContext.getProperty("karaf.instances"))
                .andReturn(karaf.getCanonicalPath() + "/instances").anyTimes();
        expect(sysBundleContext.getProperty("karaf.data")).andReturn(karaf.getCanonicalPath() + "/data").anyTimes();

        replay(componentContext, sysBundleContext, sysBundle, bundleContext, bundle, repository);

        PatchManagement pm = mockManagementService(bundleContext);
        ((GitPatchManagementServiceImpl)pm).setGitPatchRepository(repository);

        ServiceImpl service = new ServiceImpl();
        setField(service, "patchManagement", pm);
        service.activate(componentContext);
        
        try {
            service.download(new URL("file:" + storage + "/temp/f00.zip"));
            fail("Should have thrown exception on non existent patch file.");
        } catch (Exception e) {        	
        }        
        
        Iterable<Patch> patches = service.download(patch132.toURI().toURL());
        assertNotNull(patches);
        Iterator<Patch> it = patches.iterator();
        assertTrue( it.hasNext() );
        Patch patch = it.next();
        assertNotNull( patch );
        assertEquals("patch-1.3.2", patch.getPatchData().getId());
        assertNotNull(patch.getPatchData().getBundles());
        assertEquals(1, patch.getPatchData().getBundles().size());
        Iterator<String> itb = patch.getPatchData().getBundles().iterator();
        assertEquals("mvn:foo/my-bsn/1.3.2", itb.next());
        assertNull(patch.getResult());
        verify(componentContext, sysBundleContext, sysBundle, bundleContext, bundle);

        //
        // Simulate the patch
        //

        reset(componentContext, sysBundleContext, sysBundle, bundleContext, bundle);

        expect(sysBundleContext.getBundles()).andReturn(new Bundle[] { bundle });
        expect(sysBundleContext.getServiceReference("io.fabric8.api.FabricService")).andReturn(null).anyTimes();
        expect(bundle.getSymbolicName()).andReturn("my-bsn").anyTimes();
        expect(bundle.getVersion()).andReturn(new Version("1.3.1")).anyTimes();
        expect(bundle.getLocation()).andReturn("location").anyTimes();
        expect(bundle.getBundleId()).andReturn(123L).anyTimes();
        BundleStartLevel bsl = createMock(BundleStartLevel.class);
        expect(bsl.getStartLevel()).andReturn(30).anyTimes();
        expect(bundle.adapt(BundleStartLevel.class)).andReturn(bsl).anyTimes();
        expect(bundle.getState()).andReturn(1);
        expect(sysBundleContext.getProperty("karaf.default.repository")).andReturn("system").anyTimes();
        replay(componentContext, sysBundleContext, sysBundle, bundleContext, bundle, bsl);

        PatchResult result = service.install(patch, true);
        assertNotNull( result );
        assertNull( patch.getResult() );
        assertTrue(result.isSimulation());

        verify(componentContext, sysBundleContext, sysBundle, bundleContext, bundle, bsl);

        //
        // Recreate a new service and verify the downloaded patch is still available
        //

        reset(componentContext, sysBundleContext, sysBundle, bundleContext, bundle, repository, bsl);
        expect(componentContext.getBundleContext()).andReturn(bundleContext);
        expect(bundleContext.getBundle(0)).andReturn(sysBundle);
        expect(sysBundle.getBundleContext()).andReturn(sysBundleContext);
        expect(sysBundleContext.getProperty(Service.NEW_PATCH_LOCATION))
                .andReturn(storage.toString()).anyTimes();
        expect(sysBundleContext.getProperty("karaf.home"))
                .andReturn(karaf.toString()).anyTimes();
        expect(sysBundleContext.getProperty("karaf.base"))
                .andReturn(karaf.getCanonicalPath()).anyTimes();
        expect(sysBundleContext.getProperty("karaf.name"))
                .andReturn("root").anyTimes();
        expect(sysBundleContext.getProperty("karaf.instances"))
                .andReturn(karaf.getCanonicalPath() + "/instances").anyTimes();
        expect(sysBundleContext.getProperty("karaf.default.repository")).andReturn("system").anyTimes();
        expect(repository.getManagedPatch(anyString())).andReturn(null).anyTimes();
        expect(repository.findOrCreateMainGitRepository()).andReturn(null).anyTimes();
        replay(componentContext, sysBundleContext, sysBundle, bundleContext, bundle, repository, bsl);

        service = new ServiceImpl();
        setField(service, "patchManagement", pm);
        service.activate(componentContext);

        patches = service.getPatches();
        assertNotNull(patches);
        it = patches.iterator();
        assertTrue( it.hasNext() );
        patch = it.next();
        assertNotNull( patch );
        assertEquals("patch-1.3.2", patch.getPatchData().getId());
        assertNotNull(patch.getPatchData().getBundles());
        assertEquals(1, patch.getPatchData().getBundles().size());
        itb = patch.getPatchData().getBundles().iterator();
        assertEquals("mvn:foo/my-bsn/1.3.2", itb.next());
        assertNull(patch.getResult());
        verify(componentContext, sysBundleContext, sysBundle, bundleContext, bundle, bsl);

        //
        // Install the patch
        //

        reset(componentContext, sysBundleContext, sysBundle, bundleContext, bundle, bsl);

        expect(sysBundleContext.getBundles()).andReturn(new Bundle[] { bundle });
        expect(sysBundleContext.getServiceReference("io.fabric8.api.FabricService")).andReturn(null).anyTimes();
        expect(bundle.getSymbolicName()).andReturn("my-bsn").anyTimes();
        expect(bundle.getVersion()).andReturn(new Version("1.3.1")).anyTimes();
        expect(bundle.getLocation()).andReturn("location").anyTimes();
        expect(bundle.getHeaders()).andReturn(new Hashtable<String, String>()).anyTimes();
        expect(bundle.getBundleId()).andReturn(123L).anyTimes();
        bundle.update(EasyMock.<InputStream>anyObject());
        expect(sysBundleContext.getBundles()).andReturn(new Bundle[] { bundle });
        expect(bundle.getState()).andReturn(Bundle.INSTALLED).anyTimes();
        expect(bundle.getRegisteredServices()).andReturn(null);
        expect(bundle.adapt(BundleStartLevel.class)).andReturn(bsl).anyTimes();
        expect(bsl.getStartLevel()).andReturn(30).anyTimes();
        expect(sysBundleContext.getBundle(0)).andReturn(sysBundle);
        expect(sysBundle.adapt(FrameworkWiring.class)).andReturn(wiring);
        expect(sysBundleContext.getProperty("karaf.default.repository")).andReturn("system").anyTimes();
        bundle.start();
        wiring.refreshBundles(eq(asSet(bundle)), anyObject(FrameworkListener[].class));
        expectLastCall().andAnswer(new IAnswer<Object>() {
            @Override
            public Object answer() throws Throwable {
                ((FrameworkListener) (EasyMock.getCurrentArguments()[1])).frameworkEvent(null);
                return null;
            }
        });
        replay(componentContext, sysBundleContext, sysBundle, bundleContext, bundle, bundle2, wiring, bsl);

        result = service.install(patch, false);
        assertNotNull( result );
        assertSame( result, patch.getResult() );
        assertFalse(patch.getResult().isSimulation());

        verify(componentContext, sysBundleContext, sysBundle, bundleContext, bundle, wiring);

        //
        // Recreate a new service and verify the downloaded patch is still available and installed
        //

        reset(componentContext, sysBundleContext, sysBundle, bundleContext, bundle, repository);
        expect(componentContext.getBundleContext()).andReturn(bundleContext);
        expect(bundleContext.getBundle(0)).andReturn(sysBundle);
        expect(sysBundle.getBundleContext()).andReturn(sysBundleContext);
        expect(repository.getManagedPatch(anyString())).andReturn(null).anyTimes();
        expect(sysBundleContext.getProperty(Service.NEW_PATCH_LOCATION))
                .andReturn(storage.toString()).anyTimes();
        expect(sysBundleContext.getProperty("karaf.home"))
                .andReturn(karaf.toString()).anyTimes();
        expect(sysBundleContext.getProperty("karaf.base"))
                .andReturn(karaf.getCanonicalPath()).anyTimes();
        expect(sysBundleContext.getProperty("karaf.name"))
                .andReturn("root").anyTimes();
        expect(sysBundleContext.getProperty("karaf.instances"))
                .andReturn(karaf.getCanonicalPath() + "/instances").anyTimes();
        expect(sysBundleContext.getProperty("karaf.default.repository")).andReturn("system").anyTimes();
        replay(componentContext, sysBundleContext, sysBundle, bundleContext, bundle, repository);

        service = new ServiceImpl();
        setField(service, "patchManagement", pm);
        service.activate(componentContext);

        patches = service.getPatches();
        assertNotNull(patches);
        it = patches.iterator();
        assertTrue( it.hasNext() );
        patch = it.next();
        assertNotNull( patch );
        assertEquals("patch-1.3.2", patch.getPatchData().getId());
        assertNotNull(patch.getPatchData().getBundles());
        assertEquals(1, patch.getPatchData().getBundles().size());
        itb = patch.getPatchData().getBundles().iterator();
        assertEquals("mvn:foo/my-bsn/1.3.2", itb.next());
        assertNotNull(patch.getResult());
        verify(componentContext, sysBundleContext, sysBundle, bundleContext, bundle);
    }

    private GitPatchManagementServiceImpl mockManagementService(final BundleContext bundleContext) throws IOException {
        return new GitPatchManagementServiceImpl(bundleContext) {
            @Override
            public Patch trackPatch(PatchData patchData) throws PatchException {
                return new Patch(patchData, null);
            }
            @Override
            public String beginInstallation(PatchKind kind) {
                this.pendingTransactionsTypes.put("tx", kind);
                this.pendingTransactions.put("tx", null);
                return "tx";
            }
            @Override
            public void install(String transaction, Patch patch, List<BundleUpdate> bundleUpdatesInThisPatch) {
            }
            @Override
            public void rollbackInstallation(String transaction) {
            }
            @Override
            public void commitInstallation(String transaction) {
            }

        };
    }

    @Test
    public void testPatchWithVersionRanges() throws Exception {
        ComponentContext componentContext = createMock(ComponentContext.class);
        BundleContext bundleContext = createMock(BundleContext.class);
        Bundle sysBundle = createMock(Bundle.class);
        BundleContext sysBundleContext = createMock(BundleContext.class);
        Bundle bundle = createMock(Bundle.class);
        Bundle bundle2 = createMock(Bundle.class);
        FrameworkWiring wiring = createMock(FrameworkWiring.class);
        GitPatchRepository repository = createMock(GitPatchRepository.class);

        //
        // Create a new service, download a patch
        //
        expect(componentContext.getBundleContext()).andReturn(bundleContext);
        expect(bundleContext.getBundle(0)).andReturn(sysBundle).anyTimes();
        expect(sysBundle.getBundleContext()).andReturn(sysBundleContext).anyTimes();
        expect(sysBundleContext.getProperty(Service.NEW_PATCH_LOCATION))
                .andReturn(storage.toString()).anyTimes();
        expect(repository.getManagedPatch(anyString())).andReturn(null).anyTimes();
        expect(repository.findOrCreateMainGitRepository()).andReturn(null).anyTimes();
        expect(sysBundleContext.getProperty("karaf.default.repository")).andReturn("system").anyTimes();
        expect(sysBundleContext.getProperty("karaf.home"))
                .andReturn(karaf.getCanonicalPath()).anyTimes();
        expect(sysBundleContext.getProperty("karaf.base"))
                .andReturn(karaf.getCanonicalPath()).anyTimes();
        expect(sysBundleContext.getProperty("karaf.name"))
                .andReturn("root").anyTimes();
        expect(sysBundleContext.getProperty("karaf.instances"))
                .andReturn(karaf.getCanonicalPath() + "/instances").anyTimes();
        expect(sysBundleContext.getProperty("karaf.data")).andReturn(karaf.getCanonicalPath() + "/data").anyTimes();
        replay(componentContext, sysBundleContext, sysBundle, bundleContext, bundle, repository);

        PatchManagement pm = mockManagementService(bundleContext);
        ((GitPatchManagementServiceImpl)pm).setGitPatchRepository(repository);

        ServiceImpl service = new ServiceImpl();
        setField(service, "patchManagement", pm);
        service.activate(componentContext);
        Iterable<Patch> patches = service.download(patch140.toURI().toURL());
        assertNotNull(patches);
        Iterator<Patch> it = patches.iterator();
        assertTrue( it.hasNext() );
        Patch patch = it.next();
        assertNotNull( patch );
        assertEquals("patch-1.4.0", patch.getPatchData().getId());
        assertNotNull(patch.getPatchData().getBundles());
        assertEquals(1, patch.getPatchData().getBundles().size());
        Iterator<String> itb = patch.getPatchData().getBundles().iterator();
        assertEquals("mvn:foo/my-bsn/1.4.0", itb.next());
        assertNull(patch.getResult());
        verify(componentContext, sysBundleContext, sysBundle, bundleContext, bundle);

        //
        // Simulate the patch
        //
        reset(componentContext, sysBundleContext, sysBundle, bundleContext, bundle);

        expect(sysBundleContext.getBundles()).andReturn(new Bundle[] { bundle });
        expect(sysBundleContext.getServiceReference("io.fabric8.api.FabricService")).andReturn(null);
        expect(bundle.getSymbolicName()).andReturn("my-bsn").anyTimes();
        expect(bundle.getVersion()).andReturn(new Version("1.3.1")).anyTimes();
        expect(bundle.getLocation()).andReturn("location").anyTimes();
        expect(bundle.getBundleId()).andReturn(123L);
        BundleStartLevel bsl = createMock(BundleStartLevel.class);
        expect(bsl.getStartLevel()).andReturn(30).anyTimes();
        expect(bundle.adapt(BundleStartLevel.class)).andReturn(bsl).anyTimes();
        expect(bundle.getState()).andReturn(1);
        expect(sysBundleContext.getProperty("karaf.default.repository")).andReturn("system").anyTimes();
        replay(componentContext, sysBundleContext, sysBundle, bundleContext, bundle, bsl);

        PatchResult result = service.install(patch, true);
        assertNotNull( result );
        assertNull( patch.getResult() );
        assertEquals(1, result.getBundleUpdates().size());
        assertTrue(result.isSimulation());
    }

    @Test
    public void testVersionHistory() {
        // the same bundle has been patched twice
        Patch patch1 = new Patch(new PatchData("patch1", "First patch", null, null, null, null, null), null);
        patch1.setResult(new PatchResult(patch1.getPatchData(), true, System.currentTimeMillis(), new LinkedList<io.fabric8.patch.management.BundleUpdate>(), null));
        patch1.getResult().getBundleUpdates().add(new BundleUpdate("my-bsn", "1.1.0", "mvn:groupId/my-bsn/1.1.0",
                "1.0.0", "mvn:groupId/my-bsn/1.0.0"));
        Patch patch2 = new Patch(new PatchData("patch2", "Second patch", null, null, null, null, null), null);
        patch2.setResult(new PatchResult(patch1.getPatchData(), true, System.currentTimeMillis(), new LinkedList<io.fabric8.patch.management.BundleUpdate>(), null));
        patch2.getResult().getBundleUpdates().add(new BundleUpdate("my-bsn;directive1=true", "1.2.0", "mvn:groupId/my-bsn/1.2.0",
                "1.1.0", "mvn:groupId/my-bsn/1.1.0"));
        Map<String, Patch> patches = new HashMap<String, Patch>();
        patches.put("patch1", patch1);
        patches.put("patch2", patch2);

        // the version history should return the correct URL, even when bundle.getLocation() does not
        ServiceImpl.BundleVersionHistory  history = new ServiceImpl.BundleVersionHistory(patches);
        assertEquals("Should return version from patch result instead of the original location",
                     "mvn:groupId/my-bsn/1.2.0",
                     history.getLocation(createMockBundle("my-bsn", "1.2.0", "mvn:groupId/my-bsn/1.0.0")));
        assertEquals("Should return version from patch result instead of the original location",
                     "mvn:groupId/my-bsn/1.1.0",
                     history.getLocation(createMockBundle("my-bsn", "1.1.0", "mvn:groupId/my-bsn/1.0.0")));
        assertEquals("Should return original bundle location if no maching version is found in the history",
                     "mvn:groupId/my-bsn/1.0.0",
                     history.getLocation(createMockBundle("my-bsn", "1.0.0", "mvn:groupId/my-bsn/1.0.0")));
        assertEquals("Should return original bundle location if no maching version is found in the history",
                     "mvn:groupId/my-bsn/0.9.0",
                     history.getLocation(createMockBundle("my-bsn", "0.9.0", "mvn:groupId/my-bsn/0.9.0")));
    }

    private Bundle createMockBundle(String bsn, String version, String location) {
        Bundle result = createNiceMock(Bundle.class);
        expect(result.getSymbolicName()).andReturn(bsn).anyTimes();
        expect(result.getVersion()).andReturn(Version.parseVersion(version));
        expect(result.getLocation()).andReturn(location);
        replay(result);
        return result;
    }

    private void generateData() throws Exception {
        karaf = new File(baseDir, "karaf");
        delete(karaf);
        karaf.mkdirs();
        new File(karaf, "etc").mkdir();
        new File(karaf, "etc/startup.properties").createNewFile();
        System.setProperty("karaf.base", karaf.getAbsolutePath());
        System.setProperty("karaf.home", karaf.getAbsolutePath());
        System.setProperty("karaf.name", "root");

        storage = new File(baseDir, "storage");
        delete(storage);
        storage.mkdirs();

        bundlev131 = createBundle("my-bsn", "1.3.1");
        bundlev132 = createBundle("my-bsn;directive1:=true; directve2:=1000", "1.3.2");
        bundlev140 = createBundle("my-bsn", "1.4.0");
        bundlev200 = createBundle("my-bsn", "2.0.0");

        patch132 = createPatch("patch-1.3.2", bundlev132, "mvn:foo/my-bsn/1.3.2");
        patch140 = createPatch("patch-1.4.0", bundlev140, "mvn:foo/my-bsn/1.4.0", "[1.3.0,1.5.0)");
        patch200 = createPatch("patch-2.0.0", bundlev140, "mvn:foo/my-bsn/2.0.0");

        createPatch("patch-with-prereq2", bundlev132, "mvn:foo/my-bsn/1.3.2", null, "prereq2");
    }

    private File createPatch(String id, File bundle, String mvnUrl) throws Exception {
        return createPatch(id, bundle, mvnUrl, null);
    }

    private File createPatch(String id, File bundle, String mvnUrl, String range) throws Exception {
        return createPatch(id, bundle, mvnUrl, range, null);
    }

    private File createPatch(String id, File bundle, String mvnUrl, String range, String requirement) throws Exception {
        File patchFile = new File(storage, "temp/" + id + ".zip");
        File pd = new File(storage, "temp/" + id + "/" + id + ".patch");
        pd.getParentFile().mkdirs();
        Properties props = new Properties();
        props.put("id", id);
        props.put("bundle.count", "1");
        props.put("bundle.0", mvnUrl);
        if (range != null) {
            props.put("bundle.0.range", range);
        }
        if (requirement != null) {
            props.put("requirement.count", "1");
            props.put("requirement.O", requirement);
        }
        FileOutputStream fos = new FileOutputStream(pd);
        props.store(fos, null);
        fos.close();
        File bf = new File(storage, "temp/" + id + "/repository/" + Utils.mvnurlToArtifact(mvnUrl, true).getPath());
        bf.getParentFile().mkdirs();
        IOUtils.copy(new FileInputStream(bundle), new FileOutputStream(bf));
        fos = new FileOutputStream(patchFile);
        jarDir(pd.getParentFile(), fos);
        fos.close();
        return patchFile;
    }

    private File createBundle(String bundleSymbolicName, String version) throws Exception {
        File jar = new File(storage, "temp/" + stripSymbolicName(bundleSymbolicName) + "-" + version + ".jar");
        File man = new File(storage, "temp/" + stripSymbolicName(bundleSymbolicName) + "-" + version + "/META-INF/MANIFEST.MF");
        man.getParentFile().mkdirs();
        Manifest mf = new Manifest();
        mf.getMainAttributes().putValue("Manifest-Version", "1.0");
        mf.getMainAttributes().putValue("Bundle-ManifestVersion", "2");
        mf.getMainAttributes().putValue("Bundle-SymbolicName", bundleSymbolicName);
        mf.getMainAttributes().putValue("Bundle-Version", version);
        FileOutputStream fos = new FileOutputStream(man);
        mf.write(fos);
        fos.close();
        fos = new FileOutputStream(jar);
        jarDir(man.getParentFile().getParentFile(), fos);
        fos.close();
        return jar;
    }

    @SafeVarargs
    private final <T> Set<T> asSet(T... objects) {
        HashSet<T> set = new HashSet<T>();
        Collections.addAll(set, objects);
        return set;
    }

    private void delete(File file) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                delete( child );
            }
            file.delete();
        } else if (file.isFile()) {
            file.delete();
        }
    }

    private URL getZippedTestDir(String name) throws IOException {
        File f2 = new File(baseDir, name + ".jar");
        OutputStream os = new FileOutputStream(f2);
        jarDir(new File(baseDir, name), os);
        os.close();
        return f2.toURI().toURL();
    }




    public static void jarDir(File directory, OutputStream os) throws IOException
    {
        // create a ZipOutputStream to zip the data to
        JarOutputStream zos = new JarOutputStream(os);
        zos.setLevel(Deflater.NO_COMPRESSION);
        String path = "";
        File manFile = new File(directory, JarFile.MANIFEST_NAME);
        if (manFile.exists())
        {
            byte[] readBuffer = new byte[8192];
            FileInputStream fis = new FileInputStream(manFile);
            try
            {
                ZipEntry anEntry = new ZipEntry(JarFile.MANIFEST_NAME);
                zos.putNextEntry(anEntry);
                int bytesIn = fis.read(readBuffer);
                while (bytesIn != -1)
                {
                    zos.write(readBuffer, 0, bytesIn);
                    bytesIn = fis.read(readBuffer);
                }
            }
            finally
            {
                fis.close();
            }
            zos.closeEntry();
        }
        zipDir(directory, zos, path, Collections.singleton(JarFile.MANIFEST_NAME));
        // close the stream
        zos.close();
    }

    public static void zipDir(File directory, ZipOutputStream zos, String path, Set/* <String> */ exclusions) throws IOException
    {
        // get a listing of the directory content
        File[] dirList = directory.listFiles();
        byte[] readBuffer = new byte[8192];
        int bytesIn = 0;
        // loop through dirList, and zip the files
        for (int i = 0; i < dirList.length; i++)
        {
            File f = dirList[i];
            if (f.isDirectory())
            {
                String prefix = path + f.getName() + "/";
                zos.putNextEntry(new ZipEntry(prefix));
                zipDir(f, zos, prefix, exclusions);
                continue;
            }
            String entry = path + f.getName();
            if (!exclusions.contains(entry))
            {
                FileInputStream fis = new FileInputStream(f);
                try
                {
                    ZipEntry anEntry = new ZipEntry(entry);
                    zos.putNextEntry(anEntry);
                    bytesIn = fis.read(readBuffer);
                    while (bytesIn != -1)
                    {
                        zos.write(readBuffer, 0, bytesIn);
                        bytesIn = fis.read(readBuffer);
                    }
                }
                finally
                {
                    fis.close();
                }
            }
        }
    }

    public class CustomBundleURLStreamHandlerFactory implements
            URLStreamHandlerFactory {
        private static final String MVN_URI_PREFIX = "mvn";

        public URLStreamHandler createURLStreamHandler(String protocol) {
            if (protocol.equals(MVN_URI_PREFIX)) {
                return new MvnHandler();
            } else {
                return null;
            }
        }
    }

    public class MvnHandler extends URLStreamHandler {
        @Override
        protected URLConnection openConnection(URL u) throws IOException {
            if (u.toString().equals("mvn:foo/my-bsn/1.3.1")) {
                return bundlev131.toURI().toURL().openConnection();
            }
            if (u.toString().equals("mvn:foo/my-bsn/1.3.2")) {
                return bundlev132.toURI().toURL().openConnection();
            }
            if (u.toString().equals("mvn:foo/my-bsn/1.4.0")) {
                return bundlev140.toURI().toURL().openConnection();
            }
            if (u.toString().equals("mvn:foo/my-bsn/2.0.0")) {
                return bundlev200.toURI().toURL().openConnection();
            }
            throw new IllegalArgumentException(u.toString());
        }
    }

}
