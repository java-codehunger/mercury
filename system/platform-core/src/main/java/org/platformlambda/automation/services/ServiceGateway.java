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

package org.platformlambda.automation.services;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.platformlambda.automation.config.RoutingEntry;
import org.platformlambda.automation.models.*;
import org.platformlambda.automation.util.SimpleHttpUtility;
import org.platformlambda.core.exception.AppException;
import org.platformlambda.core.models.AsyncHttpRequest;
import org.platformlambda.core.models.EventEnvelope;
import org.platformlambda.core.serializers.SimpleMapper;
import org.platformlambda.core.serializers.SimpleXmlParser;
import org.platformlambda.core.system.*;
import org.platformlambda.core.util.AppConfigReader;
import org.platformlambda.core.util.CryptoApi;
import org.platformlambda.core.util.Utility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ServiceGateway {
    private static final Logger log = LoggerFactory.getLogger(ServiceGateway.class);

    private static final CryptoApi crypto = new CryptoApi();
    private static final SimpleXmlParser xmlReader = new SimpleXmlParser();
    private static final String HTTP_REQUEST = "http.request";
    private static final String AUTH_HANDLER = "rest.automation.auth.handler";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_LEN = "Content-Length";
    private static final String APPLICATION_FORM_URLENCODED = "application/x-www-form-urlencoded";
    private static final String MULTIPART_FORM_DATA = "multipart/form-data";
    private static final String APPLICATION_JSON = "application/json";
    private static final String APPLICATION_XML = "application/xml";
    private static final String TEXT_HTML = "text/html";
    private static final String TEXT_PLAIN = "text/plain";
    private static final String PROTOCOL = "x-forwarded-proto";
    private static final String HTTPS = "https";
    private static final String COOKIE = "cookie";
    private static final String ASYNC_HTTP_RESPONSE = AppStarter.ASYNC_HTTP_RESPONSE;
    private static final String OPTIONS = "OPTIONS";
    private static final String GET = "GET";
    private static final String PUT = "PUT";
    private static final String POST = "POST";
    private static final String PATCH = "PATCH";
    private static final String INDEX_HTML = "index.html";
    private static final String CLASSPATH = "classpath:";
    private static final String FILEPATH = "file:";
    private static final String ETAG = "ETag";
    private static final String IF_NONE_MATCH = "If-None-Match";
    private static final int BUFFER_SIZE = 4 * 1024;
    // requestId -> context
    private static final ConcurrentMap<String, AsyncContextHolder> contexts = new ConcurrentHashMap<>();
    private static String defaultTraceIdLabel;
    private static List<String> traceIdLabels;
    private static String staticFolder;
    private static String resourceFolder;

    public ServiceGateway() {
        Platform platform = Platform.getInstance();
        if (!platform.hasRoute(AUTH_HANDLER)) {
            Utility util = Utility.getInstance();
            AppConfigReader config = AppConfigReader.getInstance();
            List<String> labels = util.split(config.getProperty("trace.http.header"), ", ");
            if (labels.isEmpty()) {
                labels.add("X-Trace-Id");
            }
            defaultTraceIdLabel = labels.get(0);
            traceIdLabels = labels;
            log.info("HTTP trace headers {}", traceIdLabels);
            String folder = config.getProperty("spring.web.resources.static-locations",
                    config.getProperty("static.html.folder", "classpath:/public"));
            if (folder.startsWith(CLASSPATH)) {
                String resource = folder.substring(CLASSPATH.length());
                resourceFolder = resource.endsWith("/") ? resource.substring(0, resource.length() - 1) : resource;
            } else if (folder.startsWith(FILEPATH)) {
                staticFolder = folder.substring(FILEPATH.length());
            } else if (folder.startsWith("/")) {
                staticFolder = folder;
            } else {
                log.warn("Static content folder must start with {} or {}", CLASSPATH, FILEPATH);
            }
            // register authentication handler
            try {
                platform.registerPrivate(AUTH_HANDLER, new AuthInterceptor(), 200);
            } catch (IOException e) {
                log.error("Unable to load {} - {}", AUTH_HANDLER, e.getMessage());
            }
        }
    }

    public static String getDefaultTraceIdLabel() {
        return defaultTraceIdLabel;
    }

    public ConcurrentMap<String, AsyncContextHolder> getContexts() {
        return contexts;
    }

    public static void closeContext(String requestId) {
        contexts.remove(requestId);
    }

    public void handleEvent(AssignedRoute route, String requestId, int status, String error) {
        AsyncContextHolder holder = contexts.get(requestId);
        if (holder != null) {
            HttpServerRequest request = holder.request;
            SimpleHttpUtility httpUtil = SimpleHttpUtility.getInstance();
            if (error != null) {
                if (GET.equals(request.method().name()) && status == 404) {
                    String path = Utility.getInstance().getUrlDecodedPath(request.path());
                    EtagFile file = getStaticFile(path);
                    if (file != null) {
                        HttpServerResponse response = request.response();
                        response.putHeader(CONTENT_TYPE, getFileContentType(path));
                        String ifNoneMatch = request.getHeader(IF_NONE_MATCH);
                        if (file.equals(ifNoneMatch)) {
                            response.setStatusCode(304);
                            response.putHeader(CONTENT_LEN, String.valueOf(0));
                        } else {
                            response.putHeader(ETAG, file.eTag);
                            response.putHeader(CONTENT_LEN, String.valueOf(file.content.length));
                            response.write(Buffer.buffer(file.content));
                        }
                        closeContext(requestId);
                        response.end();
                        return;
                    }
                }
                httpUtil.sendError(requestId, request, status, error);
            } else {
                try {
                    routeRequest(requestId, route, holder);
                } catch (AppException e) {
                    httpUtil.sendError(requestId, request, e.getStatus(), e.getMessage());
                }
            }
        }
    }

    private String getFileContentType(String path) {
        if (path.endsWith("/") || path.endsWith(".html") || path.endsWith(".htm")) {
            return TEXT_HTML;
        } else if (path.endsWith(".css")) {
            return "text/css";
        } else if (path.endsWith(".js")) {
            return "text/javascript";
        } else {
            return "application/octet-stream";
        }
    }

    private EtagFile getStaticFile(String path) {
        if (path.endsWith("/")) {
            path += INDEX_HTML;
        }
        if (resourceFolder != null) {
            return getResourceFile(path);
        }
        if (staticFolder != null) {
            return getLocalFile(path);
        }
        return null;
    }

    private EtagFile getResourceFile(String path) {
        Utility util = Utility.getInstance();
        InputStream in = this.getClass().getResourceAsStream(resourceFolder+path);
        if (in != null) {
            byte[] b = Utility.getInstance().stream2bytes(in);
            return new EtagFile(util.bytes2hex(crypto.getSHA1(b)), b);
        }
        return null;
    }

    private EtagFile getLocalFile(String path) {
        Utility util = Utility.getInstance();
        File f = new File(staticFolder, path);
        if (f.exists()) {
            byte[] b = Utility.getInstance().file2bytes(f);
            return new EtagFile(util.bytes2hex(crypto.getSHA1(b)), b);
        }
        return null;
    }

    private void routeRequest(String requestId, AssignedRoute route, AsyncContextHolder holder) throws AppException {
        Utility util = Utility.getInstance();
        HttpServerRequest request = holder.request;
        String uri = util.getUrlDecodedPath(request.path());
        String method = request.method().name();
        holder.setUrl(uri).setMethod(method).setResHeaderId(route.info.responseTransformId);
        SimpleHttpUtility httpUtil = SimpleHttpUtility.getInstance();
        if (OPTIONS.equals(method)) {
            // insert CORS headers for OPTIONS
            if (route.info.corsId == null) {
                throw new AppException(405, "Method not allowed");
            } else {
                CorsInfo corsInfo = RoutingEntry.getInstance().getCorsInfo(route.info.corsId);
                if (corsInfo != null && !corsInfo.options.isEmpty()) {
                    HttpServerResponse response = request.response();
                    for (String ch : corsInfo.options.keySet()) {
                        String prettyHeader = getHeaderCase(ch);
                        if (prettyHeader != null) {
                            response.putHeader(prettyHeader, corsInfo.options.get(ch));
                        }
                    }
                    closeContext(requestId);
                    response.setStatusCode(204).end();
                } else {
                    throw new AppException(405, "Method not allowed");
                }
            }
            return;
        }
        HttpServerResponse response = request.response();
        // insert CORS headers for the HTTP response
        if (route.info.corsId != null) {
            CorsInfo corsInfo = RoutingEntry.getInstance().getCorsInfo(route.info.corsId);
            if (corsInfo != null && !corsInfo.headers.isEmpty()) {
                for (String ch : corsInfo.headers.keySet()) {
                    String prettyHeader = getHeaderCase(ch);
                    if (prettyHeader != null) {
                        response.putHeader(prettyHeader, corsInfo.headers.get(ch));
                    }
                }
            }
        }
        // check if target service is available
        String authService = null;
        PostOffice po = PostOffice.getInstance();
        if (!po.exists(route.info.primary)) {
            throw new AppException(503, "Service " + route.info.primary + " not reachable");
        }
        if (route.info.defaultAuthService != null) {
            List<String> authHeaders = route.info.getAuthHeaders();
            if (!authHeaders.isEmpty()) {
                for (String h: authHeaders) {
                    String v = request.getHeader(h);
                    if (v != null) {
                        String svc = route.info.getAuthService(h);
                        if (svc == null) {
                            svc = route.info.getAuthService(h, v);
                        }
                        if (svc != null) {
                            authService = svc;
                            break;
                        }
                    }
                }
            }
            if (authService == null) {
                authService = route.info.defaultAuthService;
            }
            if (!po.exists(authService)) {
                throw new AppException(503, "Service " + authService + " not reachable");
            }
        }
        AsyncHttpRequest req = new AsyncHttpRequest();
        String queryString = request.query();
        if (queryString != null) {
            req.setQueryString(queryString);
        }
        req.setUrl(httpUtil.normalizeUrl(uri, route.info.urlRewrite));
        if (route.info.host != null) {
            req.setTargetHost(route.info.host);
            req.setTrustAllCert(route.info.trustAllCert);
        }
        req.setMethod(method);
        req.setSecure(HTTPS.equals(request.getHeader(PROTOCOL)));
        req.setTimeoutSeconds(route.info.timeoutSeconds);
        if (!route.arguments.isEmpty()) {
            for (String p: route.arguments.keySet()) {
                req.setPathParameter(p, route.arguments.get(p));
            }
        }
        MultiMap params = request.params();
        for (String key: params.names()) {
            List<String> values = params.getAll(key);
            if (values.size() == 1) {
                req.setQueryParameter(key, values.get(0));
            }
            if (values.size() > 1) {
                req.setQueryParameter(key, values);
            }
        }
        boolean hasCookies = false;
        Map<String, String> headers = new HashMap<>();
        MultiMap headerMap = request.headers();
        for (String key: headerMap.names()) {
            /*
             * Single-value HTTP header is assumed.
             */
            String value = headerMap.get(key);
            if (key.equalsIgnoreCase(COOKIE)) {
                hasCookies = true;
            } else {
                headers.put(key.toLowerCase(), value);
            }
        }
        // load cookies
        if (hasCookies) {
            Set<Cookie> cookies = request.cookies();
            for (Cookie c : cookies) {
                req.setCookie(c.getName(), c.getValue());
            }
        }
        RoutingEntry re = RoutingEntry.getInstance();
        if (route.info.requestTransformId != null) {
            headers = httpUtil.filterHeaders(re.getRequestHeaderInfo(route.info.requestTransformId), headers);
        }
        for (Map.Entry<String, String> entry: headers.entrySet()) {
            req.setHeader(entry.getKey(), entry.getValue());
        }
        if (route.info.flowId != null) {
            req.setHeader("x-flow-id", route.info.flowId);
        }
        req.setRemoteIp(request.remoteAddress().hostAddress());
        // Distributed tracing required?
        String traceId = null;
        String tracePath = null;
        // Set trace header if needed
        if (route.info.tracing) {
            List<String> traceHeader = getTraceId(request);
            traceId = traceHeader.get(1);
            tracePath = method + " " + uri;
            if (queryString != null) {
                tracePath += "?" + queryString;
            }
            response.putHeader(traceHeader.get(0), traceHeader.get(1));
        }
        final HttpRequestEvent requestEvent = new HttpRequestEvent(requestId, route.info.primary,
                                                    authService, traceId, tracePath,
                                                    route.info.services, route.info.timeoutSeconds * 1000L,
                                                    route.info.tracing);
        // load HTTP body
        if (POST.equals(method) || PUT.equals(method) || PATCH.equals(method)) {
            final AtomicBoolean inputComplete = new AtomicBoolean(false);
            final ByteArrayOutputStream requestBody = new ByteArrayOutputStream();
            String contentType = request.getHeader(CONTENT_TYPE);
            if (contentType == null) {
                contentType = "?";
            }
            if (contentType.startsWith(MULTIPART_FORM_DATA) && POST.equals(method)) {
                final StreamHolder stream = new StreamHolder(route.info.timeoutSeconds);
                request.uploadHandler(upload -> {
                    req.setFileName(upload.filename());
                    final AtomicInteger total = new AtomicInteger();
                    upload.handler(block -> {
                        int len = block.length();
                        if (len > 0) {
                            total.addAndGet(len);
                            readInputStream(stream.getOutputStream(), block, len);
                        }
                    }).endHandler(end -> {
                        int size = total.get();
                        req.setContentLength(size);
                        if (size > 0) {
                            req.setStreamRoute(stream.getInputStreamId());
                            stream.close();
                        }
                        sendRequestToService(request, requestEvent.setHttpRequest(req));
                    });
                });
                request.resume();

            } else if (contentType.startsWith(APPLICATION_JSON)) {
                request.bodyHandler(block -> {
                    byte[] b = block.getBytes(0, block.length());
                    requestBody.write(b, 0, b.length);
                    if (inputComplete.get()) {
                        String text = util.getUTF(requestBody.toByteArray()).trim();
                        if (text.length() == 0) {
                            req.setBody(new HashMap<>());
                        } else {
                            if (text.startsWith("{") && text.endsWith("}")) {
                                req.setBody(SimpleMapper.getInstance().getMapper().readValue(text, Map.class));
                            } else if (text.startsWith("[") && text.endsWith("]")) {
                                req.setBody(SimpleMapper.getInstance().getMapper().readValue(text, List.class));
                            } else {
                                req.setBody(text);
                            }
                        }
                        sendRequestToService(request, requestEvent.setHttpRequest(req));
                    }
                }).endHandler(done -> inputComplete.set(true));
            } else if (contentType.startsWith(APPLICATION_XML)) {
                request.bodyHandler(block -> {
                    byte[] b = block.getBytes(0, block.length());
                    requestBody.write(b, 0, b.length);
                    if (inputComplete.get()) {
                        String text = util.getUTF(requestBody.toByteArray()).trim();
                        try {
                            req.setBody(text.isEmpty()? new HashMap<>() : xmlReader.parse(text));
                        } catch (Exception e) {
                            req.setBody(text);
                        }
                        sendRequestToService(request, requestEvent.setHttpRequest(req));
                    }
                }).endHandler(done -> inputComplete.set(true));
            } else if (contentType.equals(APPLICATION_FORM_URLENCODED) ||
                    contentType.startsWith(TEXT_HTML) || contentType.startsWith(TEXT_PLAIN)) {
                final boolean urlEncodeParameters = contentType.equals(APPLICATION_FORM_URLENCODED);
                request.bodyHandler(block -> {
                    byte[] b = block.getBytes(0, block.length());
                    requestBody.write(b, 0, b.length);
                    if (inputComplete.get()) {
                        String text = util.getUTF(requestBody.toByteArray());
                        if (urlEncodeParameters) {
                            Map<String, String> kv = httpUtil.decodeQueryString(text);
                            for (Map.Entry<String, String> entry: kv.entrySet()) {
                                req.setQueryParameter(entry.getKey(), entry.getValue());
                            }
                        } else {
                            req.setBody(text);
                        }
                        sendRequestToService(request, requestEvent.setHttpRequest(req));
                    }
                }).endHandler(done -> inputComplete.set(true));
            } else {
                /*
                 * Input is not JSON, XML or TEXT.
                 * Check if the content-length is larger than threshold.
                 * For large payload, it is better to deliver as a stream.
                 */
                int contentLen = util.str2int(request.getHeader(CONTENT_LEN));
                if (contentLen > 0 && contentLen <= route.info.threshold) {
                    request.bodyHandler(block -> {
                        byte[] b = block.getBytes(0, block.length());
                        requestBody.write(b, 0, b.length);
                        if (inputComplete.get()) {
                            req.setBody(requestBody.toByteArray());
                            sendRequestToService(request, requestEvent.setHttpRequest(req));
                        }
                    }).endHandler(done -> inputComplete.set(true));
                } else {
                    final AtomicInteger total = new AtomicInteger();
                    final StreamHolder stream = new StreamHolder(route.info.timeoutSeconds);
                    request.bodyHandler(block -> {
                        int len = block.length();
                        if (len > 0) {
                            total.addAndGet(len);
                            readInputStream(stream.getOutputStream(), block, len);
                        }
                        if (inputComplete.get()) {
                            int size = total.get();
                            req.setContentLength(size);
                            if (size > 0) {
                                req.setStreamRoute(stream.getInputStreamId());
                                stream.close();
                            }
                            sendRequestToService(request, requestEvent.setHttpRequest(req));
                        }
                    }).endHandler(end -> inputComplete.set(true));
                }
            }
        } else {
            sendRequestToService(request, requestEvent.setHttpRequest(req));
        }
    }

    public void sendRequestToService(HttpServerRequest request, HttpRequestEvent requestEvent) {
        SimpleHttpUtility httpUtil = SimpleHttpUtility.getInstance();
        PostOffice po = PostOffice.getInstance();
        if (requestEvent.authService != null) {
            try {
                po.send(AUTH_HANDLER, requestEvent.toMap());
            } catch (IOException e) {
                httpUtil.sendError(requestEvent.requestId, request,400, e.getMessage());
            }
        } else {
            EventEnvelope event = new EventEnvelope();
            event.setTo(requestEvent.primary).setBody(requestEvent.httpRequest)
                    .setCorrelationId(requestEvent.requestId)
                    .setReplyTo(ASYNC_HTTP_RESPONSE + "@" + Platform.getInstance().getOrigin());
            // enable distributed tracing if needed
            if (requestEvent.tracing) {
                event.setFrom(HTTP_REQUEST);
                event.setTrace(requestEvent.traceId, requestEvent.tracePath);
            }
            try {
                po.send(event);
                // copying to secondary services if any
                if (requestEvent.services.size() > 1) {
                    for (String secondary : requestEvent.services) {
                        if (!secondary.equals(requestEvent.primary)) {
                            EventEnvelope copy = new EventEnvelope().setTo(secondary).setBody(requestEvent.httpRequest);
                            if (requestEvent.tracing) {
                                copy.setFrom(HTTP_REQUEST);
                                copy.setTrace(requestEvent.traceId, requestEvent.tracePath);
                            }
                            sendToSecondaryTarget(copy);
                        }
                    }
                }
            } catch (IOException e) {
                httpUtil.sendError(requestEvent.requestId, request, 400, e.getMessage());
            }
        }
    }

    private void sendToSecondaryTarget(EventEnvelope event) {
        try {
            PostOffice.getInstance().send(event);
        } catch (Exception e) {
            log.warn("Unable to copy event to {} - {}", event.getTo(), e.getMessage());
        }
    }

    private void readInputStream(ObjectStreamWriter out, Buffer block, int len) {
        if (out != null && block != null && len > 0) {
            try {
                byte[] data = block.getBytes(0, len);
                if (data.length > BUFFER_SIZE) {
                    int bytesRead = 0;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    ByteArrayInputStream in = new ByteArrayInputStream(data);
                    while (bytesRead < data.length) {
                        int n = in.read(buffer);
                        bytesRead += n;
                        out.write(buffer, 0, n);
                    }
                } else {
                    out.write(data);
                }
            } catch (IOException e) {
                log.error("Unexpected error while reading HTTP input stream", e);
            }
        }
    }

    /**
     * Get X-Trace-Id from HTTP request headers if any.
     * Otherwise, generate a unique ID.
     *
     * @param request HTTP
     * @return traceLabel and traceId
     */
    private List<String> getTraceId(HttpServerRequest request) {
        List<String> result = new ArrayList<>();
        for (String label: traceIdLabels) {
            String id = request.getHeader(label);
            if (id != null) {
                result.add(label);
                result.add(id);
            }
        }
        result.add(getDefaultTraceIdLabel());
        result.add(Utility.getInstance().getUuid());
        return result;
    }

    private String getHeaderCase(String header) {
        StringBuilder sb = new StringBuilder();
        List<String> parts = Utility.getInstance().split(header, "-");
        for (String p: parts) {
            sb.append(p.substring(0, 1).toUpperCase());
            if (p.length() > 1) {
                sb.append(p.substring(1));
            }
            sb.append('-');
        }
        return sb.length() == 0? null : sb.substring(0, sb.length()-1);
    }

}
