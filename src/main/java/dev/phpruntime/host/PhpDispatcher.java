package dev.phpruntime.host;

import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.runtime.PhpQuery;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.snapshot.SnapshotRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transport-independent request handling: maps a URL to a file under the document
 * root, runs .php files through the engine with superglobals filled in, and serves
 * other files as static content. HTTP servers (Netty, JDK) only adapt to this.
 */
public final class PhpDispatcher implements AutoCloseable {

    /** What a transport hands in. Header names keep their original case. */
    public record Request(String method, String rawPath, String rawQuery, String protocol,
                          Map<String, String> headers, byte[] body,
                          String remoteAddr, String serverHost, int serverPort) {
    }

    public record Response(int status, List<String[]> headers, byte[] body) {
        static Response text(int status, String body) {
            List<String[]> h = new ArrayList<>();
            h.add(new String[]{"Content-Type", "text/plain; charset=UTF-8"});
            return new Response(status, h, PhpStrings.bytes(PhpStrings.fromJava(body)));
        }
    }

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html", "text/html; charset=UTF-8"), Map.entry("htm", "text/html; charset=UTF-8"),
            Map.entry("css", "text/css; charset=UTF-8"), Map.entry("js", "text/javascript; charset=UTF-8"),
            Map.entry("mjs", "text/javascript; charset=UTF-8"), Map.entry("map", "application/json"),
            Map.entry("json", "application/json"), Map.entry("xml", "application/xml"),
            Map.entry("txt", "text/plain; charset=UTF-8"), Map.entry("csv", "text/csv; charset=UTF-8"),
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("avif", "image/avif"),
            Map.entry("svg", "image/svg+xml"), Map.entry("ico", "image/x-icon"), Map.entry("bmp", "image/bmp"),
            Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"), Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"), Map.entry("eot", "application/vnd.ms-fontobject"),
            Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"), Map.entry("mp3", "audio/mpeg"),
            Map.entry("ogg", "audio/ogg"), Map.entry("wav", "audio/wav"), Map.entry("pdf", "application/pdf"),
            Map.entry("zip", "application/zip"), Map.entry("gz", "application/gzip"), Map.entry("wasm", "application/wasm"));

    /** What a request path resolved to; immutable so it can be shared between worker threads. */
    private enum RouteKind { SCRIPT, STATIC, REDIRECT, FORBIDDEN, NOT_FOUND }

    private record Route(RouteKind kind, Path file, String pathInfo, long checkedAtNanos) {
        static Route of(RouteKind kind, Path file, String pathInfo, long now) {
            return new Route(kind, file, pathInfo, now);
        }
    }

    /** Bounded like the route cache: sites send a small set of header names, not arbitrary ones. */
    static final int CGI_NAME_CACHE_LIMIT = 256;
    private static final int DEFAULT_ROUTE_CACHE_LIMIT = 10_000;
    private static final long DEFAULT_ROUTE_REVALIDATE_NANOS = 2_000_000_000L;

    private final Path docRoot;
    private final PhpEngine engine;
    private final SnapshotServer snapshots;
    private final WorkerPool workers;
    /** Resolved request paths, revalidated on the file system at most every {@link #routeRevalidateNanos}. */
    private final ConcurrentHashMap<String, Route> routes = new ConcurrentHashMap<>();
    /** Original request header name to its CGI server name, e.g. User-Agent to HTTP_USER_AGENT. */
    private final ConcurrentHashMap<String, String> cgiNames = new ConcurrentHashMap<>();
    private volatile long routeRevalidateNanos = DEFAULT_ROUTE_REVALIDATE_NANOS;
    private volatile int routeCacheLimit = DEFAULT_ROUTE_CACHE_LIMIT;

    public PhpDispatcher(Path docRoot, PhpEngine engine) {
        this(docRoot, engine, null);
    }

    /** With a profile, eligible requests are served from boot snapshots; without, nothing changes. */
    public PhpDispatcher(Path docRoot, PhpEngine engine, SnapshotProfile profile) {
        this(docRoot, engine, profile, null);
    }

    /** With a worker pool, PHP routes are handed to a long-lived worker instead of a fresh run. */
    public PhpDispatcher(Path docRoot, PhpEngine engine, SnapshotProfile profile, WorkerPool workers) {
        this.docRoot = docRoot.toAbsolutePath().normalize();
        this.engine = engine;
        this.workers = workers;
        try {
            this.snapshots = profile == null ? null : new SnapshotServer(this.docRoot, engine, profile);
        } catch (IOException e) {
            throw new IllegalArgumentException("snapshot profile: " + e.getMessage(), e);
        }
    }

    public Path docRoot() {
        return docRoot;
    }

    public SnapshotServer snapshots() {
        return snapshots;
    }

    @Override
    public void close() {
        if (snapshots != null) {
            snapshots.close();
        }
    }

    /** Blocking: runs PHP. Transports must call this off their I/O threads. */
    public Response handle(Request req) {
        try {
            String path = PhpStrings.toJava(PhpQuery.urldecode(req.rawPath(), false));
            if (path.equals("/__scheduler")) {
                String body = engine.scheduler() == null ? "no scheduler\n" : engine.scheduler().stats() + "\n";
                return Response.text(200, body);
            }
            Route route = route(path);
            switch (route.kind()) {
                case FORBIDDEN -> {
                    return Response.text(403, "Forbidden\n");
                }
                case NOT_FOUND -> {
                    return Response.text(404, "Not Found: " + path + "\n");
                }
                case REDIRECT -> {
                    List<String[]> h = new ArrayList<>();
                    String q = req.rawQuery() == null || req.rawQuery().isEmpty() ? "" : "?" + req.rawQuery();
                    h.add(new String[]{"Location", req.rawPath() + "/" + q});
                    return new Response(308, h, new byte[0]);
                }
                case STATIC -> {
                    return serveStatic(route.file(), req.method());
                }
                case SCRIPT -> {
                    // runs PHP below
                }
            }
            Path file = route.file();
            String pathInfo = route.pathInfo();
            if (workers != null && file.toString().endsWith(".php")) {
                PhpRequest request = buildRequest(req, workers.workerScript(), null);
                workers.prepare(request);
                PhpEngine.Result result = workers.execute(request);
                if (!result.errors().isEmpty()) {
                    System.err.print(result.errors());
                }
                return response(request, result);
            }
            PhpRequest request = buildRequest(req, file, pathInfo);
            SnapshotServer.Plan plan = null;
            if (snapshots != null) {
                try {
                    plan = snapshots.plan(req, request, file);
                } catch (RuntimeException e) {
                    // A snapshot problem must never fail a request: run it as today.
                    System.err.println("blitz snapshot: " + e);
                }
            }
            SnapshotRun run = plan == null ? null : plan.run();
            PhpEngine.Result result = engine.execute(file, request, run);
            if (!result.errors().isEmpty()) {
                System.err.print(result.errors());
            }
            if (plan != null) {
                if (snapshots.reportEnabled()) {
                    request.setHeader("X-Blitz-Snapshot", plan.outcome().label(), true);
                }
                if (run != null && snapshots.verifyEnabled()) {
                    snapshots.verify(request, file, result, run);
                }
            }
            return response(request, result);
        } catch (IOException | RuntimeException e) {
            return Response.text(500, "Internal Server Error: " + e + "\n");
        }
    }

    private static Response response(PhpRequest request, PhpEngine.Result result) {
        List<String[]> headers = new ArrayList<>(request.responseHeaders);
        if (headers.stream().noneMatch(h -> h[0].equalsIgnoreCase("Content-Type"))) {
            headers.add(new String[]{"Content-Type", "text/html; charset=UTF-8"});
        }
        headers.add(new String[]{"X-Powered-By", "php-truffle"});
        return new Response(result.status(), headers, result.body());
    }

    /**
     * The cached resolution for a request path, revalidated against the file system at most
     * every {@link #routeRevalidateNanos}. The cache is bounded so front-controller sites,
     * where every distinct URL resolves to the same index.php, cannot grow it without limit.
     */
    private Route route(String path) {
        Route cached = routes.get(path);
        long now = System.nanoTime();
        if (cached != null && now - cached.checkedAtNanos() < routeRevalidateNanos) {
            return cached;
        }
        Route fresh = resolveRoute(path);
        if (routes.size() >= routeCacheLimit) {
            routes.clear();
        }
        routes.put(path, fresh);
        return fresh;
    }

    /**
     * Routing as FrankenPHP's php_server (try_files {path} {path}/index.php index.php,
     * split_path .php): no configuration needed for front controllers like WordPress.
     */
    private Route resolveRoute(String path) {
        long now = System.nanoTime();
        Path file = docRoot.resolve(path.substring(1)).normalize();
        if (!file.startsWith(docRoot)) {
            return Route.of(RouteKind.FORBIDDEN, null, null, now);
        }
        String pathInfo = null;
        if (Files.isDirectory(file)) {
            Path index = file.resolve("index.php");
            if (Files.isRegularFile(index) && !path.endsWith("/")) {
                return Route.of(RouteKind.REDIRECT, file, null, now);
            }
            file = Files.isRegularFile(index) ? index : docRoot.resolve("index.php");
        } else if (!Files.isRegularFile(file)) {
            int split = path.indexOf(".php/");
            Path script = split < 0 ? null : docRoot.resolve(path.substring(1, split + 4)).normalize();
            if (script != null && script.startsWith(docRoot) && Files.isRegularFile(script)) {
                file = script;
                pathInfo = path.substring(split + 4);
            } else {
                file = docRoot.resolve("index.php");
            }
        }
        if (!Files.isRegularFile(file)) {
            return Route.of(RouteKind.NOT_FOUND, null, null, now);
        }
        return Route.of(isScript(file) ? RouteKind.SCRIPT : RouteKind.STATIC, file, pathInfo, now);
    }

    private static Response serveStatic(Path file, String method) throws IOException {
        String name = file.getFileName().toString();
        String ext = PhpStrings.lower(name.substring(name.lastIndexOf('.') + 1));
        List<String[]> h = new ArrayList<>();
        h.add(new String[]{"Content-Type", MIME.getOrDefault(ext, "application/octet-stream")});
        h.add(new String[]{"Last-Modified", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Files.getLastModifiedTime(file).toInstant().atZone(java.time.ZoneOffset.UTC))});
        return new Response(200, h, "HEAD".equals(method) ? new byte[0] : Files.readAllBytes(file));
    }

    /** Test hook: revalidate route entries this often instead of every two seconds. */
    void setRouteRevalidateMillis(long millis) {
        routeRevalidateNanos = millis * 1_000_000L;
    }

    int routeCacheSize() {
        return routes.size();
    }

    /** Test hook: a smaller bound than the default 10,000, to exercise eviction cheaply. */
    void setRouteCacheLimit(int limit) {
        routeCacheLimit = limit;
    }

    int routeCacheLimit() {
        return routeCacheLimit;
    }

    int cgiNameCacheSize() {
        return cgiNames.size();
    }

    /** .php files run as PHP; .js files are Node-style request handlers (module.exports = (req, res) => ...). */
    private static boolean isScript(Path file) {
        String name = file.toString();
        return name.endsWith(".php") || name.endsWith(".js");
    }

    private PhpRequest buildRequest(Request req, Path file, String pathInfo) {
        PhpRequest r = new PhpRequest();
        r.http = true;
        r.method = req.method();
        r.queryString = req.rawQuery() == null ? "" : req.rawQuery();
        r.uri = req.rawPath() + (r.queryString.isEmpty() ? "" : "?" + r.queryString);
        r.scriptFilename = file.toString();
        r.remoteAddr = req.remoteAddr();
        parseQuery(r.queryString, r.get);
        r.body = PhpStrings.of(req.body());
        String contentType = null;
        String script = "/" + docRoot.relativize(file).toString().replace('\\', '/');
        Map<String, String> s = r.server;
        s.put("REQUEST_METHOD", r.method);
        s.put("REQUEST_URI", r.uri);
        s.put("QUERY_STRING", r.queryString);
        s.put("SCRIPT_NAME", PhpStrings.fromJava(script));
        s.put("PHP_SELF", PhpStrings.fromJava(pathInfo == null ? script : script + pathInfo));
        if (pathInfo != null) {
            s.put("PATH_INFO", PhpStrings.fromJava(pathInfo));
        }
        s.put("GATEWAY_INTERFACE", "CGI/1.1");
        s.put("REQUEST_SCHEME", "http");
        s.put("REQUEST_TIME_FLOAT", Double.toString(System.currentTimeMillis() / 1000.0));
        s.put("SCRIPT_FILENAME", PhpStrings.fromJava(r.scriptFilename));
        s.put("DOCUMENT_ROOT", PhpStrings.fromJava(docRoot.toString()));
        s.put("SERVER_PROTOCOL", req.protocol());
        s.put("SERVER_SOFTWARE", "php-truffle");
        s.put("SERVER_NAME", PhpStrings.fromJava(req.serverHost()));
        s.put("SERVER_PORT", Integer.toString(req.serverPort()));
        s.put("REMOTE_ADDR", r.remoteAddr);
        s.put("REQUEST_TIME", Long.toString(System.currentTimeMillis() / 1000));
        for (Map.Entry<String, String> h : req.headers().entrySet()) {
            if (h.getKey().equalsIgnoreCase("Content-Type")) {
                contentType = h.getValue();
            }
            s.put(cgiName(h.getKey()), h.getValue());
            r.headers.put(h.getKey(), h.getValue());
        }
        if ("POST".equals(r.method) && contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
            parseQuery(r.body, r.post);
        }
        // The shared host also serves JavaScript. Keep that language's existing text
        // request contract without changing its runtime/parser or PHP's byte fields.
        if (file.toString().endsWith(".js")) {
            r.get.replaceAll((key, value) -> PhpStrings.toJava(value));
            r.post.replaceAll((key, value) -> PhpStrings.toJava(value));
            r.body = PhpStrings.toJava(r.body);
        }
        return r;
    }

    /**
     * The CGI name for a request header ({@code HTTP_USER_AGENT}, or {@code CONTENT_TYPE} and
     * {@code CONTENT_LENGTH} without the prefix), cached so a repeated name costs one lookup.
     */
    private String cgiName(String name) {
        String cached = cgiNames.get(name);
        if (cached != null) {
            return cached;
        }
        String upper = PhpStrings.upper(name).replace('-', '_');
        String cgi = upper.equals("CONTENT_TYPE") || upper.equals("CONTENT_LENGTH") ? upper : "HTTP_" + upper;
        if (cgiNames.size() < CGI_NAME_CACHE_LIMIT) {
            cgiNames.put(name, cgi);
        }
        return cgi;
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
            String k = PhpQuery.urldecode(eq < 0 ? pair : pair.substring(0, eq), true);
            String v = eq < 0 ? "" : PhpQuery.urldecode(pair.substring(eq + 1), true);
            into.put(k, v);
        }
    }
}
