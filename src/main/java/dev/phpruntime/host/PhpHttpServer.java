package dev.phpruntime.host;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.phpruntime.runtime.PhpRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small php-fpm-like front end: maps URLs onto .php files under a document root,
 * fills in the superglobals and returns the script's output and headers.
 *
 * <p>Each connection is handled on a pool thread, but how many requests execute PHP at
 * the same time is decided by the scheduler's CPU slots, not by the pool size.
 */
public final class PhpHttpServer implements AutoCloseable {

    private static final Map<String, String> MIME = Map.of(
            "html", "text/html; charset=UTF-8", "css", "text/css", "js", "application/javascript",
            "json", "application/json", "png", "image/png", "jpg", "image/jpeg", "svg", "image/svg+xml",
            "txt", "text/plain; charset=UTF-8", "ico", "image/x-icon");

    private final Path docRoot;
    private final PhpEngine engine;
    private final HttpServer server;
    private final ExecutorService workers;

    public PhpHttpServer(Path docRoot, int port, PhpEngine engine, int maxConnections) throws IOException {
        this.docRoot = docRoot.toAbsolutePath().normalize();
        this.engine = engine;
        this.server = HttpServer.create(new InetSocketAddress(port), 128);
        this.workers = Executors.newFixedThreadPool(maxConnections, r -> {
            Thread t = new Thread(r, "php-http-worker");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(workers);
        server.createContext("/", this::handle);
        server.createContext("/__scheduler", this::schedulerStats);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void schedulerStats(HttpExchange ex) throws IOException {
        String body = engine.scheduler() == null ? "no scheduler\n" : engine.scheduler().stats() + "\n";
        send(ex, 200, "text/plain; charset=UTF-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = URLDecoder.decode(ex.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
            Path file = docRoot.resolve(path.substring(1)).normalize();
            if (!file.startsWith(docRoot)) {
                send(ex, 403, "text/plain", "Forbidden\n".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (Files.isDirectory(file)) {
                file = file.resolve("index.php");
            }
            if (!Files.isRegularFile(file)) {
                send(ex, 404, "text/plain", ("Not Found: " + path + "\n").getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!file.toString().endsWith(".php")) {
                String name = file.getFileName().toString();
                String ext = name.substring(name.lastIndexOf('.') + 1);
                send(ex, 200, MIME.getOrDefault(ext, "application/octet-stream"), Files.readAllBytes(file));
                return;
            }
            PhpRequest request = buildRequest(ex, file);
            PhpEngine.Result result = engine.execute(file, request);
            if (!result.errors().isEmpty()) {
                System.err.print(result.errors());
            }
            for (String[] h : request.responseHeaders) {
                ex.getResponseHeaders().add(h[0], h[1]);
            }
            if (!ex.getResponseHeaders().containsKey("Content-Type")) {
                ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            }
            ex.getResponseHeaders().set("X-Powered-By", "php-truffle");
            sendRaw(ex, result.status(), result.body());
        } catch (RuntimeException e) {
            send(ex, 500, "text/plain", ("Internal Server Error: " + e + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private PhpRequest buildRequest(HttpExchange ex, Path file) throws IOException {
        PhpRequest r = new PhpRequest();
        r.method = ex.getRequestMethod();
        r.uri = ex.getRequestURI().toString();
        r.queryString = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
        r.scriptFilename = file.toString();
        r.remoteAddr = ex.getRemoteAddress().getAddress().getHostAddress();
        parseQuery(r.queryString, r.get);
        try (InputStream in = ex.getRequestBody()) {
            r.body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String contentType = ex.getRequestHeaders().getFirst("Content-Type");
        if ("POST".equals(r.method) && contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
            parseQuery(r.body, r.post);
        }
        String script = "/" + docRoot.relativize(file).toString().replace('\\', '/');
        Map<String, String> s = r.server;
        s.put("REQUEST_METHOD", r.method);
        s.put("REQUEST_URI", r.uri);
        s.put("QUERY_STRING", r.queryString);
        s.put("SCRIPT_NAME", script);
        s.put("PHP_SELF", script);
        s.put("SCRIPT_FILENAME", r.scriptFilename);
        s.put("DOCUMENT_ROOT", docRoot.toString());
        s.put("SERVER_PROTOCOL", ex.getProtocol());
        s.put("SERVER_SOFTWARE", "php-truffle");
        s.put("SERVER_NAME", ex.getLocalAddress().getHostName());
        s.put("SERVER_PORT", Integer.toString(ex.getLocalAddress().getPort()));
        s.put("REMOTE_ADDR", r.remoteAddr);
        s.put("REQUEST_TIME", Long.toString(System.currentTimeMillis() / 1000));
        for (Map.Entry<String, List<String>> h : ex.getRequestHeaders().entrySet()) {
            String key = "HTTP_" + h.getKey().toUpperCase().replace('-', '_');
            s.put(key, String.join(", ", h.getValue()));
            r.headers.put(h.getKey(), String.join(", ", h.getValue()));
        }
        return r;
    }

    static void parseQuery(String query, Map<String, String> into) {
        if (query == null || query.isEmpty()) {
            return;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            into.put(k, v);
        }
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        sendRaw(ex, status, body);
    }

    private static void sendRaw(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }
}
