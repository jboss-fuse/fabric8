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

import java.util.LinkedList;
import java.util.List;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import io.fabric8.api.jmx.ScrComponentStatus;
import io.fabric8.api.jmx.ScrHelperMBean;
import io.fabric8.api.mxbean.ProfileManagement;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.common.util.JMXUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(name = "io.fabric8.scrservice", label = "Fabric8 SCR Service")
public class ScrService extends AbstractComponent implements ScrHelperMBean {

    public static Logger LOG = LoggerFactory.getLogger(ScrService.class);
    public static ObjectName OBJECT_NAME;

    @Reference(referenceInterface = org.apache.felix.scr.ScrService.class)
    private org.apache.felix.scr.ScrService scrService;

    @Reference(referenceInterface = MBeanServer.class, bind = "bindMBeanServer", unbind = "unbindMBeanServer")
    private MBeanServer mbeanServer;

    static {
        try {
            OBJECT_NAME = new ObjectName("io.fabric8:service=Scr");
        } catch (MalformedObjectNameException e) {
            // ignore
        }
    }

    @Activate
    void activate() throws Exception {
        if (mbeanServer != null) {
            JMXUtils.registerMBean(new StandardMBean(this, ScrHelperMBean.class, true), mbeanServer, OBJECT_NAME);
        }
        activateComponent();
    }

    @Deactivate
    void deactivate() throws Exception {
        if (mbeanServer != null) {
            JMXUtils.unregisterMBean(mbeanServer, OBJECT_NAME);
        }
        deactivateComponent();
    }

    @Override
    public List<ScrComponentStatus> listComponents() {
        List<ScrComponentStatus> scrComponents = new LinkedList<ScrComponentStatus>();
        if (scrService != null) {
            for (org.apache.felix.scr.Component c : scrService.getComponents()) {
                scrComponents.add(new ScrComponentStatus(c.getName(), c.getState(), getStateName(c.getState())));
            }
        }

        return scrComponents;
    }

    void bindMBeanServer(MBeanServer mbeanServer) {
        this.mbeanServer = mbeanServer;
    }

    void unbindMBeanServer(MBeanServer mbeanServer) {
        this.mbeanServer = null;
    }

    private String getStateName(int state) {
        switch (state) {
            case 2:
                return "Enabled";
            case 4:
                return "Unsatisfied";
            case 8:
                return "Activating";
            case 16:
                return "Active";
            case 32:
                return "Registered";
            case 64:
                return "Factory";
            case 128:
                return "Deactivating";
            case 256:
                return "Destroying";
            case 1024:
                return "Disabling";
            case 2048:
                return "Disposing";

        }
        return "Unknown";
    }

}
