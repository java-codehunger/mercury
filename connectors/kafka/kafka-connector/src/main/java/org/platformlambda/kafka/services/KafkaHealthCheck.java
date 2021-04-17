/*

    Copyright 2018-2021 Accenture Technology

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

package org.platformlambda.kafka.services;

import org.platformlambda.core.exception.AppException;
import org.platformlambda.core.models.EventEnvelope;
import org.platformlambda.core.models.Inbox;
import org.platformlambda.core.models.Kv;
import org.platformlambda.core.models.LambdaFunction;
import org.platformlambda.core.system.Platform;
import org.platformlambda.core.system.PostOffice;
import org.platformlambda.core.system.PubSub;
import org.platformlambda.core.util.AppConfigReader;
import org.platformlambda.core.util.Utility;
import org.platformlambda.kafka.KafkaSetup;
import org.platformlambda.kafka.reporter.PresenceConnector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KafkaHealthCheck implements LambdaFunction {

    private static final String MANAGER = KafkaSetup.MANAGER;
    private static final String TYPE = "type";
    private static final String HEALTH = "health";
    private static final String INFO = "info";
    private static final String LOOP_BACK = "loopback";
    private static final String REPLY_TO = "reply_to";
    private static final String ORIGIN = "origin";
    private static final String LIST = "list";
    private static final long TIMEOUT = 5000;
    private final boolean presenceMonitor;

    public KafkaHealthCheck() {
        AppConfigReader reader = AppConfigReader.getInstance();
        presenceMonitor = "true".equals(reader.getProperty("service.monitor", "false"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object handleEvent(Map<String, String> headers, Object body, int instance) throws Exception {

        if (INFO.equals(headers.get(TYPE))) {
            Map<String, Object> result = new HashMap<>();
            result.put("service", "kafka");
            result.put("href", KafkaSetup.getDisplayUrl());
            return result;
        }

        if (HEALTH.equals(headers.get(TYPE))) {
            if (presenceMonitor) {
                // Since service monitor does not have a topic, we can just list all topics
                PostOffice po = PostOffice.getInstance();
                EventEnvelope response = po.request(MANAGER, TIMEOUT, new Kv(TYPE, LIST),
                        new Kv(ORIGIN, Platform.getInstance().getOrigin()));
                if (response.getBody() instanceof List) {
                    List<String> list = (List<String>) response.getBody();
                    if (list.isEmpty()) {
                        return "Kafka is healthy but it does not have any topics";
                    } else {
                        return "kafka is healthy and it contains " +
                                list.size() + " " + (list.size() == 1 ? "topic" : "topics");
                    }
                } else {
                    throw new AppException(500, "Unable to list topics");
                }

            } else {
                String topicPartition = PresenceConnector.getInstance().getTopic();
                String topic = getTopic(topicPartition);
                int partition = getPartition(topicPartition);
                long begin = System.currentTimeMillis();
                // wait for reply
                PubSub ps = PubSub.getInstance();
                String me = Platform.getInstance().getOrigin();
                Inbox inbox = new Inbox(1);
                // String topic, int partition, Map<String, String> headers, Object body
                Map<String, String> parameters = new HashMap<>();
                parameters.put(REPLY_TO, inbox.getId()+"@"+me);
                ps.publish(topic, partition, parameters, LOOP_BACK);
                inbox.waitForResponse(TIMEOUT);
                EventEnvelope pong = inbox.getReply();
                inbox.close();
                if (pong == null) {
                    throw new AppException(408, "Loopback test timeout for " + TIMEOUT + " ms");
                }
                if (pong.hasError()) {
                    throw new AppException(pong.getStatus(), pong.getError());
                }
                if (pong.getBody() instanceof Boolean) {
                    Boolean pingOk = (Boolean) pong.getBody();
                    if (pingOk) {
                        long diff = System.currentTimeMillis() - begin;
                        String loopback = "Loopback test took " + diff + " ms";
                        PresenceConnector connector = PresenceConnector.getInstance();
                        boolean ready = connector.isConnected() && connector.isReady();
                        return loopback+"; "+(ready? "connected" : "offline");
                    }
                }
                throw new AppException(500, "Loopback test failed");
            }
        } else {
            throw new IllegalArgumentException("Usage: type=health");
        }

    }

    private String getTopic(String topicPartition) {
        return topicPartition.contains("-") ?
                topicPartition.substring(0, topicPartition.lastIndexOf('-')) : topicPartition;
    }

    private int getPartition(String topicPartition) {
        int hyphen = topicPartition.lastIndexOf('-');
        if (hyphen == -1) {
            return -1;
        } else {
            return Utility.getInstance().str2int(topicPartition.substring(topicPartition.lastIndexOf('-')+1));
        }
    }

}
