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
package io.fabric8.core.mxbean;

import io.fabric8.api.mxbean.ProfileManagement;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.api.scr.ValidatingReference;

import javax.management.InstanceAlreadyExistsException;
import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A provider of system MXBeans
 */
@Component(policy = ConfigurationPolicy.IGNORE, immediate = true)
@Service(MXBeansProvider.class)
public final class MXBeansProvider extends AbstractComponent {

    public static Logger LOGGER = LoggerFactory.getLogger(MXBeansProvider.class);

    @Reference(referenceInterface = MBeanServer.class)
    private final ValidatingReference<MBeanServer> mbeanServer = new ValidatingReference<>();

    private BundleContext context;
    private ServiceTracker<MBeanServer, MBeanServer> tracker;

    @Activate
    void activate(BundleContext context) throws InvalidSyntaxException {
        this.context = context;
        activateInternal();
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
        deactivateInternal();
    }

    private void activateInternal() throws InvalidSyntaxException {
        this.tracker = new ServiceTracker<>(context, context.createFilter("(&(objectClass=javax.management.MBeanServer)(guarded=true))"),
                new ServiceTrackerCustomizer<MBeanServer, MBeanServer>() {
            @Override
            public MBeanServer addingService(ServiceReference<MBeanServer> reference) {
                MBeanServer server = MXBeansProvider.this.context.getService(reference);
                registerGuardedMBeanServer(server);
                return server;
            }

            @Override
            public void modifiedService(ServiceReference<MBeanServer> reference, MBeanServer service) {
                MBeanServer server = MXBeansProvider.this.context.getService(reference);
                registerGuardedMBeanServer(null);
                registerGuardedMBeanServer(server);
            }

            @Override
            public void removedService(ServiceReference<MBeanServer> reference, MBeanServer service) {
                registerGuardedMBeanServer(null);
                MXBeansProvider.this.context.ungetService(reference);
            }
        });
        this.tracker.open();

        MBeanServer server = mbeanServer.get();
        try {
            ProfileManagement profileMXBean = new ProfileManagementImpl();
            server.registerMBean(new StandardMBean(profileMXBean, ProfileManagement.class, true), new ObjectName(ProfileManagement.OBJECT_NAME));
        } catch (JMException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void deactivateInternal() {
        try {
            if (this.tracker != null) {
                this.tracker.close();
            }
            MBeanServer server = mbeanServer.get();
            server.unregisterMBean(new ObjectName(ProfileManagement.OBJECT_NAME));
        } catch (JMException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * (Un)Registers guarded {@link MBeanServer} in JMX to be used by Jolokia
     * @param server
     */
    private void registerGuardedMBeanServer(MBeanServer server) {
        if (server != null) {
            try {
                ObjectName jolokiaServerName = new ObjectName("jolokia:type=MBeanServer");
                if (!mbeanServer.get().isRegistered(jolokiaServerName)) {
                    mbeanServer.get().registerMBean(new JolokiaMBeanHolder(server), jolokiaServerName);
                }
            } catch (InstanceAlreadyExistsException e) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(e.getMessage());
                }
            } catch (Exception e) {
                LOGGER.warn(e.getMessage(), e);
            }
        } else {
            try {
                ObjectName jolokiaServerName = new ObjectName("jolokia:type=MBeanServer");
                mbeanServer.get().unregisterMBean(jolokiaServerName);
            } catch (Throwable t) {
                LOGGER.warn("Error while unregistering \"jolokia:type=MBeanServer\"");
            }
        }
    }

    void bindMbeanServer(MBeanServer service) {
        this.mbeanServer.bind(service);
    }

    void unbindMbeanServer(MBeanServer service) {
        this.mbeanServer.unbind(service);
    }

}
