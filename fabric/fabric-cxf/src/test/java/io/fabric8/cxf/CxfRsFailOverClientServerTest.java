/**
 *  Copyright 2005-2017 Red Hat, Inc.
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
package io.fabric8.cxf;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import org.apache.cxf.Bus;
import org.apache.cxf.feature.AbstractFeature;
import org.apache.cxf.interceptor.Interceptor;
import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
import org.apache.cxf.jaxrs.client.JAXRSClientFactoryBean;
import org.apache.cxf.message.Message;
import org.junit.After;
import org.junit.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.AbstractJUnit4SpringContextTests;

@ContextConfiguration(locations = {"FailOverContext.xml"})
public class CxfRsFailOverClientServerTest extends AbstractJUnit4SpringContextTests {
    @Autowired
    protected Bus bus;
    @Autowired
    protected FabricLoadBalancerFeature feature;

    @After
    public void shutdown() throws Exception {
        if (applicationContext instanceof DisposableBean) {
            ((DisposableBean) applicationContext).destroy();
        }
    }

    @Test
    public void testClientServer() throws Exception {
        assertNotNull(bus);
        // The bus is load the feature
        JAXRSServerFactoryBean factory = new JAXRSServerFactoryBean();
        factory.setResourceClasses(CustomerService.class);
        factory.setAddress("http://localhost:9000/fail/server");
        factory.setBus(bus);
        factory.create();


        factory = new JAXRSServerFactoryBean();
        factory.setServiceClass(CustomerService.class);
        factory.setResourceClasses(CustomerService.class);
        factory.setBus(bus);
        factory.create();

        // sleep a while to let the service be published
        for (int i = 0; i < 100; i++) {
            if (!feature.getLoadBalanceStrategy().getAlternateAddressList().isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }

        JAXRSClientFactoryBean clientFactory = new JAXRSClientFactoryBean();
        clientFactory.setServiceClass(CustomerServiceResources.class);
        // The address is not the actual address that the client will access
        clientFactory.setAddress("http://someotherplace");

        List<AbstractFeature> features = new ArrayList<>();
        // add the instance of FabricLoadBalancerFeature into features list
        features.add(feature);
        // we need to setup the feature on the clientfactory
        clientFactory.setFeatures(features);
        // set this interceptor to simulate the Transport level exception
        List<Interceptor<? extends Message>> outInterceptor = clientFactory.getOutInterceptors();
        outInterceptor.add(new TransportFailureInterceptor());
        clientFactory.setOutInterceptors(outInterceptor);

        CustomerServiceResources proxy = clientFactory.create(CustomerServiceResources.class);
        Customer response = proxy.getCustomer("123");

        assertEquals("Get a wrong customer name", "John", response.getName());

        response = proxy.getCustomer("123");
        assertEquals("Get a wrong customer name", "John", response.getName());
    }

}
