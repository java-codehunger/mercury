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

package org.platformlambda.spring.system;

import org.platformlambda.core.system.AppStarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class MainAppLoader {
    private static final Logger log = LoggerFactory.getLogger(MainAppLoader.class);

    @EventListener
    public void handleEvent(Object event) {

        if (event instanceof ApplicationReadyEvent) {
            /*
             * this event will happen in both WAR and JAR deployment mode
             * At this point, Spring Boot is ready
             */
            log.info("Loading user application");
            AppStarter.runMainApp();
        }
        // in case Spring Boot fails, it does not make sense to keep the rest of the application running.
        if (event instanceof ApplicationFailedEvent) {
            log.error("{}", ((ApplicationFailedEvent) event).getException().getMessage());
            System.exit(-1);
        }

    }

}
