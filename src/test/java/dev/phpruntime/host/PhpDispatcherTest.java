package dev.phpruntime.host;

import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.sched.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Request routing through {@link PhpDispatcher}: the bounded route cache, its revalidation,
 * path-info and directory routing, traversal refusal, and CGI header names.
 */
class PhpDispatcherTest {

    @TempDir
    Path dir;

    private Scheduler scheduler;
    private PhpEngine engine;
    private PhpDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        scheduler = new Scheduler(2, 10, 0);
        engine = new PhpEngine(scheduler, 2);
        dispatcher = new PhpDispatcher(dir, engine);
    }

    @AfterEach
    void tearDown() {
        dispatcher.close();
        engine.close();
        scheduler.close();
    }

    private void write(String name, String content) throws IOException {
        Path path = dir.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private PhpDispatcher.Response get(String path) {
        return get(path, null, Map.of());
    }

    private PhpDispatcher.Response get(String path, Map<String, String> headers) {
        return get(path, null, headers);
    }

    private PhpDispatcher.Response get(String path, String query, Map<String, String> headers) {
        return send("GET", path, query, headers);
    }

    private PhpDispatcher.Response send(String method, String path, String query, Map<String, String> headers) {
        return dispatcher.handle(new PhpDispatcher.Request(method, path, query, "HTTP/1.1", headers,
                new byte[0], "127.0.0.1", "localhost", 80));
    }

    private static String text(PhpDispatcher.Response response) {
        return PhpStrings.toJava(PhpStrings.of(response.body()));
    }

    private static String header(PhpDispatcher.Response response, String name) {
        for (String[] h : response.headers()) {
            if (h[0].equalsIgnoreCase(name)) {
                return h[1];
            }
        }
        return null;
    }

    @Test
    void repeatedRequestsReuseOneResolution() throws Exception {
        write("hello.php", "<?php echo 'hello';");
        assertEquals(10_000, dispatcher.routeCacheLimit());

        assertEquals(200, get("/hello.php").status());
        assertEquals("hello", text(get("/hello.php")));
        assertEquals("hello", text(get("/hello.php")));
        assertEquals(1, dispatcher.routeCacheSize(), "each request resolved the path again");
    }

    @Test
    void aCreatedFileIsNoticedAfterRevalidation() throws Exception {
        write("index.php", "<?php echo 'front';");
        dispatcher.setRouteRevalidateMillis(60_000);

        assertEquals("front", text(get("/new.php")));
        write("new.php", "<?php echo 'new';");
        assertEquals("front", text(get("/new.php")),
                "the route cache did not keep the earlier fallback");

        dispatcher.setRouteRevalidateMillis(0);
        assertEquals("new", text(get("/new.php")),
                "revalidation did not pick up the created file");
    }

    @Test
    void aDeletedFileIsNoticedAfterRevalidation() throws Exception {
        write("index.php", "<?php echo 'front';");
        write("gone.php", "<?php echo 'gone';");
        dispatcher.setRouteRevalidateMillis(60_000);

        assertEquals("gone", text(get("/gone.php")));
        Files.delete(dir.resolve("gone.php"));
        assertEquals(500, get("/gone.php").status(),
                "a stale route served a file that no longer exists");

        dispatcher.setRouteRevalidateMillis(0);
        assertEquals(200, get("/gone.php").status());
        assertEquals("front", text(get("/gone.php")),
                "the deleted file still had its own route after revalidation");
    }

    @Test
    void aFileReplacedByADirectoryIsNoticedAfterRevalidation() throws Exception {
        write("index.php", "<?php echo 'front';");
        write("thing", "static v1");
        dispatcher.setRouteRevalidateMillis(60_000);

        PhpDispatcher.Response first = get("/thing");
        assertEquals(200, first.status());
        assertEquals("static v1", text(first));

        Files.delete(dir.resolve("thing"));
        Files.createDirectory(dir.resolve("thing"));
        write("thing/index.php", "<?php echo 'dir';");
        assertEquals(500, get("/thing").status(),
                "a stale static route served a file that is now a directory");

        dispatcher.setRouteRevalidateMillis(0);
        PhpDispatcher.Response redirect = get("/thing");
        assertEquals(308, redirect.status());
        assertEquals("/thing/", header(redirect, "Location"));
        assertEquals("dir", text(get("/thing/")));
    }

    @Test
    void pathInfoIsSplitFromTheScript() throws Exception {
        write("info.php", """
                <?php
                echo $_SERVER['PATH_INFO'], "\\n";
                echo $_SERVER['PHP_SELF'], "\\n";
                echo $_SERVER['SCRIPT_NAME'], "\\n";
                """);

        assertEquals("/extra/path\n/info.php/extra/path\n/info.php\n",
                text(get("/info.php/extra/path")));
        assertEquals("/extra/path\n/info.php/extra/path\n/info.php\n",
                text(get("/info.php/extra/path")));
        assertEquals(1, dispatcher.routeCacheSize());
    }

    @Test
    void directoriesRedirectAndUseTheirIndexOrTheFrontController() throws Exception {
        write("index.php", "<?php echo 'front';");
        write("sub/index.php", "<?php echo 'sub-index';");
        Files.createDirectories(dir.resolve("bare"));

        PhpDispatcher.Response redirect = get("/sub", "a=1", Map.of());
        assertEquals(308, redirect.status());
        assertEquals("/sub/?a=1", header(redirect, "Location"));

        assertEquals("/sub/", header(get("/sub"), "Location"));
        assertEquals("sub-index", text(get("/sub/")));
        assertEquals("sub-index", text(get("/sub/index.php")));

        // A directory without index.php falls back to the document root's index.php, with or
        // without a trailing slash, and must not redirect.
        assertEquals("front", text(get("/bare")));
        assertEquals("front", text(get("/bare/")));
    }

    @Test
    void traversalOutsideTheDocumentRootIsForbidden() throws Exception {
        write("index.php", "<?php echo 'front';");

        assertEquals(403, get("/../secret").status());
        assertEquals("Forbidden\n", text(get("/../secret")));
        assertEquals(403, get("/a/../../secret").status());
        assertEquals(403, get("/%2e%2e/secret").status());
        assertEquals(403, get("/%2e%2e%2fsecret").status());
    }

    @Test
    void aFrontControllerStaysBounded() throws Exception {
        write("index.php", "<?php echo 'front';");
        dispatcher.setRouteCacheLimit(64);
        dispatcher.setRouteRevalidateMillis(60_000);

        for (int i = 0; i < 200; i++) {
            PhpDispatcher.Response response = get("/page/" + i);
            assertEquals(200, response.status(), "page " + i);
            assertEquals("front", text(response), "page " + i);
        }
        assertTrue(dispatcher.routeCacheSize() <= 64,
                "route cache grew to " + dispatcher.routeCacheSize() + " entries");

        // Clearing the full cache must not change routing.
        assertEquals("front", text(get("/page/0")));
    }

    @Test
    void headerNamesBecomeCgiNamesWithTheirExceptions() throws Exception {
        write("server.php", """
                <?php
                echo $_SERVER['HTTP_USER_AGENT'], "\\n";
                echo $_SERVER['HTTP_X_CUSTOM_HEADER'], "\\n";
                echo $_SERVER['CONTENT_TYPE'], "\\n";
                echo $_SERVER['CONTENT_LENGTH'], "\\n";
                echo isset($_SERVER['HTTP_CONTENT_TYPE']) ? 'prefixed' : 'not-prefixed', "\\n";
                echo $_SERVER['HTTP_X_DUP'], "\\n";
                """);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "agent/1");
        headers.put("x-custom-header", "custom");
        headers.put("content-type", "text/plain");
        headers.put("Content-Length", "5");
        headers.put("X-Dup", "first");
        headers.put("x-dup", "second");

        String expected = "agent/1\ncustom\ntext/plain\n5\nnot-prefixed\nsecond\n";
        assertEquals(expected, text(get("/server.php", headers)));
        int cached = dispatcher.cgiNameCacheSize();
        assertEquals(expected, text(get("/server.php", headers)));
        assertEquals(cached, dispatcher.cgiNameCacheSize(), "a repeated header name was cached twice");
    }

    @Test
    void theHeaderNameCacheIsBounded() throws Exception {
        write("ok.php", "<?php echo isset($_SERVER['HTTP_H0']) ? 'ok' : 'missing';");
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; i < PhpDispatcher.CGI_NAME_CACHE_LIMIT * 2; i++) {
            headers.put("H" + i, "v" + i);
        }
        assertEquals("ok", text(get("/ok.php", headers)));
        assertTrue(dispatcher.cgiNameCacheSize() <= PhpDispatcher.CGI_NAME_CACHE_LIMIT,
                "header-name cache grew to " + dispatcher.cgiNameCacheSize());
    }

    @Test
    void staticFilesKeepTheirLastModifiedTimeAndMime() throws Exception {
        write("style.css", "body{}");
        Instant modified = Instant.parse("2024-01-02T03:04:05Z");
        Files.setLastModifiedTime(dir.resolve("style.css"), FileTime.from(modified));
        dispatcher.setRouteRevalidateMillis(60_000);

        PhpDispatcher.Response first = get("/style.css");
        assertEquals(200, first.status());
        assertEquals("text/css; charset=UTF-8", header(first, "Content-Type"));
        assertEquals("Tue, 2 Jan 2024 03:04:05 GMT", header(first, "Last-Modified"));
        assertEquals("body{}", text(first));

        Instant later = Instant.parse("2024-02-03T04:05:06Z");
        Files.setLastModifiedTime(dir.resolve("style.css"), FileTime.from(later));
        assertEquals("Sat, 3 Feb 2024 04:05:06 GMT", header(get("/style.css"), "Last-Modified"),
                "the cached route reused an old Last-Modified");

        PhpDispatcher.Response head = send("HEAD", "/style.css", null, Map.of());
        assertEquals(200, head.status());
        assertEquals(0, head.body().length);
        assertNotNull(header(head, "Last-Modified"));
    }

    @Test
    void responseHeadersBypassNettyValidation() throws Exception {
        // php permits control bytes other than CR, LF and NUL in a header value.
        write("weird.php", "<?php header(\"X-Weird: a\\x01b\"); header(\"X-Tab: a\\tb\"); echo 'body';");
        try (NettyPhpServer server = new NettyPhpServer(dispatcher, 0, 2)) {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(10_000);
                socket.getOutputStream().write(("GET /weird.php HTTP/1.1\r\nHost: localhost\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                String raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
                assertTrue(raw.contains("X-Weird: a\u0001b"), raw);
                assertTrue(raw.contains("X-Tab: a\tb"), raw);
                assertTrue(raw.endsWith("body"), raw);
            }
        }
    }

    @Test
    void aMissingPathIsCachedAsNotFound() throws Exception {
        PhpDispatcher.Response missing = get("/nothing");
        assertEquals(404, missing.status());
        assertEquals("Not Found: /nothing\n", text(missing));
        assertEquals(404, get("/nothing").status());
        assertEquals(1, dispatcher.routeCacheSize());
    }
}
