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

package org.platformlambda.cloud.services;

import org.platformlambda.core.annotations.ZeroTracing;
import org.platformlambda.core.models.LambdaFunction;
import org.platformlambda.core.models.VersionInfo;
import org.platformlambda.core.system.Platform;
import org.platformlambda.core.system.ServerPersonality;
import org.platformlambda.core.system.ServiceDiscovery;
import org.platformlambda.core.util.AppConfigReader;
import org.platformlambda.core.util.Utility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

@ZeroTracing
public class ServiceQuery implements LambdaFunction {
    private static final Logger log = LoggerFactory.getLogger(ServiceQuery.class);

    private static final String TYPE = ServiceDiscovery.TYPE;
    private static final String ROUTE = ServiceDiscovery.ROUTE;
    private static final String FIND = ServiceDiscovery.FIND;
    private static final String SEARCH = ServiceDiscovery.SEARCH;
    private static final String DOWNLOAD = "download";
    private static final String INFO = "info";
    private final int closedUserGroup;

    public ServiceQuery() {
        Utility util = Utility.getInstance();
        AppConfigReader config = AppConfigReader.getInstance();
        int maxGroups = Math.min(30,
                Math.max(3, util.str2int(config.getProperty("max.closed.user.groups", "30"))));
        closedUserGroup = util.str2int(config.getProperty("closed.user.group", "1"));
        if (closedUserGroup < 1 || closedUserGroup > maxGroups) {
            log.error("closed.user.group is invalid. Please select a number from 1 to "+maxGroups);
            System.exit(-1);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object handleEvent(Map<String, String> headers, Object body, int instance) {
        String type = headers.get(TYPE);
        if (INFO.equals(type)) {
            Platform platform = Platform.getInstance();
            Map<String, Object> result = new HashMap<>();
            result.put("personality", ServerPersonality.getInstance().getType());
            VersionInfo info = Utility.getInstance().getVersionInfo();
            result.put("version", info.getVersion());
            result.put("name", platform.getName());
            result.put("origin", platform.getOrigin());
            result.put("group_id", info.getGroupId());
            result.put("artifact_id", info.getArtifactId());
            return result;

        } else if (DOWNLOAD.equals(type)) {
            Utility util = Utility.getInstance();
            Platform platform = Platform.getInstance();
            String me = platform.getName()+", v"+util.getVersionInfo().getVersion();
            Map<String, Object> result = new HashMap<>();
            result.put("routes", getRouteList());
            result.put("nodes", getOriginList());
            result.put("name", me);
            result.put("origin", platform.getOrigin());
            result.put("group", closedUserGroup);
            return result;

        } else if (FIND.equals(type) && headers.containsKey(ROUTE)) {
            String route = headers.get(ROUTE);
            if (route.equals("*")) {
                if (body instanceof List) {
                    return exists((List<String>) body);
                } else {
                    return false;
                }
            } else {
                return exists(route);
            }

        } else if (SEARCH.equals(headers.get(TYPE)) && headers.containsKey(ROUTE)) {
            return ServiceRegistry.getInstances(headers.get(ROUTE));

        } else {
            throw new IllegalArgumentException("Usage: type=download, info or (type=find, route=route_name)");
        }
    }

    private Map<String, List<String>> getRouteList() {
        Map<String, List<String>> result = new HashMap<>();
        Map<String, Map<String, String>> routes = ServiceRegistry.getAllRoutes();
        for (String r: routes.keySet()) {
            Map<String, String> providers = routes.get(r);
            List<String> list = new ArrayList<>();
            for (String p: providers.keySet()) {
                list.add(providers.get(p).toLowerCase()+", "+p);
            }
            if (list.size() > 1) {
                Collections.sort(list);
            }
            result.put(r, list);
        }
        return result;
    }

    private List<String> getOriginList() {
        List<String> result = new ArrayList<>();
        Map<String, String> origins = ServiceRegistry.getAllOrigins();
        for (String p: origins.keySet()) {
            result.add(origins.get(p)+", "+p);
        }
        if (result.size() > 1) {
            Collections.sort(result);
        }
        return result;
    }

    private boolean exists(List<String> routes) {
        for (String r: routes) {
            if (!exists(r)) {
                return false;
            }
        }
        return true;
    }

    private boolean exists(String route) {
        if (route.contains(".")) {
            // normal route name
            if (Platform.getInstance().hasRoute(route)) {
                return true;
            }
            Map<String, String> targets = ServiceRegistry.getDestinations(route);
            return targets != null && !targets.isEmpty();
        } else {
            // origin-ID
            return ServiceRegistry.destinationExists(route);
        }
    }

}
