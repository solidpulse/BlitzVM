package dev.phpruntime.host;

import dev.phpruntime.sched.Scheduler;
import dev.phpruntime.snapshot.SnapshotGeneration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server-level boot snapshot tests: eligibility, restore, staleness, refusal, the request-time
 * hooks and verify mode, all through a real Netty server on a small app in
 * {@code src/test/resources/snapshot-app}.
 */
class SnapshotServerTest {

    private static final String REPORT = "blitz.snapshot.report";
    private static final String VERIFY = "blitz.snapshot.verify";
    private static final String REVALIDATE = "blitz.include.revalidateMs";

    private static final String APP_BODY = "boot=booted\n"
            + "global=7\n"
            + "object=svc\n"
            + "static=5\n"
            + "describe=count=5\n"
            + "shutdown=boot\n";

    @TempDir
    Path temp;

    @BeforeAll
    static void enableReportAndRevalidateAlways() {
        System.setProperty(REPORT, "true");
        // Check included files on every request; the tests touch them right before a request.
        System.setProperty(REVALIDATE, "0");
    }

    @AfterAll
    static void clearProperties() {
        System.clearProperty(REPORT);
        System.clearProperty(VERIFY);
        System.clearProperty(REVALIDATE);
    }

    @AfterEach
    void clearVerify() {
        System.clearProperty(VERIFY);
    }

    @Test
    void restoredRequestsAreHitsAndMatchAPlainServer() throws Exception {
        Path app = copyApp("snapshot-app", temp.resolve("app"));
        try (Fixture plain = Fixture.start(app, null, null);
             Fixture snap = Fixture.start(app, app.resolve("profile.properties"), null)) {
            Response first = snap.get("/");
            assertEquals(200, first.status());
            assertEquals("miss", first.snapshot());
            assertEquals(APP_BODY, first.body());
            assertEquals(APP_BODY, plain.get("/").body());

            Response hit = waitForHit(snap, "/");
            assertEquals(APP_BODY, hit.body());
            assertEquals(APP_BODY, plain.get("/").body());

            // A second restored request starts from pristine snapshot state, not from the
            // mutations the first one made to statics and globals.
            Response again = snap.get("/");
            assertEquals("hit", again.snapshot());
            assertEquals(APP_BODY, again.body());
        }
    }

    @Test
    void ineligibleRequestsRunAsToday() throws Exception {
        Path app = copyApp("snapshot-app", temp.resolve("app"));
        try (Fixture snap = Fixture.start(app, app.resolve("profile.properties"), null)) {
            waitForHit(snap, "/");

            Response other = snap.get("/other.php");
            assertEquals("ineligible", other.snapshot());
            assertEquals("other\n", other.body());

            Response post = snap.post("/");
            assertEquals("ineligible", post.snapshot());
            assertEquals(APP_BODY, post.body());

            Response cookie = snap.get("/", Map.of("Cookie", "wordpress_logged_in_abc=1"));
            assertEquals("ineligible", cookie.snapshot());
            assertEquals(APP_BODY, cookie.body());

            Response harmless = snap.get("/", Map.of("Cookie", "colour=blue"));
            assertEquals("hit", harmless.snapshot());
        }
    }

    @Test
    void eachStalenessReasonForcesARebuild() throws Exception {
        Path app = copyApp("snapshot-app", temp.resolve("app"));
        try (Fixture snap = Fixture.start(app, app.resolve("profile.properties"), null)) {
            waitForHit(snap, "/");

            SnapshotGeneration.bump();
            assertEquals("stale", snap.get("/").snapshot());
            assertEquals("hit", waitForHit(snap, "/").snapshot());

            Files.setLastModifiedTime(app.resolve("app-boot.php"),
                    FileTime.fromMillis(System.currentTimeMillis() + 5_000));
            assertEquals("stale", snap.get("/").snapshot());
            assertEquals("hit", waitForHit(snap, "/").snapshot());

            Thread.sleep(2_100); // past twice ttl_ms=1000: too old to serve while refreshing
            assertEquals("stale", snap.get("/").snapshot());
            assertEquals("hit", waitForHit(snap, "/").snapshot());
        }
    }

    @Test
    void ttlExpiryKeepsServingWhileARefreshRuns() throws Exception {
        Path app = copyApp("snapshot-app", temp.resolve("app"));
        try (Fixture snap = Fixture.start(app, app.resolve("profile.properties"), null)) {
            waitForHit(snap, "/");
            long rebuilds = snap.snapshots().rebuildCount();

            Thread.sleep(1_100); // past ttl_ms=1000, within twice that
            Response refreshed = snap.get("/");
            assertEquals("hit", refreshed.snapshot());
            assertEquals(APP_BODY, refreshed.body());
            assertEquals(rebuilds + 1, snap.snapshots().rebuildCount());
            waitForRebuildToLand(snap);

            // A write still stops the old snapshot at once, refresh or not.
            Thread.sleep(1_100);
            SnapshotGeneration.bump();
            assertEquals("stale", snap.get("/").snapshot());
        }
    }

    /** Waits until the refreshed snapshot is in place: a request soon after it is a plain hit. */
    private static void waitForRebuildToLand(Fixture snap) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        long before = snap.snapshots().rebuildCount();
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            if ("hit".equals(snap.get("/").snapshot()) && snap.snapshots().rebuildCount() == before) {
                return;
            }
            before = snap.snapshots().rebuildCount();
        }
        throw new AssertionError("refresh did not land");
    }

    @Test
    void oneKeyGoingStaleTooOftenIsDisabled() throws Exception {
        Path app = copyApp("snapshot-app", temp.resolve("app"));
        Path profile = app.resolve("profile-long-ttl.properties");
        Files.writeString(profile, Files.readString(app.resolve("profile.properties"))
                .replace("ttl_ms=1000", "ttl_ms=60000"));
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        try (Fixture snap = Fixture.start(app, profile, log::add)) {
            waitForHit(snap, "/");
            for (int i = 1; i <= 31; i++) {
                SnapshotGeneration.bump();
                assertEquals("stale", snap.get("/").snapshot());
                if (i < 31) {
                    assertEquals("hit", waitForHit(snap, "/").snapshot());
                }
            }
            assertTrue(waitForLog(log, "snapshot disabled for index.php"), "disable not logged: " + log);
            assertEquals("stale", snap.get("/").snapshot());
        }
    }

    @Test
    void ttlExpiryAloneNeverDisablesAKey() throws Exception {
        Path app = copyApp("snapshot-app", temp.resolve("app"));
        Path profile = app.resolve("profile-short-ttl.properties");
        Files.writeString(profile, Files.readString(app.resolve("profile.properties"))
                .replace("ttl_ms=1000", "ttl_ms=150"));
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        try (Fixture snap = Fixture.start(app, profile, log::add)) {
            waitForHit(snap, "/");
            for (int i = 1; i <= 35; i++) {
                Thread.sleep(350); // past twice ttl_ms=150, so the TTL forces a normal run
                assertEquals("stale", snap.get("/").snapshot());
                waitForHit(snap, "/");
            }
            assertTrue(log.stream().noneMatch(l -> l.contains("snapshot disabled")), "disabled: " + log);
        }
    }

    @Test
    void wordpressProfileShipsItsAfterRestoreProgram() throws Exception {
        SnapshotProfile profile = SnapshotProfile.load("wordpress");
        assertEquals("wp-load.php", profile.boot());
        assertEquals(List.of("index.php"), profile.entries());
        assertEquals(java.util.Set.of("GET", "HEAD"), profile.methods());
        assertTrue(profile.skipCookiePrefixes().contains("wp-postpass_"));
        assertEquals(List.of("WP_START_TIMESTAMP"), profile.requestTimeConstants());
        assertTrue(profile.verifyIgnore().contains("static-locals:function:wpdb::placeholder_escape"),
                profile.verifyIgnore().toString());
        assertTrue(profile.verifyIgnore().contains("static-locals:function:wp_is_json_media_type"),
                profile.verifyIgnore().toString());
        SnapshotProfile.Program program = profile.afterRestoreProgram(temp);
        assertNotNull(program);
        assertTrue(program.code().contains("wp_magic_quotes"), program.code());
    }

    /**
     * Item 4: a template run that ends in a refusal must not change what later requests compute.
     * The boot declares many enums, so the template run and the live requests link the same
     * class templates at the same time; before the linking invariant was fixed, one context
     * could reuse a class built against another request's {@code UnitEnum}, and the enum then
     * stopped being a {@code UnitEnum} for the rest of the process.
     */
    @Test
    void aRefusedTemplateRunDoesNotChangeLaterRequests() throws Exception {
        Path app = copyApp("snapshot-app-enum", temp.resolve("app"));
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        try (Fixture plain = Fixture.start(app, null, null);
             Fixture snap = Fixture.start(app, app.resolve("profile.properties"), log::add)) {
            String expected = plain.get("/").body();
            assertTrue(expected.contains("unit=yes"), expected);
            assertTrue(expected.contains("backed=yes"), expected);

            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
            try {
                List<java.util.concurrent.Future<Response>> inFlight = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    inFlight.add(pool.submit(() -> snap.get("/")));
                }
                for (java.util.concurrent.Future<Response> future : inFlight) {
                    assertEquals(expected, future.get(30, java.util.concurrent.TimeUnit.SECONDS).body());
                }
            } finally {
                pool.shutdownNow();
            }
            assertTrue(waitForLog(log, "output was written during boot"), "refusal not logged: " + log);

            for (int i = 0; i < 5; i++) {
                Response response = snap.get("/");
                assertEquals(expected, response.body());
            }
        }
    }

    @Test
    void refusedBootIsLoggedOnceAndRequestsKeepWorking() throws Exception {        Path app = copyApp("snapshot-app-refused", temp.resolve("app"));
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        try (Fixture snap = Fixture.start(app, app.resolve("profile.properties"), log::add)) {
            String expected = "boot output\nok\n";
            assertEquals(expected, snap.get("/").body());
            assertTrue(waitForLog(log, "output was written during boot"), "refusal not logged: " + log);

            for (int i = 0; i < 3; i++) {
                Response r = snap.get("/");
                assertEquals("miss", r.snapshot());
                assertEquals(expected, r.body());
            }
            assertEquals(1, count(log, "output was written during boot"));

            // A changed included file retries the refused capture without logging it again.
            long rebuilds = snap.snapshots().rebuildCount();
            Files.setLastModifiedTime(app.resolve("boot.php"),
                    FileTime.fromMillis(System.currentTimeMillis() + 5_000));
            snap.get("/");
            assertTrue(await(() -> snap.snapshots().rebuildCount() > rebuilds),
                    "refused capture was not retried after boot.php changed");
            assertEquals(1, count(log, "output was written during boot"));
            assertEquals(expected, snap.get("/").body());
        }
    }

    @Test
    void requestTimeConstantAndAfterRestoreDifferPerRequest() throws Exception {
        Path app = copyApp("snapshot-app", temp.resolve("app"));
        try (Fixture plain = Fixture.start(app, null, null);
             Fixture snap = Fixture.start(app, app.resolve("profile.properties"), null)) {
            waitForHit(snap, "/");
            Response a = snap.get("/");
            Response b = snap.get("/");
            assertEquals("hit", a.snapshot());
            assertEquals("hit", b.snapshot());

            assertNotNull(a.requestTime());
            assertNotEquals(a.requestTime(), b.requestTime());
            double seconds = Double.parseDouble(a.requestTime());
            assertTrue(Math.abs(seconds - System.currentTimeMillis() / 1000.0) < 10.0,
                    "request time not current: " + seconds);

            assertEquals("yes", a.afterRestore());
            assertEquals("yes", b.afterRestore());
            assertEquals("no", plain.get("/").afterRestore());
        }
    }

    @Test
    void verifyModeFindsNoDifferencesOnARequestIndependentApp() throws Exception {
        System.setProperty(VERIFY, "true");
        Path app = copyApp("snapshot-app", temp.resolve("app"));
        try (Fixture snap = Fixture.start(app, app.resolve("profile.properties"), null)) {
            waitForHit(snap, "/");
            Response hit = snap.get("/");
            assertEquals("hit", hit.snapshot());
            assertEquals(APP_BODY, hit.body());
            List<String> differences = snap.snapshots().verifyDifferences();
            assertTrue(differences.isEmpty(), "unexpected differences: " + differences);
        }
    }

    @Test
    void verifyModeFindsARequestDependencyInBoot() throws Exception {
        System.setProperty(VERIFY, "true");
        Path app = copyApp("snapshot-app-request", temp.resolve("app"));
        try (Fixture snap = Fixture.start(app, app.resolve("profile.properties"), null)) {
            waitForHit(snap, "/?x=1");
            Response hit = snap.get("/?x=1");
            assertEquals("hit", hit.snapshot());
            assertEquals("from_get=\n", hit.body());

            List<String> differences = snap.snapshots().verifyDifferences();
            assertFalse(differences.isEmpty(), "verify reported no differences");
            assertTrue(differences.stream().anyMatch(d -> d.contains("global:from_get")
                            || d.contains("response body")),
                    "differences did not name the request dependency: " + differences);
        }
    }

    @Test
    void verifyIgnoreSuppressesAProfileDeclaredComponent() throws Exception {
        System.setProperty(VERIFY, "true");
        Path app = copyApp("snapshot-app-verify-ignore", temp.resolve("app"));
        try (Fixture snap = Fixture.start(app, app.resolve("profile.properties"), null)) {
            waitForHit(snap, "/?x=1");
            assertEquals("hit", snap.get("/?x=1").snapshot());
            List<String> differences = snap.snapshots().verifyDifferences();
            assertTrue(differences.stream().anyMatch(d -> d.contains("static-locals:function:request_memo")),
                    "the static-local dependency was not reported: " + differences);
        }
        try (Fixture snap = Fixture.start(app, app.resolve("profile-ignore.properties"), null)) {
            waitForHit(snap, "/?x=1");
            Response hit = snap.get("/?x=1");
            assertEquals("hit", hit.snapshot());
            assertEquals("stable\n", hit.body());
            List<String> differences = snap.snapshots().verifyDifferences();
            assertTrue(differences.isEmpty(), "ignored component was still reported: " + differences);
        }
    }

    // ---------------------------------------------------------------- fixture

    private record Response(int status, String body, String snapshot, String requestTime,
                            String afterRestore) {
    }

    private static final class Fixture implements AutoCloseable {
        private final Scheduler scheduler;
        private final PhpEngine engine;
        private final PhpDispatcher dispatcher;
        private final PhpServer server;
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
        private final int port;

        private Fixture(Path docRoot, SnapshotProfile profile, Consumer<String> logger) throws Exception {
            this.scheduler = new Scheduler(2, 10, 30_000);
            this.engine = new PhpEngine(scheduler);
            this.dispatcher = new PhpDispatcher(docRoot, engine, profile);
            if (logger != null && dispatcher.snapshots() != null) {
                dispatcher.snapshots().setLogger(logger);
            }
            this.server = new NettyPhpServer(dispatcher, 0, 16);
            server.start();
            this.port = server.port();
        }

        static Fixture start(Path docRoot, Path profile, Consumer<String> logger) throws Exception {
            return new Fixture(docRoot,
                    profile == null ? null : SnapshotProfile.load(profile.toString()), logger);
        }

        SnapshotServer snapshots() {
            return dispatcher.snapshots();
        }

        Response get(String path) throws Exception {
            return get(path, Map.of());
        }

        Response get(String path, Map<String, String> headers) throws Exception {
            return send("GET", path, headers);
        }

        Response post(String path) throws Exception {
            return send("POST", path, Map.of());
        }

        private Response send(String method, String path, Map<String, String> headers) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(30));
            headers.forEach(builder::header);
            if (method.equals("GET")) {
                builder.GET();
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.ISO_8859_1));
            return new Response(response.statusCode(), response.body(),
                    response.headers().firstValue("X-Blitz-Snapshot").orElse(null),
                    response.headers().firstValue("X-App-Request-Time").orElse(null),
                    response.headers().firstValue("X-App-After").orElse(null));
        }

        @Override
        public void close() {
            server.close();
            dispatcher.close();
            engine.close();
            scheduler.close();
        }
    }

    private static Response waitForHit(Fixture fixture, String path) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            Response response = fixture.get(path);
            if ("hit".equals(response.snapshot())) {
                return response;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no snapshot hit for " + path);
    }

    private static boolean waitForLog(List<String> log, String needle) throws InterruptedException {
        return await(() -> count(log, needle) > 0);
    }

    private static boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static long count(List<String> log, String needle) {
        synchronized (log) {
            return log.stream().filter(line -> line.contains(needle)).count();
        }
    }

    private Path copyApp(String name, Path target) throws IOException {
        URL resource = SnapshotServerTest.class.getClassLoader().getResource(name);
        assertNotNull(resource, "missing test resource " + name);
        Path source;
        try {
            source = Path.of(resource.toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path copy = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(copy);
                } else {
                    Files.createDirectories(copy.getParent());
                    Files.copy(file, copy);
                }
            }
        }
        return target;
    }
}
