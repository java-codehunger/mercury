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

package org.platformlambda.tibco.services;

import org.platformlambda.cloud.ConnectorConfig;
import org.platformlambda.cloud.EventProducer;
import org.platformlambda.cloud.ServiceLifeCycle;
import org.platformlambda.core.models.EventEnvelope;
import org.platformlambda.core.models.LambdaFunction;
import org.platformlambda.core.system.Platform;
import org.platformlambda.core.system.PostOffice;
import org.platformlambda.core.util.Utility;
import org.platformlambda.core.websocket.common.MultipartPayload;
import org.platformlambda.tibco.TibcoConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.*;
import java.io.IOException;
import java.util.*;

public class EventConsumer {
    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private static final String TYPE = ServiceLifeCycle.TYPE;
    private static final String INIT = ServiceLifeCycle.INIT;
    private static final String DONE = "done";
    private static final String TOKEN = ServiceLifeCycle.TOKEN;
    private static final long INITIALIZE = ServiceLifeCycle.INITIALIZE;
    private static final String MONITOR = "monitor";
    private static final String TO_MONITOR = "@"+MONITOR;
    private static final String COMPLETION = "completion.";
    private static final String STOP = "stop";
    private static final String TOPIC = "topic";
    private static final String QUEUE = "queue";
    private final String INIT_TOKEN = UUID.randomUUID().toString();
    private final String realTopic, virtualTopic, topic;
    private final int partition;
    private int skipped = 0;
    private long offset = -1;
    private Session session;
    private MessageConsumer messageConsumer;
    private final String domain;
    private final Properties properties;

    public EventConsumer(String domain, Properties properties, String topic, int partition, String... parameters)
            throws IOException {
        this.domain = domain;
        this.properties = properties;
        boolean substitute = ConnectorConfig.topicSubstitutionEnabled();
        Map<String, String> preAllocatedTopics = ConnectorConfig.getTopicSubstitution();
        this.topic = topic;
        this.partition = partition;
        this.virtualTopic = partition < 0 ? topic : topic + "." + partition;
        this.realTopic = substitute? preAllocatedTopics.getOrDefault(virtualTopic, virtualTopic) : virtualTopic;
        Utility util = Utility.getInstance();
        /*
         * Ignore groupId and clientId as they are specific to Kafka only.
         * Just detect if INITIALIZE is provided.
         */
        if (parameters != null) {
            for (String p: parameters) {
                long offset = util.str2long(p);
                if (offset == INITIALIZE) {
                    this.offset = INITIALIZE;
                    break;
                }
            }
        }
    }

    public void start() throws IOException {
        final boolean requireInitialization = offset == INITIALIZE;
        if (requireInitialization) {
            ServiceLifeCycle initialLoad = new ServiceLifeCycle(topic, partition, INIT_TOKEN);
            initialLoad.start();
        }
        try {
            PostOffice po = PostOffice.getInstance();
            Platform platform = Platform.getInstance();
            Connection connection = TibcoConnector.getConnection(domain, properties);
            session = connection.createSession(Session.AUTO_ACKNOWLEDGE);
            if (partition == -2) {
                messageConsumer = session.createConsumer(session.createQueue(realTopic));
            } else {
                messageConsumer = session.createConsumer(session.createTopic(realTopic));
            }
            messageConsumer.setMessageListener(new EventListener());
            String completionHandler = COMPLETION + virtualTopic.toLowerCase();
            LambdaFunction f = (headers, body, instance) -> {
                try {
                    messageConsumer.close();
                    session.close();
                    log.info("Unsubscribed {} {}", partition == -2? QUEUE : TOPIC, realTopic);
                } catch (JMSException e) {
                    log.error("Unable to close consumer - {}", e.getMessage());
                } finally {
                    platform.release(completionHandler);
                    String INIT_HANDLER = INIT + "." + (partition < 0 ? topic : topic + "." + partition);
                    if (requireInitialization && platform.hasRoute(INIT_HANDLER)) {
                        try {
                            po.send(INIT_HANDLER, DONE);
                        } catch (IOException e) {
                            // ok to ignore
                        }
                    }
                }
                return true;
            };
            platform.registerPrivate(completionHandler, f, 1);
            log.info("Subscribed {} {}", partition == -2? QUEUE : TOPIC, realTopic);

        } catch (Exception e) {
            log.error("Unable to start {} {} - {}", partition == -2? QUEUE : TOPIC, realTopic, e.getMessage());
            throw new IOException(e);
        }
    }

    public void shutdown() {
        String completionHandler = COMPLETION + virtualTopic;
        Platform platform = Platform.getInstance();
        PostOffice po = PostOffice.getInstance();
        if (platform.hasRoute(completionHandler)) {
            try {
                po.send(completionHandler, STOP);
            } catch (IOException e) {
                log.error("Unable to close consumer - {}", e.getMessage());
            }
        }
    }

    private class EventListener implements MessageListener {

        @SuppressWarnings("unchecked")
        @Override
        public void onMessage(Message evt) {
            PostOffice po = PostOffice.getInstance();
            String origin = Platform.getInstance().getOrigin();
            try {
                Enumeration<String> headerNames = evt.getPropertyNames();
                Map<String, String> originalHeaders = new HashMap<>();
                while (headerNames.hasMoreElements()) {
                    String h = headerNames.nextElement();
                    String value = evt.getStringProperty(h);
                    originalHeaders.put(h, value);
                }
                boolean embedEvent = originalHeaders.containsKey(EventProducer.EMBED_EVENT);
                String recipient = originalHeaders.get(EventProducer.RECIPIENT);
                if (recipient != null && recipient.contains(MONITOR)) {
                    recipient = null;
                }
                if (recipient != null && !recipient.equals(origin)) {
                    log.error("Skipping record because it belongs to {}", recipient);
                    return;
                }
                EventEnvelope message = new EventEnvelope();
                if (embedEvent && evt instanceof BytesMessage) {
                    BytesMessage b = (BytesMessage) evt;
                    int len = (int) b.getBodyLength();
                    byte[] data = new byte[len];
                    b.readBytes(data);
                    try {
                        message.load(data);
                        message.setEndOfRoute();
                    } catch (Exception e) {
                        log.error("Unable to decode incoming event for {} - {}", realTopic, e.getMessage());
                        return;
                    }
                    try {
                        String to = message.getTo();
                        if (to != null) {
                            // remove special routing qualifier for presence monitor events
                            if (to.contains(TO_MONITOR)) {
                                message.setTo(to.substring(0, to.indexOf(TO_MONITOR)));
                            }
                            po.send(message);
                        } else {
                            MultipartPayload.getInstance().incoming(message);
                        }
                    } catch (Exception e) {
                        log.error("Unable to process incoming event for {} - {} {}",
                                realTopic, e.getClass().getSimpleName(), e.getMessage());
                    }
                } else {
                    if (offset == INITIALIZE) {
                        if (INIT.equals(originalHeaders.get(TYPE)) &&
                                INIT_TOKEN.equals(originalHeaders.get(TOKEN))) {
                            offset = -1;
                            if (skipped > 0) {
                                log.info("Skipped {} outdated event{}", skipped, skipped == 1 ? "" : "s");
                            }
                        } else {
                            skipped++;
                            return;
                        }
                    }
                    final Object data;
                    if (evt instanceof BytesMessage) {
                        BytesMessage b = (BytesMessage) evt;
                        int len = (int) b.getBodyLength();
                        byte[] bytes = new byte[len];
                        b.readBytes(bytes);
                        data = bytes;
                    } else if (evt instanceof TextMessage) {
                        TextMessage txt = (TextMessage) evt;
                        data = txt.getText();
                    } else {
                        log.error("Event to {} dropped because it is not Text or Binary", realTopic);
                        return;
                    }
                    // transport the headers and payload in original form
                    message.setBody(data).setHeaders(originalHeaders);
                    try {
                        // mercury service name must be lower case
                        po.send(message.setTo(virtualTopic.toLowerCase()));
                    } catch (Exception e) {
                        log.error("Unable to process incoming event for {} - {} {}",
                                realTopic, e.getClass().getSimpleName(), e.getMessage());
                    }
                }

            } catch (JMSException e) {
                log.error("Unable to process incoming event - {}", e.getMessage());
            }
        }
    }

}
