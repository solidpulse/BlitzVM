package dev.phpruntime.host;

import dev.phpruntime.runtime.PhpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Blitz\Http\Server API over real sockets: the request/response objects, lazy request
 * data, keep-alive and pipelining, chunked bodies, 100-continue, malformed input, handler
 * failures, and more than one worker.
 */
class BlitzServerModeTest {

    @TempDir
    Path dir;

    private int port;
    private PhpEngine engine;
    private BlitzServerRuntime runtime;
    private Thread serverThread;
    private final AtomicReference<PhpEngine.Result> result = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    @AfterEach
    void tearDown() throws Exception {
        if (runtime != null) {
            runtime.stop();
        }
        if (serverThread != null) {
            serverThread.join(10_000);
            if (serverThread.isAlive()) {
                serverThread.interrupt();
            }
        }
        if (engine != null) {
            engine.close();
        }
    }

    private void start(String code) throws Exception {
        startOnPort(code, freePort());
    }

    private void startOnPort(String code, int requestedPort) throws Exception {
        port = requestedPort;
        Path script = dir.resolve("server.php");
        Files.writeString(script, code.replace("__PORT__", Integer.toString(requestedPort)));
        engine = new PhpEngine(null);
        runtime = new BlitzServerRuntime(engine, engine.source(script), script.toString());
        engine.setServerHost(runtime);
        PhpRequest request = new PhpRequest();
        request.uri = script.toString();
        request.scriptFilename = script.toString();
        serverThread = new Thread(() -> {
            try {
                result.set(engine.execute(script, request));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "blitz-test-server");
        serverThread.setDaemon(true);
        serverThread.start();
        if (requestedPort == 0) {
            awaitBoundPort();
        } else {
            awaitPort();
        }
    }

    /** For {@code port = 0}: the runtime picks the port when the first worker binds. */
    private void awaitBoundPort() throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (failure.get() != null) {
                throw new AssertionError("server failed", failure.get());
            }
            PhpEngine.Result done = result.get();
            if (done != null) {
                fail("server exited before listening: " + done.bodyText() + done.errors());
            }
            int bound = runtime.port();
            if (bound > 0) {
                port = bound;
                awaitPort();
                return;
            }
            Thread.sleep(20);
        }
        fail("server did not bind a port");
    }

    private void awaitPort() throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (failure.get() != null) {
                throw new AssertionError("server failed", failure.get());
            }
            PhpEngine.Result done = result.get();
            if (done != null) {
                fail("server exited before listening: " + done.bodyText() + done.errors());
            }
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException e) {
                Thread.sleep(40);
            }
        }
        fail("server did not listen on port " + port);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ---------------------------------------------------------------- HTTP client

    private static final class Response {
        final int status;
        final String reason;
        final Map<String, List<String>> headers;
        final byte[] body;

        Response(int status, String reason, Map<String, List<String>> headers, byte[] body) {
            this.status = status;
            this.reason = reason;
            this.headers = headers;
            this.body = body;
        }

        String text() {
            return new String(body, StandardCharsets.ISO_8859_1);
        }

        String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        List<String> headerValues(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }
    }

    private final class Client implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Client() throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(8_000);
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        void write(String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        Response read(boolean head) throws IOException {
            String statusLine = readLine();
            if (statusLine == null) {
                return null;
            }
            String[] parts = statusLine.split(" ", 3);
            int status = Integer.parseInt(parts[1]);
            String reason = parts.length > 2 ? parts[2] : "";
            Map<String, List<String>> headers = new LinkedHashMap<>();
            while (true) {
                String line = readLine();
                if (line == null || line.isEmpty()) {
                    break;
                }
                int colon = line.indexOf(':');
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
            boolean noBody = status == 100 || head || status == 204 || status == 304;
            byte[] body;
            if (noBody) {
                body = new byte[0];
            } else if (header(headers, "transfer-encoding") != null
                    && header(headers, "transfer-encoding").toLowerCase(Locale.ROOT).contains("chunked")) {
                ByteArrayOutputStream chunked = new ByteArrayOutputStream();
                while (true) {
                    String sizeLine = readLine();
                    if (sizeLine == null) {
                        break;
                    }
                    int semi = sizeLine.indexOf(';');
                    int size = Integer.parseInt((semi < 0 ? sizeLine : sizeLine.substring(0, semi)).trim(), 16);
                    if (size == 0) {
                        readLine();
                        break;
                    }
                    byte[] chunk = readExactly(size);
                    chunked.write(chunk);
                    readLine();
                }
                body = chunked.toByteArray();
            } else if (header(headers, "content-length") != null) {
                body = readExactly(Integer.parseInt(header(headers, "content-length")));
            } else {
                body = in.readAllBytes();
            }
            return new Response(status, reason, headers, body);
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\n') {
                    break;
                }
                if (c != '\r') {
                    line.write(c);
                }
            }
            if (c < 0 && line.size() == 0) {
                return null;
            }
            return line.toString(StandardCharsets.ISO_8859_1);
        }

        private byte[] readExactly(int length) throws IOException {
            byte[] bytes = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(bytes, read, length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return read == length ? bytes : java.util.Arrays.copyOf(bytes, read);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static String header(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private Response send(String method, String path, Map<String, String> headers, String body) throws IOException {
        try (Client client = new Client()) {
            StringBuilder request = new StringBuilder();
            request.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            request.append("Host: localhost\r\n");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                request.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
            if (!body.isEmpty() || method.equals("POST")) {
                request.append("Content-Length: ")
                        .append(body.getBytes(StandardCharsets.ISO_8859_1).length).append("\r\n");
            }
            request.append("\r\n").append(body);
            client.write(request.toString());
            Response response = client.read(method.equals("HEAD"));
            assertNotNull(response, "no response");
            return response;
        }
    }

    private Response get(String path) throws IOException {
        return send("GET", path, Map.of(), "");
    }

    private Response sendRaw(String raw) throws IOException {
        try (Client client = new Client()) {
            client.write(raw);
            Response response = client.read(false);
            assertNotNull(response, "no response");
            return response;
        }
    }

    // ---------------------------------------------------------------- tests

    private static final String HELLO = """
            <?php
            $server = new Blitz\\Http\\Server('127.0.0.1', __PORT__);
            $server->set(['worker_num' => 1]);
            $server->on('request', function (Blitz\\Http\\Request $req, Blitz\\Http\\Response $res) {
                $res->header('Content-Type', 'text/plain');
                $res->end("Hello, World!\\n");
            });
            $server->start();
            """;

    @Test
    void helloHasOnlyTheHeadersTheHandlerAskedFor() throws Exception {
        start(HELLO);
        Response response = get("/");
        assertEquals(200, response.status);
        assertEquals("OK", response.reason);
        assertEquals("Hello, World!\n", response.text());
        assertEquals("text/plain", response.header("Content-Type"));
        assertEquals("14", response.header("Content-Length"));
        assertNull(response.header("Date"));
        assertNull(response.header("Server"));
        assertNull(response.header("X-Powered-By"));
        assertNull(response.header("Connection"));
    }

    private static final String API = """
            <?php
            $server = new Blitz\\Http\\Server('127.0.0.1', __PORT__);
            $server->set(['worker_num' => 1]);
            $server->on('request', function (Blitz\\Http\\Request $req, Blitz\\Http\\Response $res) {
                $path = $req->server['path_info'];
                switch ($path) {
                    case '/server':
                        $res->header('Content-Type', 'application/json');
                        $res->end(json_encode($req->server));
                        return;
                    case '/headers':
                        $res->end(json_encode($req->header));
                        return;
                    case '/get':
                        $res->end(json_encode($req->get));
                        return;
                    case '/post':
                        $res->end(json_encode($req->post));
                        return;
                    case '/cookie':
                        $res->end(json_encode($req->cookie));
                        return;
                    case '/raw':
                        $res->end($req->rawContent());
                        return;
                    case '/content':
                        $res->end($req->getContent());
                        return;
                    case '/method':
                        $res->end($req->getMethod());
                        return;
                    case '/status':
                        $res->status(418, "I'm a teapot");
                        $res->end("teapot\\n");
                        return;
                    case '/cookies':
                        $res->cookie('a', 'hello world');
                        $res->rawCookie('b', 'raw-value', 0, '/x');
                        $res->end("cookies\\n");
                        return;
                    case '/chunk':
                        $res->header('Content-Type', 'text/plain');
                        $res->write("one");
                        $res->write("two");
                        $res->end("three");
                        return;
                    case '/file':
                        $res->sendfile(__DIR__ . '/file.txt');
                        return;
                    case '/redirect':
                        $res->redirect('/hello');
                        return;
                    case '/writable':
                        $res->end($res->isWritable() ? 'yes' : 'no');
                        return;
                    case '/throw':
                        throw new RuntimeException('boom');
                    case '/fatal':
                        undefined_function_for_blitz_test();
                    case '/exit':
                        exit("bye");
                    case '/worker':
                        $res->end($server->getWorkerId() . '/' . $server->getWorkerNum());
                        return;
                    case '/hello':
                        $res->header('Content-Type', 'text/plain');
                        $res->end("Hello, World!\\n");
                        return;
                    default:
                        $res->status(404);
                        $res->end("not found\\n");
                }
            });
            $server->start();
            """;

    @Test
    void requestPropertiesAreSwooleShaped() throws Exception {
        Files.writeString(dir.resolve("file.txt"), "file body\n");
        start(API);

        Response server = send("POST", "/server?x=1", Map.of("X-Probe", "probe", "Cookie", "c=d"),
                "a=b");
        assertEquals(200, server.status);
        String serverText = server.text();
        assertTrue(serverText.contains("\"request_method\":\"POST\""), serverText);
        assertTrue(serverText.contains("\"request_uri\":\"\\/server?x=1\""), serverText);
        assertTrue(serverText.contains("\"path_info\":\"\\/server\""), serverText);
        assertTrue(serverText.contains("\"query_string\":\"x=1\""), serverText);
        assertTrue(serverText.contains("\"server_protocol\":\"HTTP\\/1.1\""), serverText);
        assertTrue(serverText.contains("\"server_software\":\"blitz\""), serverText);
        assertTrue(serverText.contains("\"http_x_probe\":\"probe\""), serverText);
        assertTrue(serverText.contains("\"content_type\":\"\""), serverText);

        Map<String, String> duplicateHeaders = new LinkedHashMap<>();
        duplicateHeaders.put("X-Probe", "yes");
        duplicateHeaders.put("x-probe", "again");
        Response headers = send("GET", "/headers", duplicateHeaders, "");
        assertEquals("{\"host\":\"localhost\",\"x-probe\":\"yes, again\"}", headers.text());

        assertEquals("{\"a\":\"1\",\"b\":\"two words\"}", send("GET", "/get?a=1&b=two+words", Map.of(), "").text());
        assertEquals("{\"x\":\"1\",\"y\":\"2\"}", send("POST", "/post",
                Map.of("Content-Type", "application/x-www-form-urlencoded"), "x=1&y=2").text());
        assertEquals("{\"c\":\"1\",\"d\":\"two words\"}",
                send("GET", "/cookie", Map.of("Cookie", "c=1; d=two%20words"), "").text());
        assertEquals("{\"a\":1}", send("POST", "/raw", Map.of("Content-Type", "application/json"),
                "{\"a\":1}").text());
        assertEquals("{\"a\":1}", send("POST", "/content", Map.of(), "{\"a\":1}").text());
        assertEquals("POST", send("POST", "/method", Map.of(), "x").text());

        Response status = get("/status");
        assertEquals(418, status.status);
        assertEquals("I'm a teapot", status.reason);
        assertEquals("teapot\n", status.text());

        Response cookies = get("/cookies");
        assertEquals(200, cookies.status);
        List<String> setCookies = cookies.headerValues("set-cookie");
        assertEquals(2, setCookies.size(), setCookies.toString());
        assertTrue(setCookies.get(0).startsWith("a=hello+world"), setCookies.get(0));
        assertTrue(setCookies.get(1).startsWith("b=raw-value; path=/x"), setCookies.get(1));

        Response chunked = get("/chunk");
        assertEquals("chunked", chunked.header("Transfer-Encoding"));
        assertEquals("onetwothree", chunked.text());

        assertEquals("file body\n", get("/file").text());

        Response redirect = get("/redirect");
        assertEquals(302, redirect.status);
        assertEquals("/hello", redirect.header("Location"));
        assertEquals("", redirect.text());

        assertEquals("yes", get("/writable").text());
        assertEquals("not found\n", get("/missing").text());
        assertEquals(404, get("/missing").status);
    }

    @Test
    void keepAlivePipeliningAndHead() throws Exception {
        start(HELLO);
        try (Client client = new Client()) {
            client.write("GET / HTTP/1.1\r\nHost: x\r\n\r\n"
                    + "GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            Response first = client.read(false);
            Response second = client.read(false);
            assertEquals(200, first.status);
            assertEquals(200, second.status);
            assertEquals("Hello, World!\n", first.text());
            assertEquals("Hello, World!\n", second.text());
            assertNull(first.header("Connection"));
            assertEquals("close", second.header("Connection"));
        }

        try (Client client = new Client()) {
            client.write("HEAD / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            Response head = client.read(true);
            assertEquals(200, head.status);
            assertEquals("14", head.header("Content-Length"));
            assertEquals("", head.text());
        }
    }

    @Test
    void expectContinueAndChunkedRequest() throws Exception {
        start(API);
        try (Client client = new Client()) {
            client.write("POST /raw HTTP/1.1\r\nHost: x\r\nContent-Length: 7\r\n"
                    + "Expect: 100-continue\r\n\r\n");
            Response interim = client.read(false);
            assertEquals(100, interim.status);
            client.write("abc=123");
            Response response = client.read(false);
            assertEquals(200, response.status);
            assertEquals("abc=123", response.text());
        }

        try (Client client = new Client()) {
            client.write("POST /raw HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n");
            Response response = client.read(false);
            assertEquals(200, response.status);
            assertEquals("Wikipedia", response.text());
        }
    }

    @Test
    void malformedRequestsAre400() throws Exception {
        start(HELLO);
        assertEquals(400, sendRaw("GARBAGE\r\n\r\n").status);
        assertEquals(400, sendRaw("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: nope\r\n\r\n").status);
        assertEquals(400, sendRaw("GET / HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n").status);
        assertEquals(505, sendRaw("GET / HTTP/2.0\r\nHost: x\r\n\r\n").status);
    }

    @Test
    void aThrowingHandlerIs500AndTheWorkerKeepsServing() throws Exception {
        start(API);
        try (Client client = new Client()) {
            client.write("GET /throw HTTP/1.1\r\nHost: x\r\n\r\n");
            Response failed = client.read(false);
            assertEquals(500, failed.status);
            assertFalse(failed.text().contains("boom"), "the exception text leaked to the client");

            client.write("GET /hello HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            Response next = client.read(false);
            assertEquals(200, next.status);
            assertEquals("Hello, World!\n", next.text());
        }
        try (Client client = new Client()) {
            client.write("GET /fatal HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals(500, client.read(false).status);
            client.write("GET /hello HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            assertEquals(200, client.read(false).status);
        }
    }

    @Test
    void exitEndsTheResponseAndTheWorkerKeepsServing() throws Exception {
        start(API);
        try (Client client = new Client()) {
            client.write("GET /exit HTTP/1.1\r\nHost: x\r\n\r\n");
            assertEquals(200, client.read(false).status);
            client.write("GET /hello HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
            assertEquals(200, client.read(false).status);
        }
    }

    @Test
    void severalWorkersRunTheScriptAndServe() throws Exception {
        Path starts = dir.resolve("starts.log");
        String code = """
                <?php
                $server = new Blitz\\Http\\Server('127.0.0.1', __PORT__);
                $server->set(['worker_num' => 3]);
                $server->on('workerStart', function ($server, $id) {
                    file_put_contents(__DIR__ . '/starts.log', $id . "\\n", FILE_APPEND);
                });
                $server->on('request', function (Blitz\\Http\\Request $req, Blitz\\Http\\Response $res) use ($server) {
                    $res->end($server->getWorkerId() . '/' . $server->getWorkerNum());
                });
                $server->start();
                """;
        Files.deleteIfExists(starts);
        start(code);
        List<String> ids = Files.readAllLines(starts);
        ids.sort(String::compareTo);
        assertEquals(List.of("0", "1", "2"), ids);

        boolean sawNonZero = false;
        for (int i = 0; i < 40; i++) {
            String body = get("/").text();
            String[] parts = body.split("/");
            assertEquals(3, Integer.parseInt(parts[1]), body);
            int id = Integer.parseInt(parts[0]);
            assertTrue(id >= 0 && id < 3, body);
            sawNonZero |= id != 0;
        }
        assertTrue(sawNonZero, "every request was served by worker 0");
    }

    @Test
    void reusePortSpreadsConnectionsAcrossWorkers() throws Exception {
        assumeTrue(BlitzServerCore.reusePortSupported(), "SO_REUSEPORT is not available");
        String code = """
                <?php
                $server = new Blitz\\Http\\Server('127.0.0.1', __PORT__);
                $server->set(['worker_num' => 3]);
                $server->on('request', function (Blitz\\Http\\Request $req, Blitz\\Http\\Response $res) use ($server) {
                    $res->end((string) $server->getWorkerId());
                });
                $server->start();
                """;
        start(code);
        Map<Integer, Integer> connections = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            int id = Integer.parseInt(get("/").text());
            assertTrue(id >= 0 && id < 3, "worker id " + id);
            connections.merge(id, 1, Integer::sum);
        }
        assertEquals(3, connections.size(), "connections per worker: " + connections);
        for (Map.Entry<Integer, Integer> entry : connections.entrySet()) {
            assertTrue(entry.getValue() >= 2,
                    "worker " + entry.getKey() + " served " + entry.getValue() + " connections");
        }
    }

    @Test
    void portZeroWithSeveralWorkersBindsOnePortTheyAllServe() throws Exception {
        String code = """
                <?php
                $server = new Blitz\\Http\\Server('127.0.0.1', 0);
                $server->set(['worker_num' => 3]);
                $server->on('request', function (Blitz\\Http\\Request $req, Blitz\\Http\\Response $res) use ($server) {
                    $res->end($server->getWorkerId() . '/' . $server->getPort());
                });
                $server->start();
                """;
        startOnPort(code, 0);
        assertTrue(port > 0, "no port was bound");
        java.util.Set<Integer> workers = new java.util.HashSet<>();
        for (int i = 0; i < 30; i++) {
            String[] parts = get("/").text().split("/");
            assertEquals(Integer.toString(port), parts[1], "a worker bound a different port");
            workers.add(Integer.parseInt(parts[0]));
        }
        assertTrue(workers.size() > 1, "every connection was served by one worker: " + workers);
    }

    @Test
    void notFoundAndForbiddenAreNotSpecialCases() throws Exception {
        start(API);
        assertEquals("not found\n", sendRaw("GET /no-such-route HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").text());
    }
}
