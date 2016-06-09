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
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;
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
    @Reference(referenceInterface = MBeanServer.class, bind = "bindGuardedMBeanServer", unbind = "unbindGuardedMBeanServer", target = "(guarded=true)", policy = ReferencePolicy.DYNAMIC)
    private final ValidatingReference<MBeanServer> guardedMBeanServer = new ValidatingReference<>();

    @Activate
    void activate() {
        activateInternal();
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
        deactivateInternal();
    }

    private void activateInternal() {
        MBeanServer server = mbeanServer.get();
        try {
            ObjectName jolokiaServerName = new ObjectName("jolokia:type=MBeanServer");
            if (!mbeanServer.get().isRegistered(jolokiaServerName)) {
                mbeanServer.get().registerMBean(new JolokiaMBeanHolder(guardedMBeanServer.get()), jolokiaServerName);
            }
        } catch (InstanceAlreadyExistsException e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(e.getMessage());
            }
        } catch (Exception e) {
            LOGGER.warn(e.getMessage(), e);
        }

        try {
            ProfileManagement profileMXBean = new ProfileManagementImpl();
            server.registerMBean(new StandardMBean(profileMXBean, ProfileManagement.class, true), new ObjectName(ProfileManagement.OBJECT_NAME));
        } catch (JMException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void deactivateInternal() {
        MBeanServer server = mbeanServer.get();
        try {
            ObjectName jolokiaServerName = new ObjectName("jolokia:type=MBeanServer");
            mbeanServer.get().unregisterMBean(jolokiaServerName);
        } catch (Throwable t) {
            LOGGER.warn("Error while unregistering \"jolokia:type=MBeanServer\"");
        }
        try {
            server.unregisterMBean(new ObjectName(ProfileManagement.OBJECT_NAME));
        } catch (JMException ex) {
            throw new IllegalStateException(ex);
        }
    }

    void bindMbeanServer(MBeanServer service) {
        this.mbeanServer.bind(service);
    }

    void unbindMbeanServer(MBeanServer service) {
        this.mbeanServer.unbind(service);
    }

    void bindGuardedMBeanServer(MBeanServer mBeanServer) {
        this.guardedMBeanServer.bind(mBeanServer);
    }

    void unbindGuardedMBeanServer(MBeanServer mBeanServer) {
        this.guardedMBeanServer.unbind(mBeanServer);
    }

}
