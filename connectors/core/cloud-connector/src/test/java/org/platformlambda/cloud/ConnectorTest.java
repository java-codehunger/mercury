/*

    Copyright 2018-2023 Accenture Technology

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 */

package org.platformlambda.cloud;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.platformlambda.cloud.reporter.PresenceConnector;
import org.platformlambda.core.exception.AppException;
import org.platformlambda.core.models.EventEnvelope;
import org.platformlambda.core.models.Kv;
import org.platformlambda.core.serializers.SimpleMapper;
import org.platformlambda.core.system.Platform;
import org.platformlambda.core.system.PostOffice;
import org.platformlambda.core.system.ServiceDiscovery;
import org.platformlambda.core.util.MultiLevelMap;
import org.platformlambda.mock.TestBase;
import org.platformlambda.util.SimpleHttpRequests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConnectorTest extends TestBase {
    private static final Logger log = LoggerFactory.getLogger(ConnectorTest.class);

    private static final String CLOUD_CONNECTOR_HEALTH = "cloud.connector.health";

    private static final AtomicBoolean firstRun = new AtomicBoolean(true);

    @Before
    public void waitForMockCloud() {
        if (firstRun.get()) {
            firstRun.set(false);
            Platform platform = Platform.getInstance();
            try {
                platform.waitForProvider(CLOUD_CONNECTOR_HEALTH, 20);
                log.info("Mock cloud ready");
                waitForConnector();
            } catch (TimeoutException e) {
                log.error("{} not ready - {}", CLOUD_CONNECTOR_HEALTH, e.getMessage());
            }
        }
    }

    private void waitForConnector() {
        boolean ready = false;
        PresenceConnector connector = PresenceConnector.getInstance();
        for (int i=0; i < 20; i++) {
            if (connector.isConnected() && connector.isReady()) {
                ready = true;
                break;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        if (ready) {
            log.info("Cloud connection ready");
        } else {
            log.error("Cloud connection not ready");
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void connectivityTest() throws TimeoutException, AppException, IOException {
        String origin = "unit-test";
        Platform platform = Platform.getInstance();
        platform.waitForProvider("cloud.connector.health", 10);
        PostOffice po = PostOffice.getInstance();
        String URL = "https://127.0.0.1";
        String SERVICE_NAME = "CloudConnector";
        ConnectorConfig.setDisplayUrl(URL);
        ConnectorConfig.setServiceName(SERVICE_NAME);
        String url = ConnectorConfig.getDisplayUrl();
        String name = ConnectorConfig.getServiceName();
        Map<String, String> topicSubstitution = ConnectorConfig.getTopicSubstitution();
        Assert.assertEquals(URL, url);
        Assert.assertEquals(SERVICE_NAME, name);
        Assert.assertEquals("user.topic.one", topicSubstitution.get("multiplex.0001.0"));
        po.request(ServiceDiscovery.SERVICE_REGISTRY, 5000,
                new Kv("type", "join"), new Kv("origin", origin), new Kv("topic", "multiplex.0001-001"));
        po.request(ServiceDiscovery.SERVICE_REGISTRY, 5000,
                new Kv("type", "join"), new Kv("origin", platform.getOrigin()), new Kv("topic", "multiplex.0001-000"));
        po.request(ServiceDiscovery.SERVICE_REGISTRY, 5000,
                new Kv("type", "add"), new Kv("origin", origin),
                new Kv("route", "hello.world"), new Kv("personality", "WEB"));
        Map<String, Object> routes = new HashMap<>();
        routes.put("hello.test", "WEB");
        routes.put("hello.demo", "WEB");
        routes.put("to.be.removed", "WEB");
        po.request(ServiceDiscovery.SERVICE_REGISTRY, 5000, routes,
                new Kv("type", "add"), new Kv("origin", origin),
                new Kv("personality", "WEB"));
        po.broadcast("hello.world", "something");
        po.send("hello.demo@"+origin, "something else");
        po.request(ServiceDiscovery.SERVICE_REGISTRY, 5000, routes,
                new Kv("type", "unregister"), new Kv("origin", origin),
                new Kv("route", "to.be.removed"));
        EventEnvelope queryResult = po.request(ServiceDiscovery.SERVICE_QUERY, 5000,
                new Kv("type", "search"), new Kv("route", "hello.world"));
        Assert.assertTrue(queryResult.getBody() instanceof List);
        List<String> instances = (List<String>) queryResult.getBody();
        Assert.assertTrue(instances.contains("unit-test"));
        Assert.assertTrue(instances.contains(platform.getOrigin()));
        Object response = SimpleHttpRequests.get("http://127.0.0.1:"+port+"/info/routes");
        Assert.assertTrue(response instanceof String);
        Map<String, Object> info = SimpleMapper.getInstance().getMapper().readValue(response, Map.class);
        MultiLevelMap multi = new MultiLevelMap(info);
        Object nodes = multi.getElement("routing.nodes");
        Assert.assertTrue(nodes instanceof List);
        String nodeList = nodes.toString();
        Assert.assertTrue(nodeList.contains("unit-test"));
        Assert.assertTrue(po.exists("hello.demo"));
        queryResult = po.request(ServiceDiscovery.SERVICE_QUERY, 5000,
                Collections.singletonList("hello.world"), new Kv("type", "find"), new Kv("route", "*"));
        Assert.assertEquals(true, queryResult.getBody());
        Map<String, String> headers = new HashMap<>();
        headers.put("X-App-Instance", Platform.getInstance().getOrigin());
        response = SimpleHttpRequests.post("http://127.0.0.1:"+port+"/suspend/now", headers, new HashMap<>());
        Assert.assertTrue(response instanceof String);
        Map<String, Object> result = SimpleMapper.getInstance().getMapper().readValue(response, Map.class);
        Assert.assertEquals(200, result.get("status"));
        Assert.assertEquals("suspend", result.get("type"));
        Assert.assertEquals("/suspend/now", result.get("path"));
        response = SimpleHttpRequests.post("http://127.0.0.1:"+port+"/resume/now", headers, new HashMap<>());
        Assert.assertTrue(response instanceof String);
        result = SimpleMapper.getInstance().getMapper().readValue(response, Map.class);
        Assert.assertEquals(200, result.get("status"));
        Assert.assertEquals("resume", result.get("type"));
        Assert.assertEquals("/resume/now", result.get("path"));
        po.send(ServiceDiscovery.SERVICE_REGISTRY, new Kv("type", "leave"), new Kv("origin", origin));
    }

    @Test
    public void checkTopicNameWithoutDot() throws IOException {
        String name = "hello.world";
        ConnectorConfig.validateTopicName(name);
        String invalid = "helloworld";
        IOException ex = Assert.assertThrows(IOException.class, () -> {
            ConnectorConfig.validateTopicName(invalid);
        });
        Assert.assertEquals("Invalid route helloworld because it is missing dot separator(s). e.g. hello.world",
                ex.getMessage());
    }

    @Test
    public void checkEmptyTopic() {
        IOException ex = Assert.assertThrows(IOException.class, () -> {
            ConnectorConfig.validateTopicName("");
        });
        Assert.assertEquals("Invalid route name - use 0-9, a-z, A-Z, period, hyphen or underscore characters",
                ex.getMessage());
    }

    @Test
    public void reservedExtension() {
        IOException ex = Assert.assertThrows(IOException.class, () -> {
            ConnectorConfig.validateTopicName("hello.com");
        });
        Assert.assertEquals("Invalid route hello.com which is a reserved extension",
                ex.getMessage());
    }

    @Test
    public void reservedName() {
        IOException ex = Assert.assertThrows(IOException.class, () -> {
            ConnectorConfig.validateTopicName("Thumbs.db");
        });
        Assert.assertEquals("Invalid route Thumbs.db which is a reserved Windows filename",
                ex.getMessage());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void healthTest() throws AppException, IOException {
        Object response = SimpleHttpRequests.get("http://127.0.0.1:"+port+"/health");
        log.info("{}", response);
        Assert.assertTrue(response instanceof String);
        Map<String, Object> result = SimpleMapper.getInstance().getMapper().readValue(response, Map.class);
        Assert.assertEquals("UP", result.get("status"));
        Assert.assertEquals("cloud-connector", result.get("name"));
        MultiLevelMap multi = new MultiLevelMap(result);
        Assert.assertEquals(200, multi.getElement("upstream[0].status_code"));
    }
}
