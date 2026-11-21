package dev.phpruntime.host;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.source.Source;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.expr.EvalNode;
import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.snapshot.Snapshot;
import dev.phpruntime.snapshot.SnapshotGeneration;
import dev.phpruntime.snapshot.SnapshotRun;
import dev.phpruntime.snapshot.StateFingerprint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * The server side of boot snapshots: profiles, eligibility, the per-key snapshot store, background
 * template runs, staleness, counters and verify mode. One instance exists per HTTP server, and
 * only when {@code --profile} was given; without a profile the dispatcher never consults it.
 *
 * <p>A template run uses the snapshot package's capture/restore core (37a). The store only decides
 * when to start one, keeps the captured snapshot, and validates it before a request restores from
 * it.
 */
public final class SnapshotServer implements AutoCloseable {

    /** What happened to one request; mirrored in the {@code X-Blitz-Snapshot} header. */
    public enum Outcome {
        HIT("hit"),
        MISS("miss"),
        INELIGIBLE("ineligible"),
        STALE("stale"),
        BUILDING("building");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** The decision for one request: the header label, and the restore run when there is a hit. */
    public record Plan(Outcome outcome, SnapshotRun run) {
        static Plan of(Outcome outcome) {
            return new Plan(outcome, null);
        }
    }

    private static final long STALE_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final long DISABLED_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final long REFUSED_RETRY_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final int STALE_LIMIT = 30;

    private enum State {
        MISSING, BUILDING, READY, REFUSED, DISABLED
    }

    private record Key(String docRoot, String entry, String scheme) {
    }

    /** One snapshot key: the captured snapshot plus everything staleness and retry logic needs. */
    private static final class StoreEntry {
        final Key key;
        volatile State state = State.MISSING;
        volatile Snapshot snapshot;
        volatile long generation;
        volatile long capturedAt;
        volatile Map<String, Long> fileTimes = Map.of();
        volatile long lastFileCheck;
        volatile boolean building;
        volatile long refusedAt;
        volatile Map<String, Long> refusalFileTimes = Map.of();
        volatile String loggedRefusal;
        volatile long disabledUntil;
        volatile boolean disabledLogged;
        /** Stale events inside the last minute, guarded by {@code this}. */
        long[] staleAt = new long[64];
        int staleCount;

        StoreEntry(Key key) {
            this.key = key;
        }
    }

    private final Path docRoot;
    private final PhpEngine engine;
    private final SnapshotProfile profile;
    private final Path bootFile;
    private final String bootPath;
    private final ConcurrentHashMap<Key, StoreEntry> entries = new ConcurrentHashMap<>();
    private final ExecutorService builds = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "blitz-snapshot-build");
        t.setDaemon(true);
        return t;
    });
    private final boolean report;
    private final boolean verify;
    private final long ttlNanos;
    private final long revalidateNanos;
    private final Set<String> verifyIgnore;
    private final List<java.util.regex.Pattern> verifyBodyIgnore;
    private final Source afterRestoreSource;
    private volatile CallTarget afterRestoreTarget;
    private final ScheduledExecutorService reporter;
    private final Counters counters = new Counters();
    private final ThreadLocal<Set<String>> verifyExcluded = new ThreadLocal<>();
    private final List<String> verifyDifferences = Collections.synchronizedList(new ArrayList<>());
    private volatile Consumer<String> logger = System.err::println;
    private final AtomicBoolean reported = new AtomicBoolean();

    public SnapshotServer(Path docRoot, PhpEngine engine, SnapshotProfile profile) throws IOException {
        this.docRoot = docRoot.toAbsolutePath().normalize();
        this.engine = engine;
        this.profile = profile;
        Path boot = Path.of(profile.boot());
        if (!boot.isAbsolute()) {
            boot = this.docRoot.resolve(boot);
        }
        this.bootFile = canonical(boot);
        this.bootPath = bootFile.toString();
        this.report = Boolean.getBoolean("blitz.snapshot.report");
        this.verify = Boolean.getBoolean("blitz.snapshot.verify");
        this.ttlNanos = TimeUnit.MILLISECONDS.toNanos(profile.ttlMs());
        this.revalidateNanos = Long.getLong("blitz.include.revalidateMs", 2000L) * 1_000_000L;
        this.verifyIgnore = Set.copyOf(profile.verifyIgnore());
        List<java.util.regex.Pattern> bodyIgnore = new ArrayList<>();
        for (String name : profile.verifyIgnore()) {
            if (name.startsWith("response-body:")) {
                bodyIgnore.add(java.util.regex.Pattern.compile(name.substring("response-body:".length())));
            }
        }
        this.verifyBodyIgnore = List.copyOf(bodyIgnore);
        this.afterRestoreSource = afterRestoreSource(profile.afterRestoreProgram(this.docRoot));
        if (report) {
            reporter = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "blitz-snapshot-report");
                t.setDaemon(true);
                return t;
            });
            reporter.scheduleAtFixedRate(this::printReport, 10, 10, TimeUnit.SECONDS);
        } else {
            reporter = null;
        }
    }

    public SnapshotProfile profile() {
        return profile;
    }

    public boolean reportEnabled() {
        return report;
    }

    public boolean verifyEnabled() {
        return verify;
    }

    /** Test hook; production logs to stderr. */
    void setLogger(Consumer<String> newLogger) {
        this.logger = newLogger;
    }

    /** How many template runs have started; tests use it to observe a retry. */
    long rebuildCount() {
        return counters.rebuilds.sum();
    }

    /** Every difference verify mode has reported, for tests and debugging. */
    public List<String> verifyDifferences() {
        synchronized (verifyDifferences) {
            return List.copyOf(verifyDifferences);
        }
    }

    /**
     * Decides how one request runs. Eligible, ready and fresh requests get a restored run; other
     * eligible requests run as today and a missing, stale or refused snapshot starts a rebuild.
     */
    public Plan plan(PhpDispatcher.Request transport, PhpRequest request, Path script) {
        verifyExcluded.remove();
        String entry = entryName(script);
        if (entry == null) {
            return ineligible("script");
        }
        if (!profile.methods().contains(transport.method())) {
            return ineligible("method");
        }
        String skipped = skippedCookie(transport.headers());
        if (skipped != null) {
            return ineligible("cookie");
        }

        String scheme = request.server.getOrDefault("REQUEST_SCHEME", "http");
        Key key = new Key(docRoot.toString(), entry, scheme);
        StoreEntry store = entries.computeIfAbsent(key, StoreEntry::new);
        long now = System.nanoTime();
        double startSeconds = microtime();

        State state = store.state;
        if (state == State.DISABLED) {
            if (now - store.disabledUntil < 0) {
                counters.stale("disabled");
                return Plan.of(Outcome.STALE);
            }
            synchronized (store) {
                if (store.state == State.DISABLED) {
                    store.state = State.MISSING;
                    store.disabledLogged = false;
                }
            }
            state = store.state;
        }
        if (state == State.REFUSED && !refusalRetryDue(store, now)) {
            counters.misses.increment();
            return Plan.of(Outcome.MISS);
        }
        if (state == State.BUILDING) {
            counters.misses.increment();
            return Plan.of(Outcome.BUILDING);
        }
        if (state == State.READY) {
            // Snapshot is volatile and may be dropped by a build that started after the state
            // read; a request that loses that race simply runs as today.
            Snapshot snapshot = store.snapshot;
            if (snapshot == null) {
                counters.misses.increment();
                startBuild(store, transport.serverPort());
                return Plan.of(Outcome.MISS);
            }
            String stale = staleReason(store, now);
            if ("ttl".equals(stale) && now - store.capturedAt <= 2 * ttlNanos) {
                // Only the TTL expired: keep serving this snapshot while a background build
                // refreshes it, so requests do not fall back to a full boot. Writes and file
                // changes are checked first and still stop it at once; past twice the TTL (an
                // idle server) the request runs normally.
                if (startRefresh(store, transport.serverPort())) {
                    counters.stale(stale);
                }
                stale = null;
            }
            if (stale == null) {
                counters.hits.increment();
                SnapshotRun run = SnapshotRun.restored(bootPath, snapshot,
                        ctx -> applyRestore(ctx, startSeconds));
                if (!verify) {
                    run.withoutFingerprint();
                }
                return new Plan(Outcome.HIT, run);
            }
            counters.stale(stale);
            // TTL expiry is bounded by ttl_ms by construction (up to 30 a minute at 2 s), so only
            // writes and file changes count toward disabling a key that churns.
            if (!"ttl".equals(stale) && recordStale(store, now)) {
                return Plan.of(Outcome.STALE);
            }
            startBuild(store, transport.serverPort());
            return Plan.of(Outcome.STALE);
        }
        counters.misses.increment();
        startBuild(store, transport.serverPort());
        return Plan.of(Outcome.MISS);
    }

    /**
     * Verify mode: runs the same request fully in a shadow context and logs how the restored
     * boundary fingerprint and response body differ. The restored response is the one sent.
     */
    void verify(PhpRequest request, Path script, PhpEngine.Result restored, SnapshotRun restoredRun) {
        Set<String> excluded = verifyExcluded.get();
        verifyExcluded.remove();
        PhpEngine.Result expected;
        SnapshotRun full = SnapshotRun.full(bootPath);
        try {
            expected = engine.execute(script, copyRequest(request), full);
        } catch (IOException | RuntimeException e) {
            log("snapshot verify " + bootPath + ": shadow run failed: " + e);
            return;
        }
        List<String> differences = new ArrayList<>();
        Map<String, String> want = full.fingerprint();
        Map<String, String> have = restoredRun.fingerprint();
        if (want == null || have == null) {
            differences.add("no boundary fingerprint (full " + (want == null ? "missing" : "present")
                    + ", restored " + (have == null ? "missing" : "present") + ")");
        } else {
            Set<String> names = new TreeSet<>(want.keySet());
            names.addAll(have.keySet());
            for (String name : names) {
                if (excluded != null && excluded.contains(name)) {
                    continue;
                }
                String a = want.get(name);
                String b = have.get(name);
                if (Objects.equals(a, b)) {
                    continue;
                }
                if (verifyIgnore.contains(name)) {
                    // A profile-declared expected difference: a per-request memo or a random
                    // marker that cannot change the response. Counted so the report shows it.
                    counters.verifyIgnored(name);
                    continue;
                }
                differences.add("fingerprint " + name + " (full " + a + ", restored " + b + ")");
            }
        }
        if (!bodyMatches(restored.body(), expected.body())) {
            differences.add("response body (" + restored.body().length + " bytes restored, "
                    + expected.body().length + " bytes full)");
        }
        for (String difference : differences) {
            log("snapshot verify " + bootPath + ": " + difference);
        }
        verifyDifferences.addAll(differences);
    }

    /**
     * Whether two response bodies are equal once the profile's {@code response-body:} patterns
     * are removed. A page that renders a random id per request cannot be byte-identical; the
     * pattern names the request-dependent span so verify still catches every other difference.
     */
    private boolean bodyMatches(byte[] restored, byte[] expected) {
        if (Arrays.equals(restored, expected)) {
            return true;
        }
        if (verifyBodyIgnore.isEmpty()) {
            return false;
        }
        String a = new String(restored, java.nio.charset.StandardCharsets.ISO_8859_1);
        String b = new String(expected, java.nio.charset.StandardCharsets.ISO_8859_1);
        for (java.util.regex.Pattern pattern : verifyBodyIgnore) {
            a = pattern.matcher(a).replaceAll("");
            b = pattern.matcher(b).replaceAll("");
        }
        if (a.equals(b)) {
            counters.verifyIgnored("response-body");
            return true;
        }
        return false;
    }

    private static Source afterRestoreSource(SnapshotProfile.Program program) throws IOException {        if (program == null) {
            return null;
        }
        if (program.file() == null) {
            return Source.newBuilder(PhpLanguage.ID, program.code(), program.name()).cached(true).build();
        }
        // A profile file is read the way every other PHP file is: ISO-8859-1 bytes. The URI keeps
        // the path so the language does not treat the characters as embedder text again.
        String code = new String(Files.readAllBytes(program.file()),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        return Source.newBuilder(PhpLanguage.ID, code, program.name())
                .uri(program.file().toUri()).cached(true).build();
    }

    // ---------------------------------------------------------------- rebuild

    private void startBuild(StoreEntry store, int port) {
        synchronized (store) {
            if (store.building) {
                return;
            }
            store.building = true;
            store.state = State.BUILDING;
        }
        counters.rebuilds.increment();
        try {
            builds.execute(() -> build(store, port));
        } catch (RejectedExecutionException e) {
            synchronized (store) {
                store.building = false;
                store.state = State.MISSING;
            }
        }
    }

    /** Starts a build that leaves the current snapshot in service; false when one is running. */
    private boolean startRefresh(StoreEntry store, int port) {
        synchronized (store) {
            if (store.building) {
                return false;
            }
            store.building = true;
        }
        counters.rebuilds.increment();
        try {
            builds.execute(() -> build(store, port));
        } catch (RejectedExecutionException e) {
            synchronized (store) {
                store.building = false;
            }
        }
        return true;
    }

    /** A background template run of the entry for the synthetic request, holding a CPU slot. */
    private void build(StoreEntry store, int port) {
        Path entry = docRoot.resolve(store.key.entry()).normalize();
        SnapshotRun run = SnapshotRun.template(bootPath);
        // Read before the template run: a write that lands while it runs may or may not be in
        // the captured state, so the snapshot must already count as stale for it.
        long generation = SnapshotGeneration.current();
        long started = System.nanoTime();
        try {
            engine.execute(entry, templateRequest(entry, port), run);
        } catch (Throwable t) {
            log("snapshot build for " + store.key.entry() + " failed: " + t);
        }
        long now = System.nanoTime();
        synchronized (store) {
            store.building = false;
            if (!run.bootReached()) {
                refuse(store, List.of("the boot include " + bootPath + " was never reached"),
                        now, run, entry);
            } else if (run.refusal() != null) {
                refuse(store, run.refusal(), now, run, entry);
            } else {
                Snapshot captured = run.snapshot();
                store.snapshot = captured;
                store.generation = generation;
                store.capturedAt = started; // the age of the data, not of the copy
                store.fileTimes = modificationTimes(captured.includedFiles());
                store.lastFileCheck = now;
                // A concurrent request may have disabled the key while this build ran; the
                // disable then wins and the snapshot waits for the next attempt.
                if (store.state == State.BUILDING) {
                    store.state = State.READY;
                }
                store.loggedRefusal = null;
                store.refusalFileTimes = Map.of();
            }
        }
    }

    private void refuse(StoreEntry store, List<String> reasons, long now, SnapshotRun run, Path entry) {
        store.snapshot = null;
        // BUILDING after a miss, READY after a refresh that kept the old snapshot in service.
        if (store.state == State.BUILDING || store.state == State.READY) {
            store.state = State.REFUSED;
        }
        store.refusedAt = now;
        Set<String> files = new LinkedHashSet<>(run.includedFiles());
        if (files.isEmpty()) {
            files.add(canonical(entry).toString());
            files.add(bootPath);
        }
        store.refusalFileTimes = modificationTimes(files);
        counters.refusals.increment();
        counters.refusal(reasons.get(0));
        String headline = reasons.get(0);
        if (!headline.equals(store.loggedRefusal)) {
            store.loggedRefusal = headline;
            log("snapshot capture refused for " + store.key.entry() + ": " + String.join("; ", reasons));
        }
    }

    private boolean refusalRetryDue(StoreEntry store, long now) {
        if (now - store.refusedAt >= REFUSED_RETRY_NANOS) {
            return true;
        }
        for (Map.Entry<String, Long> file : store.refusalFileTimes.entrySet()) {
            if (!Objects.equals(file.getValue(), mtime(file.getKey()))) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- staleness

    /** Why the snapshot cannot be used, or null when it is fresh. */
    private String staleReason(StoreEntry store, long now) {
        if (SnapshotGeneration.current() != store.generation) {
            return "generation";
        }
        // Like opcache.revalidate_freq: stat the included files at most this often. Checked
        // before the TTL so that "ttl" always means nothing but the age changed.
        if (now - store.lastFileCheck >= revalidateNanos) {
            store.lastFileCheck = now;
            for (Map.Entry<String, Long> file : store.fileTimes.entrySet()) {
                if (!Objects.equals(file.getValue(), mtime(file.getKey()))) {
                    return "file";
                }
            }
        }
        if (ttlNanos > 0 && now - store.capturedAt > ttlNanos) {
            return "ttl";
        }
        return null;
    }

    /** Records one stale event; returns true when the key has gone stale too often and is disabled. */
    private boolean recordStale(StoreEntry store, long now) {
        synchronized (store) {
            long cutoff = now - STALE_WINDOW_NANOS;
            int kept = 0;
            for (int i = 0; i < store.staleCount; i++) {
                if (store.staleAt[i] > cutoff) {
                    store.staleAt[kept++] = store.staleAt[i];
                }
            }
            store.staleCount = kept;
            if (kept == store.staleAt.length) {
                store.staleAt = Arrays.copyOf(store.staleAt, kept * 2);
            }
            store.staleAt[store.staleCount++] = now;
            if (store.staleCount > STALE_LIMIT) {
                store.state = State.DISABLED;
                store.disabledUntil = now + DISABLED_NANOS;
                store.staleCount = 0;
                if (!store.disabledLogged) {
                    store.disabledLogged = true;
                    counters.disabled.increment();
                    log("snapshot disabled for " + store.key.entry()
                            + ": stale more than " + STALE_LIMIT + " times in a minute");
                }
                return true;
            }
            return false;
        }
    }

    // ---------------------------------------------------------------- restore hook

    /**
     * Runs after the copy and before the entry's first line: request-time constants get the
     * request's start time and the after-restore program runs in the global scope, as php's
     * auto_prepend_file would.
     */
    private void applyRestore(PhpContext ctx, double startSeconds) {
        Map<String, String> before = verify ? StateFingerprint.fingerprint(ctx) : null;
        for (String constant : profile.requestTimeConstants()) {
            ctx.restoreConstant(constant, startSeconds);
        }
        if (afterRestoreSource != null) {
            afterRestoreTarget(ctx).call(new Object[]{EvalNode.EVAL_MODE});
        }
        if (verify) {
            Map<String, String> after = StateFingerprint.fingerprint(ctx);
            Set<String> changed = new LinkedHashSet<>();
            Set<String> names = new TreeSet<>(before.keySet());
            names.addAll(after.keySet());
            for (String name : names) {
                if (!Objects.equals(before.get(name), after.get(name))) {
                    changed.add(name);
                }
            }
            verifyExcluded.set(changed);
        }
    }

    private CallTarget afterRestoreTarget(PhpContext ctx) {
        CallTarget target = afterRestoreTarget;
        if (target == null) {
            synchronized (this) {
                target = afterRestoreTarget;
                if (target == null) {
                    target = ctx.language().parseEvalBytes(afterRestoreSource);
                    afterRestoreTarget = target;
                }
            }
        }
        return target;
    }

    // ---------------------------------------------------------------- requests

    /** The profile entry this script is, or null when it is not one. */
    private String entryName(Path script) {
        Path absolute = script.toAbsolutePath().normalize();
        for (String entry : profile.entries()) {
            if (absolute.equals(docRoot.resolve(entry).normalize())) {
                return entry;
            }
        }
        return null;
    }

    private Plan ineligible(String reason) {
        counters.ineligible.increment();
        counters.ineligibleReasons.computeIfAbsent(reason, k -> new LongAdder()).increment();
        return Plan.of(Outcome.INELIGIBLE);
    }

    /** The first cookie name that starts with a skip prefix, or null. */
    private String skippedCookie(Map<String, String> headers) {
        String header = null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase("Cookie")) {
                header = entry.getValue();
                break;
            }
        }
        if (header == null) {
            return null;
        }
        for (String part : header.split(";")) {
            String cookie = part.trim();
            if (cookie.isEmpty()) {
                continue;
            }
            int eq = cookie.indexOf('=');
            String name = (eq < 0 ? cookie : cookie.substring(0, eq)).trim();
            for (String prefix : profile.skipCookiePrefixes()) {
                if (name.startsWith(prefix)) {
                    return name;
                }
            }
        }
        return null;
    }

    /**
     * The synthetic request a template run uses: GET of the entry, no query, no cookies, no body,
     * the profile's template host. Server variables are what a real request to that entry would
     * have, with SERVER_SOFTWARE and REQUEST_URI first so WordPress's wp_fix_server_vars() leaves
     * the pre-boot $_SERVER value-identical in the template pass.
     */
    private PhpRequest templateRequest(Path entry, int port) {
        PhpRequest r = new PhpRequest();
        r.http = true;
        r.method = "GET";
        r.uri = "/";
        r.queryString = "";
        r.scriptFilename = entry.toString();
        r.remoteAddr = "127.0.0.1";
        r.headers.put("Host", profile.templateHost());
        String script = "/" + docRoot.relativize(entry).toString().replace('\\', '/');
        Map<String, String> s = r.server;
        s.put("SERVER_SOFTWARE", "php-truffle");
        s.put("REQUEST_URI", "/");
        s.put("REQUEST_METHOD", "GET");
        s.put("QUERY_STRING", "");
        s.put("SCRIPT_NAME", PhpStrings.fromJava(script));
        s.put("PHP_SELF", PhpStrings.fromJava(script));
        s.put("GATEWAY_INTERFACE", "CGI/1.1");
        s.put("REQUEST_SCHEME", "http");
        long nowMillis = System.currentTimeMillis();
        s.put("REQUEST_TIME_FLOAT", Double.toString(nowMillis / 1000.0));
        s.put("SCRIPT_FILENAME", PhpStrings.fromJava(entry.toString()));
        s.put("DOCUMENT_ROOT", PhpStrings.fromJava(docRoot.toString()));
        s.put("SERVER_PROTOCOL", "HTTP/1.1");
        s.put("SERVER_NAME", PhpStrings.fromJava(profile.templateHost()));
        s.put("SERVER_PORT", Integer.toString(port > 0 ? port : 80));
        s.put("REMOTE_ADDR", "127.0.0.1");
        s.put("REQUEST_TIME", Long.toString(nowMillis / 1000));
        s.put("HTTP_HOST", PhpStrings.fromJava(profile.templateHost()));
        return r;
    }

    private static PhpRequest copyRequest(PhpRequest request) {
        PhpRequest r = new PhpRequest();
        r.http = request.http;
        r.method = request.method;
        r.uri = request.uri;
        r.queryString = request.queryString;
        r.scriptFilename = request.scriptFilename;
        r.remoteAddr = request.remoteAddr;
        r.body = request.body;
        r.argv = request.argv.clone();
        r.headers.putAll(request.headers);
        r.get.putAll(request.get);
        r.post.putAll(request.post);
        r.server.putAll(request.server);
        r.environment.putAll(request.environment);
        return r;
    }

    // ---------------------------------------------------------------- counters and lifetime

    private void printReport() {
        if (!reported.compareAndSet(false, true)) {
            return;
        }
        System.out.println(counters.line());
        counters.detail("ineligible", counters.ineligibleReasons).forEach(System.out::println);
        counters.detail("stale", counters.staleReasons).forEach(System.out::println);
        counters.detail("refused", counters.refusalReasons).forEach(System.out::println);
        counters.detail("verify ignored", counters.verifyIgnoredReasons).forEach(System.out::println);
        reported.set(false);
    }

    private void log(String message) {
        logger.accept(message);
    }

    @Override
    public void close() {
        if (reporter != null) {
            reporter.shutdownNow();
        }
        builds.shutdownNow();
        if (report) {
            printReport();
        }
    }

    private static final class Counters {
        final LongAdder hits = new LongAdder();
        final LongAdder misses = new LongAdder();
        final LongAdder ineligible = new LongAdder();
        final LongAdder stale = new LongAdder();
        final LongAdder rebuilds = new LongAdder();
        final LongAdder refusals = new LongAdder();
        final LongAdder disabled = new LongAdder();
        final ConcurrentHashMap<String, LongAdder> ineligibleReasons = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, LongAdder> staleReasons = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, LongAdder> refusalReasons = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, LongAdder> verifyIgnoredReasons = new ConcurrentHashMap<>();

        void stale(String reason) {
            stale.increment();
            staleReasons.computeIfAbsent(reason, k -> new LongAdder()).increment();
        }

        void refusal(String reason) {
            refusalReasons.computeIfAbsent(reason, k -> new LongAdder()).increment();
        }

        void verifyIgnored(String component) {
            verifyIgnoredReasons.computeIfAbsent(component, k -> new LongAdder()).increment();
        }

        String line() {
            return "blitz snapshot: hits=" + hits.sum() + " misses=" + misses.sum()
                    + " ineligible=" + ineligible.sum() + " stale=" + stale.sum()
                    + " rebuilds=" + rebuilds.sum() + " refusals=" + refusals.sum()
                    + " disabled=" + disabled.sum();
        }

        List<String> detail(String label, ConcurrentHashMap<String, LongAdder> reasons) {
            List<String> out = new ArrayList<>();
            for (String reason : new TreeSet<>(reasons.keySet())) {
                out.add("blitz snapshot " + label + ": " + reason + "=" + reasons.get(reason).sum());
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- helpers

    /** microtime(true) semantics: wall-clock milliseconds plus the nanosecond sub-millisecond part. */
    private static double microtime() {
        long nanos = System.currentTimeMillis() * 1_000_000L + (System.nanoTime() % 1_000_000L);
        return nanos / 1e9;
    }

    private static Path canonical(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static Map<String, Long> modificationTimes(Set<String> paths) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String path : paths) {
            out.put(path, mtime(path));
        }
        return out;
    }

    private static long mtime(String path) {
        try {
            return Files.getLastModifiedTime(Path.of(path)).toMillis();
        } catch (IOException | RuntimeException e) {
            return -1L;
        }
    }
}
