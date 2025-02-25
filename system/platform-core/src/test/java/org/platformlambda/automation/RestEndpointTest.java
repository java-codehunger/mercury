package org.platformlambda.automation;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.platformlambda.core.exception.AppException;
import org.platformlambda.core.mock.TestBase;
import org.platformlambda.core.models.AsyncHttpRequest;
import org.platformlambda.core.models.EventEnvelope;
import org.platformlambda.core.models.LambdaFunction;
import org.platformlambda.core.serializers.SimpleMapper;
import org.platformlambda.core.serializers.SimpleXmlWriter;
import org.platformlambda.core.system.*;
import org.platformlambda.core.util.MultiLevelMap;
import org.platformlambda.core.util.Utility;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeoutException;

public class RestEndpointTest extends TestBase {

    private static final String MULTIPART_FORM_DATA = "multipart/form-data";
    private static final String HTTP_REQUEST = "async.http.request";
    private static final long RPC_TIMEOUT = 10000;

    @Before
    public void setupAuthenticator() throws IOException {
        Platform platform = Platform.getInstance();
        PostOffice po = PostOffice.getInstance();
        if (!platform.hasRoute("v1.api.auth")) {
            LambdaFunction f = (headers, body, instance) -> {
                po.annotateTrace("hello", "world");
                return true;
            };
            platform.registerPrivate("v1.api.auth", f, 1);
        }
        if (!platform.hasRoute("v1.demo.auth")) {
            LambdaFunction f = (headers, body, instance) -> {
                po.annotateTrace("demo", "will show 'unauthorized'");
                return false;
            };
            platform.registerPrivate("v1.demo.auth", f, 1);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void serviceTest() throws AppException, IOException, TimeoutException {
        PostOffice po = PostOffice.getInstance();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("GET");
        req.setHeader("accept", "application/json");
        req.setUrl("/api/hello/world?hello world=abc");
        req.setQueryParameter("x1", "y");
        List<String> list = new ArrayList<>();
        list.add("a");
        list.add("b");
        req.setQueryParameter("x2", list);
        req.setTargetHost("http://127.0.0.1:"+port);

        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Assert.assertTrue(res.getBody() instanceof Map);
        MultiLevelMap map = new MultiLevelMap((Map<String, Object>) res.getBody());
        Assert.assertEquals("application/json", map.getElement("headers.accept"));
        Assert.assertEquals(false, map.getElement("https"));
        Assert.assertEquals("/api/hello/world", map.getElement("url"));
        Assert.assertEquals("GET", map.getElement("method"));
        Assert.assertEquals("127.0.0.1", map.getElement("ip"));
        Assert.assertEquals(10, map.getElement("timeout"));
        Assert.assertEquals("y", map.getElement("parameters.query.x1"));
        Assert.assertEquals(list, map.getElement("parameters.query.x2"));
    }


    @Test
    public void uriPathSecurityTest() {
        uriPathSecurity("/api/hello/world moved to https://evil.site?hello world=abc",
                "/api/hello/world moved to https");
        uriPathSecurity("/api/hello/world <div>test</div>",
                "/api/hello/world ");
        uriPathSecurity("/api/hello/world > something",
                "/api/hello/world ");
        uriPathSecurity("/api/hello/world &nbsp;",
                "/api/hello/world ");
    }

    @SuppressWarnings("unchecked")
    private void uriPathSecurity(String uri, String expected) {
        PostOffice po = PostOffice.getInstance();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("GET");
        req.setHeader("accept", "application/json");
        req.setUrl(uri);
        req.setQueryParameter("x1", "y");
        List<String> list = new ArrayList<>();
        list.add("a");
        list.add("b");
        req.setQueryParameter("x2", list);
        req.setTargetHost("http://127.0.0.1:"+port);
        AppException ex = Assert.assertThrows(AppException.class, () -> po.request(HTTP_REQUEST, RPC_TIMEOUT, req));
        Assert.assertEquals(404, ex.getStatus());
        Map<String, Object> error = SimpleMapper.getInstance().getMapper().readValue(ex.getMessage(), Map.class);
        Assert.assertEquals(expected, error.get("path"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void authRoutingTest1() {
        Utility util = Utility.getInstance();
        PostOffice po = PostOffice.getInstance();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("GET");
        req.setHeader("accept", "application/json");
        String credentials = "Basic " + util.bytesToBase64(util.getUTF("hello:world"));
        req.setHeader("authorization", credentials);
        req.setUrl("/api/hello/world");
        req.setTargetHost("http://127.0.0.1:"+port);
        try {
            po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
            throw new IllegalArgumentException("Test is excepted to throw AppException");
        } catch (Exception e) {
            Assert.assertTrue(e instanceof AppException);
            AppException ex = (AppException) e;
            Assert.assertEquals(503, ex.getStatus());
            String message = ex.getMessage();
            Map<String, Object> eMap = SimpleMapper.getInstance().getMapper().readValue(message, Map.class);
            Assert.assertEquals("Service v1.basic.auth not reachable", eMap.get("message"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void authRoutingTest2() {
        PostOffice po = PostOffice.getInstance();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("GET");
        req.setHeader("accept", "application/json");
        req.setHeader("x-app-name", "demo");
        req.setUrl("/api/hello/world");
        req.setTargetHost("http://127.0.0.1:"+port);
        try {
            po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
            throw new IllegalArgumentException("Test is excepted to throw AppException");
        } catch (Exception e) {
            Assert.assertTrue(e instanceof AppException);
            AppException ex = (AppException) e;
            Assert.assertEquals(401, ex.getStatus());
            String message = ex.getMessage();
            Map<String, Object> eMap = SimpleMapper.getInstance().getMapper().readValue(message, Map.class);
            Assert.assertEquals("Unauthorized", eMap.get("message"));
        }
    }

    @Test
    public void uploadSmallBlockWithPut() throws AppException, IOException, TimeoutException {
        Utility util = Utility.getInstance();
        PostOffice po = PostOffice.getInstance();
        int len = 0;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ObjectStreamIO stream = new ObjectStreamIO();
        ObjectStreamWriter out = new ObjectStreamWriter(stream.getOutputStreamId());
        for (int i=0; i < 100; i++) {
            byte[] line = util.getUTF("hello world "+i+"\n");
            out.write(line);
            bytes.write(line);
            len += line.length;
        }
        out.close();
        byte[] b = bytes.toByteArray();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("PUT");
        req.setUrl("/api/hello/world");
        req.setTargetHost("http://127.0.0.1:"+port);
        req.setStreamRoute(stream.getInputStreamId());
        req.setContentLength(len);
        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Assert.assertTrue(res.getBody() instanceof byte[]);
        Assert.assertArrayEquals(b, (byte[]) res.getBody());
    }

    @Test
    public void uploadBytesWithPut() throws AppException, IOException, TimeoutException {
        Utility util = Utility.getInstance();
        PostOffice po = PostOffice.getInstance();
        int len = 0;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i=0; i < 100; i++) {
            byte[] line = util.getUTF("hello world "+i+"\n");
            bytes.write(line);
            len += line.length;
        }
        byte[] b = bytes.toByteArray();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("PUT");
        req.setUrl("/api/hello/world");
        req.setTargetHost("http://127.0.0.1:"+port);
        req.setBody(b);
        req.setContentLength(len);
        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Assert.assertTrue(res.getBody() instanceof byte[]);
        Assert.assertArrayEquals(b, (byte[]) res.getBody());
    }

    @Test
    public void uploadLargeBlockWithPut() throws AppException, IOException, TimeoutException {
        Utility util = Utility.getInstance();
        PostOffice po = PostOffice.getInstance();
        int len = 0;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ObjectStreamIO stream = new ObjectStreamIO();
        ObjectStreamWriter out = new ObjectStreamWriter(stream.getOutputStreamId());
        for (int i=0; i < 600; i++) {
            String line = "hello world "+i+"\n";
            byte[] d = util.getUTF(line);
            out.write(d);
            byte[] d2 = util.getUTF(line);
            bytes.write(d2);
            len += d2.length;
        }
        out.close();

        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("PUT");
        req.setUrl("/api/hello/world");
        req.setTargetHost("http://127.0.0.1:"+port);
        req.setHeader("accept", "application/octet-stream");
        req.setHeader("content-type", "application/octet-stream");
        req.setContentLength(len);
        req.setStreamRoute(stream.getInputStreamId());
        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Assert.assertNotNull(res.getHeaders().get("stream"));
        String resultStream = res.getHeaders().get("stream");
        try (ObjectStreamReader in = new ObjectStreamReader(resultStream, 30000)) {
            ByteArrayOutputStream restored = new ByteArrayOutputStream();
            for (Object o : in) {
                if (o instanceof byte[]) {
                    restored.write((byte[]) o);
                }
            }
            Assert.assertArrayEquals(bytes.toByteArray(), restored.toByteArray());
        }
    }

    @Test
    public void uploadMultipartWithPost() throws AppException, IOException, TimeoutException {
        Utility util = Utility.getInstance();
        PostOffice po = PostOffice.getInstance();
        int len = 0;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ObjectStreamIO stream = new ObjectStreamIO();
        ObjectStreamWriter out = new ObjectStreamWriter(stream.getOutputStreamId());
        for (int i=0; i < 600; i++) {
            String line = "hello world "+i+"\n";
            byte[] d = util.getUTF(line);
            out.write(d);
            byte[] d2 = util.getUTF(line);
            bytes.write(d2);
            len += d2.length;
        }
        out.close();

        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("POST");
        req.setUrl("/api/upload/demo");
        req.setTargetHost("http://127.0.0.1:"+port);
        req.setHeader("accept", "application/json");
        req.setHeader("content-type", MULTIPART_FORM_DATA);
        req.setContentLength(len);
        req.setFileName("hello-world.txt");
        req.setStreamRoute(stream.getInputStreamId());
        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Assert.assertNotNull(res.getHeaders().get("stream"));
        String resultStream = res.getHeaders().get("stream");
        try (ObjectStreamReader in = new ObjectStreamReader(resultStream, 30000)) {
            ByteArrayOutputStream restored = new ByteArrayOutputStream();
            for (Object o : in) {
                if (o instanceof byte[]) {
                    restored.write((byte[]) o);
                }
            }
            Assert.assertArrayEquals(bytes.toByteArray(), restored.toByteArray());
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void postJson() throws AppException, IOException, TimeoutException {
        PostOffice po = PostOffice.getInstance();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("POST");
        req.setUrl("/api/hello/world");
        req.setTargetHost("http://127.0.0.1:"+port);
        Map<String, Object> data = new HashMap<>();
        data.put("hello", "world");
        data.put("test", "message");
        String json = SimpleMapper.getInstance().getMapper().writeValueAsString(data);
        req.setBody(json);
        req.setHeader("accept", "application/json");
        req.setHeader("content-type", "application/json");
        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Assert.assertTrue(res.getBody() instanceof Map);
        MultiLevelMap map = new MultiLevelMap((Map<String, Object>) res.getBody());
        Assert.assertEquals("application/json", map.getElement("headers.content-type"));
        Assert.assertEquals("application/json", map.getElement("headers.accept"));
        Assert.assertEquals(false, map.getElement("https"));
        Assert.assertEquals("/api/hello/world", map.getElement("url"));
        Assert.assertEquals("POST", map.getElement("method"));
        Assert.assertEquals("127.0.0.1", map.getElement("ip"));
        Assert.assertEquals(10, map.getElement("timeout"));
        Assert.assertTrue(map.getElement("body") instanceof Map);
        Map<String, Object> received = (Map<String, Object>) map.getElement("body");
        Assert.assertEquals(data, received);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void postXml() throws AppException, IOException, TimeoutException {
        PostOffice po = PostOffice.getInstance();
        SimpleXmlWriter xmlWriter = new SimpleXmlWriter();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("POST");
        req.setUrl("/api/hello/world");
        req.setTargetHost("http://127.0.0.1:"+port);
        Map<String, Object> data = new HashMap<>();
        data.put("hello", "world");
        data.put("test", "message");
        String xml = xmlWriter.write(data);
        req.setBody(xml);
        req.setHeader("accept", "application/xml");
        req.setHeader("content-type", "application/xml");
        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Assert.assertTrue(res.getBody() instanceof Map);
        MultiLevelMap map = new MultiLevelMap((Map<String, Object>) res.getBody());
        Assert.assertEquals("application/xml", map.getElement("headers.content-type"));
        Assert.assertEquals("application/xml", map.getElement("headers.accept"));
        // xml key-values are parsed as text
        Assert.assertEquals("false", map.getElement("https"));
        Assert.assertEquals("/api/hello/world", map.getElement("url"));
        Assert.assertEquals("POST", map.getElement("method"));
        Assert.assertEquals("127.0.0.1", map.getElement("ip"));
        Assert.assertEquals("10", map.getElement("timeout"));
        Assert.assertTrue(map.getElement("body") instanceof Map);
        Map<String, Object> received = (Map<String, Object>) map.getElement("body");
        Assert.assertEquals(data, received);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void postJsonMap() throws AppException, IOException, TimeoutException {
        PostOffice po = PostOffice.getInstance();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("POST");
        req.setUrl("/api/hello/world");
        req.setTargetHost("http://127.0.0.1:"+port);
        Map<String, Object> data = new HashMap<>();
        data.put("hello", "world");
        data.put("test", "message");
        req.setBody(data);
        req.setHeader("accept", "application/json");
        req.setHeader("content-type", "application/json");
        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Assert.assertTrue(res.getBody() instanceof Map);
        MultiLevelMap map = new MultiLevelMap((Map<String, Object>) res.getBody());
        Assert.assertEquals("application/json", map.getElement("headers.content-type"));
        Assert.assertEquals("application/json", map.getElement("headers.accept"));
        Assert.assertEquals(false, map.getElement("https"));
        Assert.assertEquals("/api/hello/world", map.getElement("url"));
        Assert.assertEquals("POST", map.getElement("method"));
        Assert.assertEquals("127.0.0.1", map.getElement("ip"));
        Assert.assertEquals(10, map.getElement("timeout"));
        Assert.assertTrue(map.getElement("body") instanceof Map);
        Map<String, Object> received = (Map<String, Object>) map.getElement("body");
        Assert.assertEquals(data, received);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testJsonResultList() throws AppException, IOException, TimeoutException {
        PostOffice po = PostOffice.getInstance();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("POST");
        req.setUrl("/api/hello/list");
        req.setTargetHost("http://127.0.0.1:"+port);
        Map<String, Object> data = new HashMap<>();
        data.put("hello", "world");
        data.put("test", "message");
        req.setBody(data);
        req.setHeader("accept", "application/json");
        req.setHeader("content-type", "application/json");
        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Assert.assertTrue(res.getBody() instanceof List);
        List<Map<String, Object>> list = (List<Map<String, Object>>) res.getBody();
        Assert.assertEquals(1, list.size());
        Assert.assertEquals(HashMap.class, list.get(0).getClass());
        MultiLevelMap map = new MultiLevelMap((Map<String, Object>) list.get(0));
        Assert.assertEquals("application/json", map.getElement("headers.content-type"));
        Assert.assertEquals("application/json", map.getElement("headers.accept"));
        Assert.assertEquals(false, map.getElement("https"));
        Assert.assertEquals("/api/hello/list", map.getElement("url"));
        Assert.assertEquals("POST", map.getElement("method"));
        Assert.assertEquals("127.0.0.1", map.getElement("ip"));
        Assert.assertEquals(15, map.getElement("timeout"));
        Assert.assertTrue(map.getElement("body") instanceof Map);
        Map<String, Object> received = (Map<String, Object>) map.getElement("body");
        Assert.assertEquals(data, received);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testXmlResultList() throws AppException, IOException, TimeoutException {
        PostOffice po = PostOffice.getInstance();
        SimpleXmlWriter xmlWriter = new SimpleXmlWriter();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("POST");
        req.setUrl("/api/hello/list");
        req.setTargetHost("http://127.0.0.1:"+port);
        Map<String, Object> data = new HashMap<>();
        data.put("hello", "world");
        data.put("test", "message");
        String xml = xmlWriter.write(data);
        req.setBody(xml);
        req.setHeader("accept", "application/xml");
        req.setHeader("content-type", "application/xml");
        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Assert.assertTrue(res.getBody() instanceof Map);
        MultiLevelMap map = new MultiLevelMap((Map<String, Object>) res.getBody());
        Assert.assertEquals("application/xml", map.getElement("result.headers.content-type"));
        Assert.assertEquals("application/xml", map.getElement("result.headers.accept"));
        // xml key-values are parsed as text
        Assert.assertEquals("false", map.getElement("result.https"));
        Assert.assertEquals("/api/hello/list", map.getElement("result.url"));
        Assert.assertEquals("POST", map.getElement("result.method"));
        Assert.assertEquals("127.0.0.1", map.getElement("result.ip"));
        Assert.assertEquals("15", map.getElement("result.timeout"));
        Assert.assertTrue(map.getElement("result.body") instanceof Map);
        Map<String, Object> received = (Map<String, Object>) map.getElement("result.body");
        Assert.assertEquals(data, received);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void sendHttpDelete() throws AppException, IOException, TimeoutException {
        PostOffice po = PostOffice.getInstance();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("DELETE");
        req.setUrl("/api/hello/world");
        req.setTargetHost("http://127.0.0.1:"+port);
        req.setHeader("accept", "application/json");
        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Assert.assertTrue(res.getBody() instanceof Map);
        MultiLevelMap map = new MultiLevelMap((Map<String, Object>) res.getBody());
        Assert.assertEquals("application/json", map.getElement("headers.accept"));
        Assert.assertEquals(false, map.getElement("https"));
        Assert.assertEquals("/api/hello/world", map.getElement("url"));
        Assert.assertEquals("DELETE", map.getElement("method"));
        Assert.assertEquals("127.0.0.1", map.getElement("ip"));
        Assert.assertEquals(10, map.getElement("timeout"));
        Assert.assertNull(map.getElement("body"));
    }

    @Test
    public void sendHttpHeadWithCID() throws AppException, IOException, TimeoutException {
        String traceId = Utility.getInstance().getDateUuid();
        PostOffice po = PostOffice.getInstance();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("HEAD");
        req.setUrl("/api/hello/world");
        req.setTargetHost("http://127.0.0.1:"+port);
        req.setHeader("accept", "application/json");
        req.setHeader("x-correlation-id", traceId);
        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Map<String, String> headers = res.getHeaders();
        // HTTP head response may include custom headers and content-length
        Assert.assertEquals("HEAD request received", headers.get("x-response"));
        Assert.assertEquals("100", headers.get("content-length"));
        // the same correlation-id is returned to the caller
        Assert.assertEquals(traceId, headers.get("x-correlation-id"));
    }

    @Test
    public void sendHttpHeadWithTraceId() throws AppException, IOException, TimeoutException {
        String traceId = Utility.getInstance().getDateUuid();
        PostOffice po = PostOffice.getInstance();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("HEAD");
        req.setUrl("/api/hello/world");
        req.setTargetHost("http://127.0.0.1:"+port);
        req.setHeader("accept", "application/json");
        req.setHeader("x-trace-id", traceId);
        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Map<String, String> headers = res.getHeaders();
        // HTTP head response may include custom headers and content-length
        Assert.assertEquals("HEAD request received", headers.get("x-response"));
        Assert.assertEquals("100", headers.get("content-length"));
        // the same correlation-id is returned to the caller
        Assert.assertEquals(traceId, headers.get("x-trace-id"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void postXmlMap() throws AppException, IOException, TimeoutException {
        PostOffice po = PostOffice.getInstance();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("POST");
        req.setUrl("/api/hello/world");
        req.setTargetHost("http://127.0.0.1:"+port);
        Map<String, Object> data = new HashMap<>();
        data.put("hello", "world");
        data.put("test", "message");
        req.setBody(data);
        req.setHeader("accept", "application/json");
        req.setHeader("content-type", "application/xml");
        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Assert.assertTrue(res.getBody() instanceof Map);
        MultiLevelMap map = new MultiLevelMap((Map<String, Object>) res.getBody());
        Assert.assertEquals("application/xml", map.getElement("headers.content-type"));
        Assert.assertEquals("application/json", map.getElement("headers.accept"));
        Assert.assertEquals(false, map.getElement("https"));
        Assert.assertEquals("/api/hello/world", map.getElement("url"));
        Assert.assertEquals("POST", map.getElement("method"));
        Assert.assertEquals("127.0.0.1", map.getElement("ip"));
        Assert.assertEquals(10, map.getElement("timeout"));
        Assert.assertTrue(map.getElement("body") instanceof Map);
        Map<String, Object> received = (Map<String, Object>) map.getElement("body");
        Assert.assertEquals(data, received);
    }


    @SuppressWarnings("unchecked")
    @Test
    public void postList() throws AppException, IOException, TimeoutException {
        PostOffice po = PostOffice.getInstance();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("POST");
        req.setUrl("/api/hello/world");
        req.setTargetHost("http://127.0.0.1:"+port);
        Map<String, Object> data = new HashMap<>();
        data.put("hello", "world");
        data.put("test", "message");
        req.setBody(Collections.singletonList(data));
        req.setHeader("accept", "application/json");
        req.setHeader("content-type", "application/json");
        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Assert.assertTrue(res.getBody() instanceof Map);
        MultiLevelMap map = new MultiLevelMap((Map<String, Object>) res.getBody());
        Assert.assertEquals("application/json", map.getElement("headers.content-type"));
        Assert.assertEquals("application/json", map.getElement("headers.accept"));
        Assert.assertEquals(false, map.getElement("https"));
        Assert.assertEquals("/api/hello/world", map.getElement("url"));
        Assert.assertEquals("POST", map.getElement("method"));
        Assert.assertEquals("127.0.0.1", map.getElement("ip"));
        Assert.assertEquals(10, map.getElement("timeout"));
        Assert.assertTrue(map.getElement("body") instanceof List);
        List<Map<String, Object>> received = (List<Map<String, Object>>) map.getElement("body");
        Assert.assertEquals(1, received.size());
        Assert.assertEquals(data, received.get(0));
    }

    @Test
    public void getIndexPage() throws AppException, IOException, TimeoutException {
        Utility util = Utility.getInstance();
        PostOffice po = PostOffice.getInstance();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("GET");
        req.setUrl("/");
        req.setTargetHost("http://127.0.0.1:"+port);
        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Assert.assertEquals("text/html", res.getHeaders().get("content-type"));
        Assert.assertTrue(res.getBody() instanceof String);
        String html = (String) res.getBody();
        InputStream in = this.getClass().getResourceAsStream("/public/index.html");
        String content = util.stream2str(in);
        Assert.assertEquals(content, html);
    }

    @Test
    public void getCssPage() throws AppException, IOException, TimeoutException {
        Utility util = Utility.getInstance();
        PostOffice po = PostOffice.getInstance();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("GET");
        req.setUrl("/sample.css");
        req.setTargetHost("http://127.0.0.1:"+port);
        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Assert.assertEquals("text/css", res.getHeaders().get("content-type"));
        Assert.assertTrue(res.getBody() instanceof String);
        String html = (String) res.getBody();
        InputStream in = this.getClass().getResourceAsStream("/public/sample.css");
        String content = util.stream2str(in);
        Assert.assertEquals(content, html);
    }

    @Test
    public void getJsPage() throws AppException, IOException, TimeoutException {
        Utility util = Utility.getInstance();
        PostOffice po = PostOffice.getInstance();
        AsyncHttpRequest req = new AsyncHttpRequest();
        req.setMethod("GET");
        req.setUrl("/sample.js");
        req.setTargetHost("http://127.0.0.1:"+port);
        EventEnvelope res = po.request(HTTP_REQUEST, RPC_TIMEOUT, req);
        Assert.assertEquals("text/javascript", res.getHeaders().get("content-type"));
        Assert.assertTrue(res.getBody() instanceof String);
        String html = (String) res.getBody();
        InputStream in = this.getClass().getResourceAsStream("/public/sample.js");
        String content = util.stream2str(in);
        Assert.assertEquals(content, html);
    }

}
