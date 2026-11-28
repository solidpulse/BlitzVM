package dev.phpruntime.host;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * A boot-snapshot profile: which entry scripts are served from a snapshot, which boot file is
 * captured, which requests are ineligible, and the per-request work a restored request needs.
 * The built-in {@code wordpress} profile covers WordPress; anything else is a properties file,
 * so the keys stay documented in one place (see {@link #KEYS}).
 */
public final class SnapshotProfile {

    /** The documented properties-file keys, in the order the report lists them. */
    public static final List<String> KEYS = List.of(
            "boot", "entries", "methods", "skip_cookie_prefixes", "after_restore",
            "request_time_constants", "ttl_ms", "template_host", "verify_ignore");

    private static final String WORDPRESS_AFTER_RESTORE =
            "dev/phpruntime/snapshot/wordpress-after-restore.php";

    /**
     * Fingerprint components verify is expected to see differ on every WordPress request,
     * both function-static memos whose value cannot affect a response:
     *
     * <ul>
     *   <li>{@code wp_is_json_media_type} caches a pure function of one string. Boot calls it
     *       with the request's {@code Accept} header; the synthetic template request has no
     *       such header, so the snapshot simply has a colder cache and recomputes on demand.
     *   <li>{@code wpdb::placeholder_escape} is a random marker per boot, used internally by
     *       {@code prepare()} and removed by the {@code query} filter before SQL runs.
     * </ul>
     */
    private static final List<String> WORDPRESS_VERIFY_IGNORE = List.of(
            "static-locals:function:wp_is_json_media_type",
            "static-locals:function:wpdb::placeholder_escape");

    private final String name;
    private final String boot;
    private final List<String> entries;
    private final Set<String> methods;
    private final List<String> skipCookiePrefixes;
    private final String afterRestore;
    private final String afterRestoreResource;
    private final List<String> requestTimeConstants;
    private final long ttlMs;
    private final String templateHost;
    private final List<String> verifyIgnore;

    private SnapshotProfile(String name, String boot, List<String> entries, Set<String> methods,
                            List<String> skipCookiePrefixes, String afterRestore,
                            String afterRestoreResource, List<String> requestTimeConstants,
                            long ttlMs, String templateHost, List<String> verifyIgnore) {
        this.name = name;
        this.boot = boot;
        this.entries = List.copyOf(entries);
        this.methods = Set.copyOf(methods);
        this.skipCookiePrefixes = List.copyOf(skipCookiePrefixes);
        this.afterRestore = afterRestore;
        this.afterRestoreResource = afterRestoreResource;
        this.requestTimeConstants = List.copyOf(requestTimeConstants);
        this.ttlMs = ttlMs;
        this.templateHost = templateHost;
        this.verifyIgnore = List.copyOf(verifyIgnore);
    }

    public static SnapshotProfile wordpress() {
        return new SnapshotProfile("wordpress", "wp-load.php", List.of("index.php"),
                new LinkedHashSet<>(List.of("GET", "HEAD")),
                List.of("wordpress_", "wp-", "comment_author_", "wp-postpass_"),
                null, WORDPRESS_AFTER_RESTORE, List.of("WP_START_TIMESTAMP"), 2000L, "localhost",
                WORDPRESS_VERIFY_IGNORE);
    }

    /**
     * The built-in profile for the application in {@code docRoot}, or null when none is
     * recognised. WordPress is recognised by the files every install has in its root:
     * {@code wp-load.php}, {@code wp-settings.php} and {@code wp-includes/version.php}.
     */
    public static String detect(Path docRoot) {
        if (docRoot != null && Files.isRegularFile(docRoot.resolve("wp-load.php"))
                && Files.isRegularFile(docRoot.resolve("wp-settings.php"))
                && Files.isRegularFile(docRoot.resolve("wp-includes").resolve("version.php"))) {
            return "wordpress";
        }
        return null;
    }

    /**
     * Loads {@code --profile <name|file>}: the name {@code wordpress} is built in; anything else
     * is a properties file. Relative {@code boot} and {@code after_restore} paths resolve against
     * the document root.
     */
    public static SnapshotProfile load(String nameOrFile) throws IOException {
        if (nameOrFile.equals("wordpress")) {
            return wordpress();
        }
        Path file = Path.of(nameOrFile);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("no such snapshot profile: " + nameOrFile);
        }
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(reader);
        }
        String boot = required(p, "boot", file);
        List<String> entries = list(p.getProperty("entries"));
        if (entries.isEmpty()) {
            throw new IllegalArgumentException(file + ": entries must name at least one script");
        }
        List<String> methods = new ArrayList<>();
        for (String method : list(p.getProperty("methods", "GET,HEAD"))) {
            methods.add(method.toUpperCase(java.util.Locale.ROOT));
        }
        long ttl = 2000L;
        String ttlText = p.getProperty("ttl_ms");
        if (ttlText != null && !ttlText.isBlank()) {
            try {
                ttl = Long.parseLong(ttlText.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(file + ": ttl_ms is not a number: " + ttlText);
            }
            if (ttl < 0) {
                throw new IllegalArgumentException(file + ": ttl_ms must not be negative");
            }
        }
        return new SnapshotProfile(file.getFileName().toString(), boot, entries,
                new LinkedHashSet<>(methods), list(p.getProperty("skip_cookie_prefixes")),
                trimmedOrNull(p.getProperty("after_restore")), null,
                list(p.getProperty("request_time_constants")), ttl,
                trimmedOrNull(p.getProperty("template_host")) != null
                        ? p.getProperty("template_host").trim() : "localhost",
                list(p.getProperty("verify_ignore")));
    }

    private static String required(Properties p, String key, Path file) {
        String value = trimmedOrNull(p.getProperty(key));
        if (value == null) {
            throw new IllegalArgumentException(file + ": " + key + " is required");
        }
        return value;
    }

    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> list(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    public String name() {
        return name;
    }

    /** The boot file, relative to the document root when not absolute. */
    public String boot() {
        return boot;
    }

    /** Entry scripts, relative to the document root. */
    public List<String> entries() {
        return entries;
    }

    public Set<String> methods() {
        return methods;
    }

    public List<String> skipCookiePrefixes() {
        return skipCookiePrefixes;
    }

    public String afterRestore() {
        return afterRestore;
    }

    public List<String> requestTimeConstants() {
        return requestTimeConstants;
    }

    public long ttlMs() {
        return ttlMs;
    }

    public String templateHost() {
        return templateHost;
    }

    /**
     * Fingerprint components that verify must not report when they differ, plus optional
     * {@code response-body:<regex>} entries naming request-dependent spans of the body (a random
     * id a page renders every request). Empty by default; the built-in WordPress profile lists
     * two function-static memos that are request-dependent but cannot change a response (see
     * {@link #WORDPRESS_VERIFY_IGNORE}).
     */
    public List<String> verifyIgnore() {
        return verifyIgnore;
    }

    /**
     * The after-restore program, or null when the profile has none. A resource ships with the
     * runtime; a file path is resolved against the document root. A file keeps its path so the
     * runtime reads it with php's byte encoding, and {@code __FILE__} points at it.
     */
    public Program afterRestoreProgram(Path docRoot) throws IOException {
        if (afterRestoreResource != null) {
            try (InputStream in = SnapshotProfile.class.getClassLoader()
                    .getResourceAsStream(afterRestoreResource)) {
                if (in == null) {
                    throw new IOException("missing runtime resource " + afterRestoreResource);
                }
                return new Program(afterRestoreResource,
                        new String(in.readAllBytes(), StandardCharsets.ISO_8859_1), null);
            }
        }
        if (afterRestore == null) {
            return null;
        }
        Path path = Path.of(afterRestore);
        if (!path.isAbsolute()) {
            path = docRoot.resolve(path);
        }
        path = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IOException("no such after_restore file: " + path);
        }
        return new Program(path.toString(), null, path);
    }

    /**
     * One PHP program shipped with a profile: the source name, the text of a runtime resource
     * ({@code code}), or the path of a profile file ({@code file}).
     */
    public record Program(String name, String code, Path file) {
    }

    /** For the startup line and the report. */
    public Map<String, String> describe() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("boot", boot);
        out.put("entries", String.join(",", entries));
        out.put("methods", String.join(",", methods.stream().sorted().toList()));
        out.put("skip_cookie_prefixes", String.join(",", skipCookiePrefixes));
        out.put("after_restore", afterRestoreResource != null ? afterRestoreResource : String.valueOf(afterRestore));
        out.put("request_time_constants", String.join(",", requestTimeConstants));
        out.put("ttl_ms", Long.toString(ttlMs));
        out.put("template_host", templateHost);
        out.put("verify_ignore", String.join(",", verifyIgnore));
        return out;
    }
}
