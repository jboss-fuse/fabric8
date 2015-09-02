/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fabric8.patch.installer;

import io.fabric8.api.*;
import io.fabric8.api.scr.ValidatingReference;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

@Component(
    name = "io.fabric8.patch.installer.FabricInstaller",
    label = "Fuse Patch Installer",
    policy = ConfigurationPolicy.IGNORE,
    immediate = true
)
public class FabricInstaller {
    public static final Logger LOG = LoggerFactory.getLogger(FabricInstaller.class);

    // reference the StandaloneInstaller so that it's initialized before these fabric bits.
    @Reference(referenceInterface = StandaloneInstaller.class)
    private final ValidatingReference<StandaloneInstaller> standaloneInstaller = new ValidatingReference<>();
    void bindStandaloneInstaller(StandaloneInstaller service) {
        this.standaloneInstaller.bind(service);
    }
    void unbindStandaloneInstaller(StandaloneInstaller service) {
        this.standaloneInstaller.unbind(service);
    }

    @Reference(referenceInterface = FabricService.class)
    private final ValidatingReference<FabricService> fabricService = new ValidatingReference<>();
    void bindFabricService(FabricService service) {
        this.fabricService.bind(service);
    }
    void unbindFabricService(FabricService service) {
        this.fabricService.unbind(service);
    }

    @Activate
    void activate() throws Exception {
        FabricService fabricService = this.fabricService.get();
        Version version = fabricService.getCurrentContainer().getVersion();
        Profile profile = version.getProfile("karaf");
        if( profile!=null ) {
            final String COMPAT_BUNDLE = "mvn:org.apache.aries.blueprint/org.apache.aries.blueprint.core.compatibility/1.0.0";
            if( !profile.getBundles().contains(COMPAT_BUNDLE) ) {

                ProfileBuilder builder = ProfileBuilder.Factory.createFrom(profile);
                ArrayList<String> bundles = new ArrayList<String>(profile.getBundles());
                bundles.add(COMPAT_BUNDLE);
                builder.setBundles(bundles);
                fabricService.adapt(ProfileService.class).updateProfile(builder.getProfile());

                LOG.info("Updated: karaf fabric profile");

            }
        }
    }
}
