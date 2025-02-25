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

package org.platformlambda.activemq;

import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.platformlambda.activemq.services.PubSubManager;
import org.platformlambda.cloud.ConnectorConfig;
import org.platformlambda.cloud.EventProducer;
import org.platformlambda.cloud.reporter.PresenceConnector;
import org.platformlambda.cloud.services.CloudHealthCheck;
import org.platformlambda.cloud.services.ServiceQuery;
import org.platformlambda.cloud.services.ServiceRegistry;
import org.platformlambda.core.annotations.CloudConnector;
import org.platformlambda.core.models.CloudSetup;
import org.platformlambda.core.system.Platform;
import org.platformlambda.core.system.PostOffice;
import org.platformlambda.core.system.PubSub;
import org.platformlambda.core.system.ServiceDiscovery;
import org.platformlambda.core.util.AppConfigReader;
import org.platformlambda.core.util.ConfigReader;
import org.platformlambda.core.util.Utility;
import org.platformlambda.core.websocket.client.PersistentWsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@CloudConnector(name="activemq")
public class ArtemisConnector implements CloudSetup {
    private static final Logger log = LoggerFactory.getLogger(ArtemisConnector.class);

    private static final String SYSTEM = "system";
    private static final String CLOUD_CLIENT_PROPERTIES = "cloud.client.properties";
    public static final String BROKER_URL = "bootstrap.servers";
    private static final String CLOUD_CONNECTOR_HEALTH = "cloud.connector.health";
    private static final String USER_ID = "user.id";
    private static final String USER_PWD = "user.password";

    private static final ConcurrentMap<String, Connection> allConnections = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Properties> allProperties = new ConcurrentHashMap<>();

    public static Properties getClusterProperties(String location) {
        // default location is cloud.client.properties
        Properties properties = allProperties.get(location);
        if (properties == null) {
            ConfigReader clusterConfig = null;
            try {
                clusterConfig = ConnectorConfig.getConfig(location,
                        "file:/tmp/config/activemq.properties,classpath:/activemq.properties");
            } catch (IOException e) {
                log.error("Unable to find activemq properties - {}", e.getMessage());
                System.exit(-1);
            }
            properties = new Properties();
            for (String k : clusterConfig.getMap().keySet()) {
                properties.setProperty(k, clusterConfig.getProperty(k));
            }
            String url = properties.getProperty(BROKER_URL);
            Utility util = Utility.getInstance();
            List<String> cluster = util.split(url, ", ");
            boolean reachable = false;
            for (String address : cluster) {
                int start = address.lastIndexOf('/');
                int colon = address.lastIndexOf(':');
                if (colon > 1 && colon > start) {
                    String host = address.substring(start+1, colon);
                    int port = util.str2int(address.substring(colon + 1));
                    if (port > 0) {
                        // ping the address to confirm it is reachable before making a client connection
                        if (util.portReady(host, port, 10000)) {
                            reachable = true;
                        }
                    }
                }
            }
            if (!reachable) {
                log.error("ActiveMQ cluster {} is not reachable", cluster);
                System.exit(-1);
            }
            allProperties.put(location, properties);
        }
        return properties;
    }

    public static synchronized Connection getConnection(String domain, Properties properties) throws JMSException {
        Connection connection = allConnections.get(domain);
        if (connection == null) {
            String cluster = properties.getProperty(BROKER_URL, "tcp://127.0.0.1:61616");
            String userId = properties.getProperty(USER_ID, "");
            String password = properties.getProperty(USER_PWD, "");
            ConnectionFactory factory = new ActiveMQConnectionFactory(cluster);
            connection = factory.createConnection(userId, password);
            connection.setExceptionListener((e) -> {
                String error = e.getMessage();
                log.error("Activemq cluster exception - {}", error);
                if (error != null && (error.contains("terminated") || error.contains("disconnect"))) {
                    ArtemisConnector.stopConnection(domain);
                    System.exit(10);
                }
            });
            connection.start();
            log.info("Connection started - {}", cluster);
            if (SYSTEM.equals(domain)) {
                ConnectorConfig.setServiceName("activemq-artemis");
                ConnectorConfig.setDisplayUrl(cluster);
            }
            allConnections.put(domain, connection);
        }
        return connection;
    }

    public static synchronized void stopConnection(String domain) {
        Connection connection = allConnections.get(domain);
        if (connection != null) {
            try {
                allConnections.remove(domain);
                connection.stop();
                log.info("Connection stopped");
            } catch (JMSException e) {
                // ok to ignore
            }
        }
    }

    @Override
    public void initialize() {
        try {
            Platform platform = Platform.getInstance();
            PubSub ps = PubSub.getInstance(SYSTEM);
            Properties properties = getClusterProperties(CLOUD_CLIENT_PROPERTIES);
            ps.enableFeature(new PubSubManager(SYSTEM, properties, ServiceRegistry.CLOUD_MANAGER));
            AppConfigReader config = AppConfigReader.getInstance();
            if (!"true".equals(config.getProperty("service.monitor", "false"))) {
                // start presence connector
                ConfigReader monitorConfig = ConnectorConfig.getConfig("presence.properties",
                        "file:/tmp/config/presence.properties,classpath:/presence.properties");
                List<String> monitors = Utility.getInstance().split(monitorConfig.getProperty("url"), ", ");
                PersistentWsClient ws = new PersistentWsClient(PresenceConnector.getInstance(), monitors);
                ws.start();
            }
            platform.registerPrivate(PostOffice.CLOUD_CONNECTOR, new EventProducer(), 1);
            // enable service discovery
            platform.registerPrivate(ServiceDiscovery.SERVICE_REGISTRY, new ServiceRegistry(), 1);
            platform.registerPrivate(ServiceDiscovery.SERVICE_QUERY, new ServiceQuery(), 10);
            platform.registerPrivate(CLOUD_CONNECTOR_HEALTH, new CloudHealthCheck(), 2);
            platform.startCloudServices();
        } catch (IOException | JMSException e) {
            log.error("Unable to setup ActiveMQ connection - {}", e.getMessage());
            System.exit(-1);
        }
    }

}
