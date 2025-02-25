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

package org.platformlambda.automation.config;

import org.platformlambda.automation.models.AssignedRoute;
import org.platformlambda.automation.models.CorsInfo;
import org.platformlambda.automation.models.HeaderInfo;
import org.platformlambda.automation.models.RouteInfo;
import org.platformlambda.core.system.AppStarter;
import org.platformlambda.core.util.ConfigReader;
import org.platformlambda.core.util.Utility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

public class RoutingEntry {
    private static final Logger log = LoggerFactory.getLogger(RoutingEntry.class);

    private static final String ASYNC_HTTP_REQUEST = AppStarter.ASYNC_HTTP_REQUEST;
    private static final String HTTP = "http://";
    private static final String HTTPS = "https://";
    private static final String REST = "rest";
    private static final String CORS = "cors";
    private static final String AUTH = "authentication";
    private static final String UPLOAD = "upload";
    private static final String THRESHOLD = "threshold";
    private static final String TRACING = "tracing";
    private static final String SERVICE = "service";
    private static final String FLOW = "flow";
    private static final String METHODS = "methods";
    private static final String URL_LABEL = "url";
    private static final String ID = "id";
    private static final String OPTIONS_METHOD = "OPTIONS";
    private static final String ORIGIN = "origin";
    private static final String OPTIONS = "options";
    private static final String HEADERS = "headers";
    private static final String DEFAULT_VALUE = "default";
    private static final String TRUST_ALL_CERT = "trust_all_cert";
    private static final String URL_REWRITE = "url_rewrite";
    private static final String TIMEOUT = "timeout";
    private static final String REQUEST = "request";
    private static final String RESPONSE = "response";
    private static final String ADD = "add";
    private static final String DROP = "drop";
    private static final String KEEP = "keep";
    private static final String SKIP_INVALID_AUTH = "Skipping entry with invalid authentication service name {}";
    private static final String ACCESS_CONTROL_PREFIX = "Access-Control-";
    private static final String[] VALID_METHODS = {"GET", "PUT", "POST", "DELETE", "HEAD", "PATCH", "OPTIONS"};
    private static final List<String> METHOD_LIST = Arrays.asList(VALID_METHODS);
    private static final int MIN_THRESHOLD = 5000;
    private static final int REG_THRESHOLD = 50000;
    private static final int MAX_THRESHOLD = 500000;
    private static final int ONE_MINUTE = 60;
    private static final int FIVE_MINUTES = 5 * ONE_MINUTE;
    private static final int ONE_HOUR = 60 * ONE_MINUTE;
    private static final int ONE_DAY = 24 * ONE_HOUR;
    private static final Map<String, RouteInfo> routes = new HashMap<>();
    private static final Map<String, Boolean> exactRoutes = new HashMap<>();
    // id -> {maps for options and headers}
    private static final Map<String, CorsInfo> cors = new HashMap<>();
    // id -> add, drop, keep
    private static final Map<String, HeaderInfo> requestHeaderInfo = new HashMap<>();
    private static final Map<String, HeaderInfo> responseHeaderInfo = new HashMap<>();
    private static final List<String> urlPaths = new ArrayList<>();
    private static final RoutingEntry instance = new RoutingEntry();

    private RoutingEntry() {
        // singleton
    }

    public static RoutingEntry getInstance() {
        return instance;
    }

    public AssignedRoute getRouteInfo(String method, String url) {
        Utility util = Utility.getInstance();
        StringBuilder sb = new StringBuilder();
        List<String> input = util.split(url, "/");
        for (String p: input) {
            sb.append('/');
            sb.append(p.trim());
        }
        // do case-insensitive matching for exact URL
        String normalizedUrl = sb.toString().toLowerCase();
        String key = method+":"+normalizedUrl;
        if (exactRoutes.containsKey(normalizedUrl)) {
            return new AssignedRoute(routes.get(key));
        } else {
            // then compare each segment in the URL, also with case insensitivity
            AssignedRoute similar = null;
            for (String u: urlPaths) {
                AssignedRoute info = getMatchedRoute(input, method, u);
                if (info != null) {
                    if (similar == null) {
                        similar = info;
                    }
                    // both URL path and method are correct
                    if (routes.containsKey(method + ":" + u)) {
                        return info;
                    }
                }
            }
            /*
             * Similar path found but method does not match.
             * This allows it to reject the request with "HTTP-405 Method Not Allowed".
             */
            return similar;
        }
    }

    public HeaderInfo getRequestHeaderInfo(String id) {
        return requestHeaderInfo.get(id);
    }

    public HeaderInfo getResponseHeaderInfo(String id) {
        return responseHeaderInfo.get(id);
    }

    public CorsInfo getCorsInfo(String id) {
        return cors.get(id);
    }

    private AssignedRoute getMatchedRoute(List<String> input, String method, String configured) {
        // "configured" is a lower case URL in the routing entry
        String key = method+":"+configured;
        AssignedRoute result = new AssignedRoute(routes.get(key));
        Utility util = Utility.getInstance();
        List<String> segments = util.split(configured, "/");
        if (matchRoute(input, segments, configured.endsWith("*"))) {
            addArguments(result, input, segments);
            return result;
        }
        return null;
    }

    private void addArguments(AssignedRoute info, List<String> input, List<String> configured) {
        for (int i=0; i < configured.size(); i++) {
            String configuredItem = configured.get(i);
            if (configuredItem.startsWith("{") && configuredItem.endsWith("}")) {
                info.setArgument(configuredItem.substring(1, configuredItem.length()-1), input.get(i));
            }
        }
    }

    private boolean matchRoute(List<String> input, List<String> segments, boolean wildcard) {
        // segment is lowercase parts of the configured URL
        if (wildcard) {
            if (segments.size() > input.size()) {
                return false;
            }
        } else {
            if (segments.size() != input.size()) {
                return false;
            }
        }
        for (int i=0; i < segments.size(); i++) {
            String configuredItem = segments.get(i);
            if (configuredItem.startsWith("{") && configuredItem.endsWith("}")) {
                continue;
            }
            if (configuredItem.equals("*")) {
                continue;
            }
            // case-insensitive comparison using lowercase
            String inputItem = input.get(i).toLowerCase();
            if (configuredItem.endsWith("*")) {
                String prefix = configuredItem.substring(0, configuredItem.length()-1);
                if (inputItem.startsWith(prefix)) {
                    continue;
                }
            }
            if (inputItem.equals(configuredItem)) {
                continue;
            }
            return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public void load(ConfigReader config) {
        if (config.exists(HEADERS)) {
            Object headerList = config.get(HEADERS);
            if (headerList instanceof List) {
                if (isMap((List<Object>) headerList)) {
                    loadHeaderTransform((List<Map<String, Object>>) headerList);
                } else {
                    log.error("'headers' section must be a list of configuration with request and response entries");
                }

            } else {
                log.error("'headers' section must contain a list of configuration. Actual: {}", headerList.getClass().getSimpleName());
            }
        }
        if (config.exists(CORS)) {
            Object corsList = config.get(CORS);
            if (corsList instanceof List) {
                if (isMap((List<Object>) corsList)) {
                    loadCors(config);
                } else {
                    log.error("'cors' section must be a list of Access-Control configuration where each entry is a map of id, options and headers");
                }

            } else {
                log.error("'cors' section must contain a list of Access-Control configuration. Actual: {}", corsList.getClass().getSimpleName());
            }
        }
        if (config.exists(REST)) {
            Object rest = config.get(REST);
            if (rest instanceof List) {
                if (isMap((List<Object>) rest)) {
                    loadRest((List<Map<String, Object>>) rest);
                } else {
                    log.error("'rest' section must be a list of configuration where each endpoint is a map of url, service, methods, timeout, etc.");
                }

            } else {
                log.error("'rest' section must contain a list of configuration. Actual: {}", rest.getClass().getSimpleName());
            }
            List<String> exact = new ArrayList<>();
            for (String r: exactRoutes.keySet()) {
                if (!exact.contains(r)) {
                    exact.add(r);
                }
            }
            if (exact.size() > 1) {
                Collections.sort(exact);
            }
            if (!exact.isEmpty()) {
                log.info("Number of exact URLs = {}", exact.size());
            }
            // sort URLs for easy parsing
            if (!routes.isEmpty()) {
                for (String r: routes.keySet()) {
                    int colon = r.indexOf(':');
                    if (colon > 0) {
                        String urlOnly = r.substring(colon+1);
                        if (!exactRoutes.containsKey(urlOnly) && !urlPaths.contains(urlOnly)) {
                            urlPaths.add(urlOnly);
                        }
                    }
                }
            }
            if (urlPaths.size() > 1) {
                Collections.sort(urlPaths);
            }
            if (!urlPaths.isEmpty()) {
                log.info("Number of wildcard URLs = {}", urlPaths.size());
            }
        }
    }

    private boolean isMap(List<Object> list) {
        for (Object o: list) {
            if (!(o instanceof Map)) {
                return false;
            }
        }
        return true;
    }

    private void loadRest(List<Map<String, Object>> config) {
        for (Map<String, Object> entry: config) {
            if (entry.containsKey(SERVICE) && entry.containsKey(METHODS) && entry.containsKey(URL_LABEL)
                    && entry.get(URL_LABEL) instanceof String) {
                String url = (String) entry.get(URL_LABEL);
                if (url.contains("{") || url.contains("}") || url.contains("*")) {
                    loadRestEntry(entry, false);
                } else {
                    loadRestEntry(entry, true);
                }
            } else {
                log.error("Skipping invalid REST entry {}", entry);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> validateServiceList(Object svcList) {
        Utility util = Utility.getInstance();
        List<String> list = svcList instanceof String?
                Collections.singletonList((String) svcList) : (List<String>) svcList;
        List<String> result = new ArrayList<>();
        for (String item: list) {
            String service = item.trim().toLowerCase();
            if (!service.isEmpty() && !result.contains(service)) {
                result.add(service);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Missing service");
        }
        String firstItem = result.get(0);
        if (firstItem.startsWith(HTTP) || firstItem.startsWith(HTTPS)) {
            if (result.size() > 1) {
                throw new IllegalArgumentException("HTTP relay supports a single URL only");
            }
            return result;
        }
        for (String item: result) {
            if (item.startsWith(HTTP) || item.startsWith(HTTPS)) {
                throw new IllegalArgumentException("Cannot mix HTTP and service target");
            }
            if (!util.validServiceName(item) || !item.contains(".")) {
                throw new IllegalArgumentException("Invalid service name");
            }
        }
        return result;
    }


    @SuppressWarnings("unchecked")
    private void loadRestEntry(Map<String, Object> entry, boolean exact) {
        Utility util = Utility.getInstance();
        RouteInfo info = new RouteInfo();
        if ((entry.get(SERVICE) instanceof String || entry.get(SERVICE) instanceof List) &&
                entry.get(METHODS) instanceof List) {
            if (entry.containsKey(FLOW)) {
                info.flowId = entry.get(FLOW).toString();
            }
            try {
                info.services = validateServiceList(entry.get(SERVICE));
            } catch (IllegalArgumentException e) {
                log.error("Skipping entry {} - {}", entry, e.getMessage());
                return;
            }
            info.primary = info.services.get(0);
            // default multipart label for upload is "file"
            if (entry.containsKey(UPLOAD)) {
                String upload = entry.get(UPLOAD).toString();
                info.upload = "true".equals(upload) || "file".equals(upload);
            }
            if (entry.containsKey(AUTH)) {
                Object list = entry.get(AUTH);
                if (list instanceof String) {
                    String auth = list.toString().trim();
                    if (util.validServiceName(auth)) {
                        info.defaultAuthService = auth;
                    } else {
                        log.error(SKIP_INVALID_AUTH, entry);
                        return;
                    }
                }
                if (list instanceof List) {
                    List<Object> authList = (List<Object>) list;
                    for (Object o: authList) {
                        String authEntry = o.toString();
                        List<String> parts = util.split(authEntry, ": ");
                        if (parts.size() == 2) {
                            String authHeader = parts.get(0);
                            String authService = parts.get(1);
                            if (util.validServiceName(authService)) {
                                if (DEFAULT_VALUE.equals(authHeader)) {
                                    info.defaultAuthService = authService;
                                } else {
                                    info.setAuthService(authHeader, "*", authService);
                                }
                            } else {
                                log.error(SKIP_INVALID_AUTH, entry);
                                return;
                            }

                        } else if (parts.size() == 3) {
                            String authHeader = parts.get(0);
                            String authValue = parts.get(1);
                            String authService = parts.get(2);
                            if (util.validServiceName(authService)) {
                                info.setAuthService(authHeader, authValue, authService);
                            } else {
                                log.error(SKIP_INVALID_AUTH, entry);
                                return;
                            }

                        } else {
                            log.error(SKIP_INVALID_AUTH, entry);
                            return;
                        }
                    }
                    if (info.defaultAuthService == null) {
                        log.error("Skipping entry because it is missing default authentication service {}", entry);
                        return;
                    }
                }
            }
            if (entry.containsKey(THRESHOLD)) {
                int value = util.str2int(entry.get(THRESHOLD).toString());
                if (value < MIN_THRESHOLD) {
                    value = MIN_THRESHOLD;
                    log.warn("{} - threshold set to {} because {} is too small", entry.get(URL_LABEL), REG_THRESHOLD, value);
                }
                if (value > MAX_THRESHOLD) {
                    value = MAX_THRESHOLD;
                    log.warn("{} - threshold set to {} because {} is too big", entry.get(URL_LABEL), MAX_THRESHOLD, value);
                }
                info.threshold = value;
            }
            if (entry.containsKey(TRACING)) {
                if ("true".equalsIgnoreCase(entry.get(TRACING).toString())) {
                    info.tracing = true;
                }
            }
            List<String> methods = (List<String>) entry.get(METHODS);
            // save case insensitive version of the URL
            String url = entry.get(URL_LABEL).toString().toLowerCase();
            // drop query string when parsing URL
            if (url.contains("?")) {
                url = url.substring(0, url.indexOf('?'));
            }
            info.timeoutSeconds = entry.containsKey(TIMEOUT)? getDurationInSeconds(entry.get(TIMEOUT).toString()) : 30;
            if (entry.containsKey(CORS)) {
                String id = entry.get(CORS).toString();
                if (cors.containsKey(id)) {
                    info.corsId = id;
                } else {
                    log.error("Skipping invalid entry because cors ID {} is not found, {}", id, entry);
                    return;
                }
            }
            if (entry.containsKey(HEADERS)) {
                String id = entry.get(HEADERS).toString();
                boolean foundTransform = false;
                if (requestHeaderInfo.containsKey(id)) {
                    info.requestTransformId = id;
                    foundTransform = true;
                }
                if (responseHeaderInfo.containsKey(id)) {
                    info.responseTransformId = id;
                    foundTransform = true;
                }
                if (!foundTransform) {
                    log.error("Skipping invalid entry because headers ID {} is not found, {}", id, entry);
                    return;
                }
            }
            if (info.primary.startsWith(HTTP) || info.primary.startsWith(HTTPS)) {
                // URL rewrite
                if (entry.containsKey(URL_REWRITE)) {
                    if (entry.get(URL_REWRITE) instanceof List) {
                        List<String> urlRewrite = (List<String>) entry.get(URL_REWRITE);
                        if (urlRewrite.size() == 2) {
                            info.urlRewrite = urlRewrite;
                        } else {
                            log.error("Skipping entry with invalid {} - {}. It should contain a list of 2 prefixes",
                                    URL_REWRITE, urlRewrite);
                            return;
                        }
                    } else {
                        log.error("Skipping entry with invalid {} - {}, expected: List<String>, actual: {}",
                                URL_REWRITE, entry.get(URL_REWRITE), entry.get(URL_REWRITE).getClass().getSimpleName());
                        return;
                    }
                }
                try {
                    URL u = new URL(info.primary);
                    if (!u.getPath().isEmpty()) {
                        log.error("Skipping entry with invalid service URL {} - Must not contain path", info.primary);
                        return;
                    }
                    if (u.getQuery() != null) {
                        log.error("Skipping entry with invalid service URL {} - Must not contain query", info.primary);
                        return;
                    }
                    if (info.primary.startsWith(HTTPS) && entry.containsKey(TRUST_ALL_CERT) &&
                                "true".equalsIgnoreCase(entry.get(TRUST_ALL_CERT).toString())) {
                        info.trustAllCert = true;
                        log.warn("Be careful - {}=true for {}", TRUST_ALL_CERT, info.primary);
                    }
                    if (info.primary.startsWith(HTTP) && entry.containsKey(TRUST_ALL_CERT)) {
                        log.warn("{}=true for {} is not relevant - Do you meant https?", TRUST_ALL_CERT, info.primary);
                    }
                    // set primary to ASYNC_HTTP_REQUEST
                    info.host = info.primary;
                    info.primary = ASYNC_HTTP_REQUEST;

                } catch (MalformedURLException e) {
                    log.error("Skipping entry with invalid service URL {} - {}", info.primary, e.getMessage());
                    return;
                }

            } else {
                if (entry.containsKey(TRUST_ALL_CERT)) {
                    log.warn("{}=true for {} is not relevant", TRUST_ALL_CERT, info.primary);
                }
            }
            if (validMethods(methods)) {
                info.methods = methods;
                if (exact) {
                    exactRoutes.put(url, true);
                }
                String nUrl = getUrl(url, exact);
                if (nUrl == null) {
                    log.error("Skipping invalid entry {}", entry);
                } else {
                    info.url = nUrl;
                    List<String> allMethods = new ArrayList<>(methods);
                    if (!allMethods.contains(OPTIONS_METHOD)) {
                        allMethods.add(OPTIONS_METHOD);
                    }
                    for (String m: allMethods) {
                        String key = m+":"+nUrl;
                        if (routes.containsKey(key)) {
                            if (!m.equals(OPTIONS_METHOD)) {
                                log.error("Skipping duplicated method and URL {}", key);
                            }
                        } else {
                            routes.put(key, info);
                            // OPTIONS method is not traced
                            if (m.equals(OPTIONS_METHOD)) {
                                log.info("{} {} -> {}, timeout={}s", m, nUrl, info.services, info.timeoutSeconds);
                            } else if (info.defaultAuthService != null) {
                                log.info("{} {} -> {} -> {}, timeout={}s, tracing={}",
                                        m, nUrl, info.defaultAuthService, info.services, info.timeoutSeconds, info.tracing);
                            } else {
                                log.info("{} {} -> {}, timeout={}s, tracing={}",
                                        m, nUrl, info.services, info.timeoutSeconds, info.tracing);
                            }
                        }
                    }
                }
            } else {
                log.error("Skipping entry with invalid method {}", entry);
            }

        } else {
            log.error("Skipping invalid REST entry {}", entry);
        }
    }

    private boolean validMethods(List<String> methods) {
        if (methods.isEmpty()) {
            return false;
        }
        for (String m: methods) {
            if (!METHOD_LIST.contains(m)) {
                return false;
            }
        }
        return true;
    }

    private String getUrl(String url, boolean exact) {
        StringBuilder sb = new StringBuilder();
        List<String> parts = Utility.getInstance().split(url.toLowerCase(), "/");
        for (String p: parts) {
            String s = p.trim();
            sb.append('/');
            if (exact) {
                sb.append(s);
            } else {
                if (s.contains("{") || s.contains("}")) {
                    if (s.contains("*")) {
                        log.error("wildcard url segment must not mix arguments with *, actual: {}", s);
                        return null;
                    }
                    if (!validArgument(s)) {
                        log.error("Argument url segment must be enclosed curly brackets, actual: {}", s);
                        return null;
                    }
                }
                if (s.contains("*")) {
                    if (!validWildcard(s)) {
                        log.error("wildcard url segment must ends with *, actual: {}", url);
                        return null;
                    }
                }
                sb.append(s);
            }
        }
        return sb.toString();
    }

    private boolean validArgument(String arg) {
        if (arg.startsWith("{") && arg.endsWith("}")) {
            String v = arg.substring(1, arg.length()-1);
            if (v.length() == 0) {
                return false;
            } else return !v.contains("{") && !v.contains("}");
        } else {
            return false;
        }
    }

    private boolean validWildcard(String wildcard) {
        if (wildcard.equals("*")) {
            return true;
        }
        if (!wildcard.endsWith("*")) {
            return false;
        }
        List<String> stars = Utility.getInstance().split(wildcard, "*");
        return stars.size() == 1;
    }

    private void loadCors(ConfigReader config) {
        for (int idx = 0; config.exists(CORS+"["+idx+"]"); idx++) {
            String id = config.getProperty(CORS+"["+idx+"]."+ID);
            String origin = config.getProperty(CORS+"["+idx+"]."+ORIGIN);
            Object options = config.get(CORS+"["+idx+"]."+OPTIONS);
            Object headers = config.get(CORS+"["+idx+"]."+HEADERS);
            if (id != null && options instanceof List && headers instanceof List) {
                loadCorsEntry(id, origin, options, headers);
            } else {
                log.error("Skipping invalid CORS definition {}", config.get(CORS+"["+idx+"]"));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void loadCorsEntry(String id, String origin, Object options, Object headers) {
        if (options instanceof List && headers instanceof List) {
            List<Object> optionList = (List<Object>) options;
            List<Object> headerList = (List<Object>) headers;
            if (validCorsList(optionList) && validCorsList(headerList)) {
                CorsInfo info = new CorsInfo(origin);
                for (Object o : optionList) {
                    info.addOption(o.toString());
                }
                for (Object h : headerList) {
                    info.addHeader(h.toString());
                }
                cors.put(id, info);
                log.info("Loaded {} CORS headers ({})", id, info.getOrigin(false));
            } else {
                log.error("Skipping invalid CORS entry id={}, origin={}, options={}, headers={}",
                        id, origin, options, headers);
            }
        }
    }

    private boolean validCorsList(List<Object> list) {
        for (Object o: list) {
            if (o instanceof String) {
                if (!validCorsElement((String) o)) {
                    return false;
                }
            } else {
                log.error("Cors header must be a list of strings, actual: {}", list);
                return false;
            }
        }
        return true;
    }

    private boolean validCorsElement(String element) {
        if (!element.startsWith(ACCESS_CONTROL_PREFIX)) {
            log.error("Cors header must start with {}, actual: {}", ACCESS_CONTROL_PREFIX, element);
            return false;
        }
        int colon = element.indexOf(':');
        if (colon == -1) {
            log.error("Cors header must contain key-value separated by a colon, actual: {}", element);
            return false;
        }
        String value = element.substring(colon+1).trim();
        if (value.length() == 0) {
            log.error("Missing value in Cors header {}, actual: {}", element.substring(0, colon), element);
            return false;
        }
        return true;
    }

    private void loadHeaderTransform(List<Map<String, Object>> config) {
        for (Map<String, Object> entry: config) {
            if (entry.containsKey(ID)) {
                loadHeaderEntry(entry, true);
                loadHeaderEntry(entry, false);
            } else {
                log.error("Skipping invalid HEADERS definition - it must contain id with request/response entries {}", entry);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void loadHeaderEntry(Map<String, Object> entry, boolean isRequest) {
        String id = (String) entry.get(ID);
        Object go = isRequest? entry.get(REQUEST) : entry.get(RESPONSE);
        if (go instanceof Map) {
            Map<String, Object> group = (Map<String, Object>) go;
            HeaderInfo info = new HeaderInfo();
            if (group.get(ADD) instanceof List) {
                List<Object> items = (List<Object>) group.get(ADD);
                for (Object o : items) {
                    String keyValue = o.toString();
                    int colon = keyValue.indexOf(':');
                    if (colon > 0) {
                        info.addHeader(keyValue.substring(0, colon).trim().toLowerCase(), keyValue.substring(colon + 1).trim());
                    } else {
                        log.warn("Skipping invalid ADD entry {} for HEADERS definition {}", o, id);
                    }
                }
            }
            if (group.get(DROP) instanceof List) {
                List<Object> items = (List<Object>) group.get(DROP);
                for (Object o : items) {
                    info.drop(o.toString().toLowerCase());
                }
            }
            if (group.get(KEEP) instanceof List) {
                List<Object> items = (List<Object>) group.get(KEEP);
                for (Object o : items) {
                    info.keep(o.toString().toLowerCase());
                }
            }
            if (isRequest) {
                requestHeaderInfo.put(id, info);
            } else {
                responseHeaderInfo.put(id, info);
            }
            log.info("Loaded {} {} headers {}", id, isRequest ? REQUEST : RESPONSE, go);
        }
    }

    public int getDurationInSeconds(String duration) {
        Utility util = Utility.getInstance();
        int multiplier = 1;
        final int n;
        if (duration.endsWith("s") || duration.endsWith("m") || duration.endsWith("h") || duration.endsWith("d")) {
            n = util.str2int(duration.substring(0, duration.length()-1));
            if (duration.endsWith("m")) {
                multiplier = ONE_MINUTE;
            }
            if (duration.endsWith("h")) {
                multiplier = ONE_HOUR;
            }
            if (duration.endsWith("d")) {
                multiplier = ONE_DAY;
            }
        } else {
            n = util.str2int(duration);
        }
        // set maximum to 5 minutes and minimum to 5 seconds
        return Math.min(FIVE_MINUTES, Math.max(n * multiplier, 5));
    }

}
