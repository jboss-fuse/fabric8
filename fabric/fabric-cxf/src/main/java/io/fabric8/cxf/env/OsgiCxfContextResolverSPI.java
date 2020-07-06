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
package io.fabric8.cxf.env;

import java.io.IOException;
import java.util.Dictionary;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;

import org.apache.cxf.common.logging.LogUtils;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.util.tracker.ServiceTracker;

import io.fabric8.cxf.endpoint.ManagedApi;

public class OsgiCxfContextResolverSPI extends CxfContextResolver.CxfContextResolverSPI {

   
    private String cxfServletContext;
 
    private ConfigurationAdmin configurationAdmin;
    
    private static final Logger LOG = LogUtils.getL7dLogger(OsgiCxfContextResolverSPI.class);

    public OsgiCxfContextResolverSPI() {
        Bundle bundle = FrameworkUtil.getBundle(OsgiCxfContextResolverSPI.class);
        if (bundle != null) {
            this.cxfServletContext = "/cxf";
            if (getConfigurationAdmin() != null) {
                try {
                    Configuration configuration = getConfigurationAdmin().getConfiguration("org.apache.cxf.osgi", null);
                    if (configuration != null) {
                        Dictionary properties = configuration.getProperties();
                        if (properties != null) {
                            String servletContext = (String)configuration.getProperties().
                                get("org.apache.cxf.servlet.context");
                            if (servletContext != null) {
                                this.cxfServletContext = servletContext;
                            }
                        }
                    }
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "getServletContext failed.", e);
                }
            }
        } else {
            throw new UnsupportedOperationException("OSGi Framework not detected");
        }
    }

    @Override
    public String getCxfServletContext() {
        return cxfServletContext;
    }

    private ConfigurationAdmin getConfigurationAdmin() {
       
        if (configurationAdmin == null) {
            BundleContext bundleContext = FrameworkUtil.getBundle(ManagedApi.class).getBundleContext();
            if (bundleContext != null) {
                ServiceReference serviceReference = bundleContext
                    .getServiceReference(ConfigurationAdmin.class.getName());
                if (serviceReference != null) {
                    configurationAdmin = (ConfigurationAdmin)bundleContext.getService(serviceReference);
                }
            }

        }

        return configurationAdmin;
    }
}
