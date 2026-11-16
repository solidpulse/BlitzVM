package dev.phpruntime.host;

import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.runtime.PhpQuery;
import dev.phpruntime.runtime.PhpStrings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Transport-independent request handling: maps a URL to a file under the document
 * root, runs .php files through the engine with superglobals filled in, and serves
 * other files as static content. HTTP servers (Netty, JDK) only adapt to this.
 */
public final class PhpDispatcher {

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

    private static final Map<String, String> MIME = Map.of(
            "html", "text/html; charset=UTF-8", "css", "text/css", "js", "application/javascript",
            "json", "application/json", "png", "image/png", "jpg", "image/jpeg", "svg", "image/svg+xml",
            "txt", "text/plain; charset=UTF-8", "ico", "image/x-icon");

    private final Path docRoot;
    private final PhpEngine engine;

    public PhpDispatcher(Path docRoot, PhpEngine engine) {
        this.docRoot = docRoot.toAbsolutePath().normalize();
        this.engine = engine;
    }

    public Path docRoot() {
        return docRoot;
    }

    /** Blocking: runs PHP. Transports must call this off their I/O threads. */
    public Response handle(Request req) {
        try {
            String path = PhpStrings.toJava(PhpQuery.urldecode(req.rawPath(), false));
            if (path.equals("/__scheduler")) {
                String body = engine.scheduler() == null ? "no scheduler\n" : engine.scheduler().stats() + "\n";
                return Response.text(200, body);
            }
            Path file = docRoot.resolve(path.substring(1)).normalize();
            if (!file.startsWith(docRoot)) {
                return Response.text(403, "Forbidden\n");
            }
            if (Files.isDirectory(file)) {
                file = file.resolve("index.php");
            }
            if (!Files.isRegularFile(file)) {
                return Response.text(404, "Not Found: " + path + "\n");
            }
            if (!isScript(file)) {
                String name = file.getFileName().toString();
                List<String[]> h = new ArrayList<>();
                h.add(new String[]{"Content-Type", MIME.getOrDefault(name.substring(name.lastIndexOf('.') + 1), "application/octet-stream")});
                return new Response(200, h, Files.readAllBytes(file));
            }
            PhpRequest request = buildRequest(req, file);
            PhpEngine.Result result = engine.execute(file, request);
            if (!result.errors().isEmpty()) {
                System.err.print(result.errors());
            }
            List<String[]> headers = new ArrayList<>(request.responseHeaders);
            if (headers.stream().noneMatch(h -> h[0].equalsIgnoreCase("Content-Type"))) {
                headers.add(new String[]{"Content-Type", "text/html; charset=UTF-8"});
            }
            headers.add(new String[]{"X-Powered-By", "php-truffle"});
            return new Response(result.status(), headers, result.body());
        } catch (IOException | RuntimeException e) {
            return Response.text(500, "Internal Server Error: " + e + "\n");
        }
    }

    /** .php files run as PHP; .js files are Node-style request handlers (module.exports = (req, res) => ...). */
    private static boolean isScript(Path file) {
        String name = file.toString();
        return name.endsWith(".php") || name.endsWith(".js");
    }

    private PhpRequest buildRequest(Request req, Path file) {
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
        for (Map.Entry<String, String> h : req.headers().entrySet()) {
            if (h.getKey().equalsIgnoreCase("Content-Type")) {
                contentType = h.getValue();
            }
        }
        if ("POST".equals(r.method) && contentType != null && contentType.startsWith("application/x-www-form-urlencoded")) {
            parseQuery(r.body, r.post);
        }
        String script = "/" + docRoot.relativize(file).toString().replace('\\', '/');
        Map<String, String> s = r.server;
        s.put("REQUEST_METHOD", r.method);
        s.put("REQUEST_URI", r.uri);
        s.put("QUERY_STRING", r.queryString);
        s.put("SCRIPT_NAME", PhpStrings.fromJava(script));
        s.put("PHP_SELF", PhpStrings.fromJava(script));
        s.put("SCRIPT_FILENAME", PhpStrings.fromJava(r.scriptFilename));
        s.put("DOCUMENT_ROOT", PhpStrings.fromJava(docRoot.toString()));
        s.put("SERVER_PROTOCOL", req.protocol());
        s.put("SERVER_SOFTWARE", "php-truffle");
        s.put("SERVER_NAME", PhpStrings.fromJava(req.serverHost()));
        s.put("SERVER_PORT", Integer.toString(req.serverPort()));
        s.put("REMOTE_ADDR", r.remoteAddr);
        s.put("REQUEST_TIME", Long.toString(System.currentTimeMillis() / 1000));
        for (Map.Entry<String, String> h : req.headers().entrySet()) {
            s.put("HTTP_" + PhpStrings.upper(h.getKey()).replace('-', '_'), h.getValue());
            r.headers.put(h.getKey(), h.getValue());
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
