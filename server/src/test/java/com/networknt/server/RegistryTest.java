/*
 * Copyright (c) 2016 Network New Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.networknt.server;

import com.networknt.cluster.LightCluster;
import com.networknt.config.Config;
import com.networknt.consul.ConsulConfig;
import com.networknt.consul.ConsulRegistry;
import com.networknt.consul.client.ConsulClientImpl;
import com.networknt.registry.URL;
import com.networknt.registry.URLImpl;
import com.networknt.utility.Constants;
import com.networknt.utility.Util;
import org.junit.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.networknt.consul.ConsulConstants.CONFIG_NAME;
import static com.networknt.server.Server.STATUS_HOST_IP;
import static org.junit.Assert.fail;

/**
 * This test is an automatic test for real consul registry and discovery by using testContainers.
 * Since it depends on docker so it should be disabled all the time unless it is used.
 */
@Ignore
public class RegistryTest {
    static final Logger logger = LoggerFactory.getLogger(RegistryTest.class);
    static ConsulRegistry registry;
    static LightCluster lightCluster;
    static String serviceId = "com.networknt.petstore-1.0.0";
    static String ipAddress;
    static CountDownLatch latch = new CountDownLatch(1);

    static ConsulConfig consulConfig = (ConsulConfig) Config.getInstance().getJsonObjectConfig(CONFIG_NAME, ConsulConfig.class);
    static ServerConfig serverConfig = (ServerConfig) Config.getInstance().getJsonObjectConfig("server", ServerConfig.class);

    static Server server1 = null;
    static Server server2 = null;
    static Server server3 = null;

    @ClassRule
    public static GenericContainer consul
            = new GenericContainer("consul")
            .withExposedPorts(8500);

    @BeforeClass
    public static void setUp() throws InterruptedException {
        //set ip address
        ipAddress = System.getenv(STATUS_HOST_IP);
        logger.info("Registry IP from STATUS_HOST_IP is " + ipAddress);
        if (ipAddress == null) {
            InetAddress inetAddress = Util.getInetAddress();
            ipAddress = inetAddress.getHostAddress();
            logger.info("Could not find IP from STATUS_HOST_IP, use the InetAddress " + ipAddress);
        }
        //refresh consul config with dynamic port generated by container test
        Integer port = consul.getFirstMappedPort();
        Map<String, String> params = new HashMap<>();
        params.put("registrySessionTimeout", "1000");
        consulConfig.setConsulUrl("http://localhost:" + port);
        //construct registry
        registry = new ConsulRegistry(new URLImpl("light", "localhost", port, "", params), new ConsulClientImpl());
        //construct cluster
        lightCluster = new LightCluster();
        //start server without registry, the registry is be done separately for testing
        startServer(49588, null, server1);
        startServer(49589, "", server2);
        startServer(49590, "dev", server3);
        //wait for registry complete
        latch.await(10, TimeUnit.SECONDS);
    }

    @AfterClass
    public static void tearDown() {
        if (server1 != null) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {

            }
            Server.stop();
            logger.info("The server1 is stopped.");
        }
        if (server2 != null) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {

            }
            server2.stop();
            logger.info("The server2 is stopped.");
        }
        if (server3 != null) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {

            }
            server3.stop();
            logger.info("The server3 is stopped.");
        }
    }

    @Test
    public void testDiscoverWithoutEnvTag() {
        String url = lightCluster.serviceToUrl("https", serviceId, null, null);
        Assert.assertNotNull(url);
        Assert.assertTrue(url.equals("https://" + ipAddress + ":49588") || url.equals("https://" + ipAddress + ":49589"));
    }

    @Test
    public void testDiscoverWithEmptyStringEnvTag() {
        String url = lightCluster.serviceToUrl("https", serviceId, "", null);
        Assert.assertNotNull(url);
        Assert.assertTrue(url.equals("https://" + ipAddress + ":49588") || url.equals("https://" + ipAddress + ":49589"));
    }

    @Test
    public void testDiscoverWithNonEmptyStringEnvTag() {
        String url = lightCluster.serviceToUrl("https", serviceId, "dev", null);
        Assert.assertNotNull(url);
        Assert.assertEquals("https://" + ipAddress + ":49590", url);
    }

    @Test
    public void testDiscoverWithNonexistentEnvTag() {
        try {
            String url = lightCluster.serviceToUrl("https", serviceId, "sit", null);
            fail();
        } catch(Exception e) {

        }
    }

    @Test
    public void testDiscoverCaching() {
        String url = lightCluster.serviceToUrl("https", serviceId, "dev", null);
        Assert.assertNotNull(url);
        Assert.assertEquals("https://" + ipAddress + ":49590", url);
        try {
            lightCluster.serviceToUrl("https", serviceId, "sit", null);
            fail();
        } catch(Exception e) {

        }
        url = lightCluster.serviceToUrl("https", serviceId, "dev", null);
        Assert.assertNotNull(url);
        Assert.assertEquals("https://" + ipAddress + ":49590", url);
    }

    private static void startServer(int port, String envTag, Server server) {
        serverConfig.setHttpsPort(port);
        if (server2 == null) {
            logger.info("starting server2");
            server2.start();
        }
        URL serviceUrl = new URLImpl("https", ipAddress, port, serviceId, new HashMap<>());
        if (envTag == null) {
            serviceUrl.removeParameter(Constants.TAG_ENVIRONMENT);
        } else {
            serviceUrl.addParameter(Constants.TAG_ENVIRONMENT, envTag);
        }
        registry.register(serviceUrl);
    }
}
