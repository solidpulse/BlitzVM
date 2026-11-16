// Ported from timelib 2022.17 (ext/date/lib/parse_tz.c),
// Copyright (c) 2015-2021 Derick Rethans, Copyright (c) 2017-2019,2021 MongoDB, Inc.,
// MIT License (see src/main/resources/META-INF/LICENSE-timelib).

package dev.phpruntime.timelib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static dev.phpruntime.timelib.TimelibConstants.*;

/**
 * Engine-wide immutable timezone database backed by system zoneinfo ({@code TZDIR} or
 * {@code /usr/share/zoneinfo}).
 *
 * <p>Parsed {@link TimelibTzinfo} instances are cached engine-wide and must not be mutated after
 * construction.
 */
public final class ZoneinfoDb {

    private static final Set<String> SKIP_TOP_LEVEL = Set.of(
            "posix", "right", "localtime", "posixrules", "tzdata.zi",
            "iso3166.tab", "zone.tab", "zone1970.tab", "zonenow.tab",
            "leapseconds", "leap-seconds.list", "SECURITY");

    private static final ZoneinfoDb INSTANCE = new ZoneinfoDb();

    private final Path tzdir;
    private final boolean available;
    private final Map<String, String> lowerToCanonical;
    private final List<String> identifiers;
    private final ConcurrentHashMap<String, TimelibTzinfo> cache = new ConcurrentHashMap<>();

    private ZoneinfoDb() {
        String env = System.getenv("TZDIR");
        Path dir = Path.of(env != null && !env.isEmpty() ? env : "/usr/share/zoneinfo");
        this.tzdir = dir;
        if (!Files.isDirectory(dir)) {
            this.available = false;
            this.lowerToCanonical = Map.of();
            this.identifiers = List.of();
            return;
        }
        this.available = true;

        Map<String, String> map = new HashMap<>();
        List<String> ids = new ArrayList<>();

        // Prefer tzdata.zi for the canonical identifier list if present, then also walk dir for any
        // extra symlinks/files (e.g. CET, EST, UTC, Etc/UTC).
        Path tzdataZi = dir.resolve("tzdata.zi");
        if (Files.isRegularFile(tzdataZi)) {
            try {
                for (String line : Files.readAllLines(tzdataZi)) {
                    if (line.startsWith("Z ")) {
                        int sp = line.indexOf(' ', 2);
                        String id = sp > 2 ? line.substring(2, sp) : line.substring(2);
                        if (!id.isEmpty() && map.putIfAbsent(id.toLowerCase(Locale.ROOT), id) == null) {
                            ids.add(id);
                        }
                    } else if (line.startsWith("L ")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 3) {
                            String id = parts[2];
                            if (!id.isEmpty() && map.putIfAbsent(id.toLowerCase(Locale.ROOT), id) == null) {
                                ids.add(id);
                            }
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }

        try (Stream<Path> walk = Files.walk(dir)) {
            walk.forEach(p -> {
                if (!Files.isRegularFile(p)) {
                    return;
                }
                String rel = dir.relativize(p).toString().replace('\\', '/');
                int slash = rel.indexOf('/');
                String top = slash >= 0 ? rel.substring(0, slash) : rel;
                if (SKIP_TOP_LEVEL.contains(top) || rel.endsWith(".tab") || rel.endsWith(".zi")) {
                    return;
                }
                if (map.putIfAbsent(rel.toLowerCase(Locale.ROOT), rel) == null) {
                    ids.add(rel);
                }
            });
        } catch (IOException ignored) {
        }

        Collections.sort(ids);
        this.lowerToCanonical = Collections.unmodifiableMap(map);
        this.identifiers = Collections.unmodifiableList(ids);
    }

    public static ZoneinfoDb instance() {
        return INSTANCE;
    }

    public boolean isAvailable() {
        return available;
    }

    /** C: {@code timelib_timezone_id_is_valid} */
    public boolean isValid(String timezone) {
        if (!available || timezone == null || timezone.isEmpty()) {
            return false;
        }
        return lowerToCanonical.containsKey(timezone.toLowerCase(Locale.ROOT));
    }

    public String canonicalName(String timezone) {
        if (!available || timezone == null || timezone.isEmpty()) {
            return null;
        }
        return lowerToCanonical.get(timezone.toLowerCase(Locale.ROOT));
    }

    public List<String> identifiers() {
        return identifiers;
    }

    /** C: {@code timelib_parse_tzfile} */
    public TimelibTzinfo parseTzfile(String timezone, int[] errorCode) {
        if (!available || timezone == null || timezone.isEmpty()) {
            errorCode[0] = TIMELIB_ERROR_NO_SUCH_TIMEZONE;
            return null;
        }
        String canonical = lowerToCanonical.get(timezone.toLowerCase(Locale.ROOT));
        if (canonical == null) {
            errorCode[0] = TIMELIB_ERROR_NO_SUCH_TIMEZONE;
            return null;
        }
        TimelibTzinfo cached = cache.get(canonical);
        if (cached != null) {
            errorCode[0] = TIMELIB_ERROR_NO_ERROR;
            return cached;
        }
        Path file = tzdir.resolve(canonical).normalize();
        if (!file.startsWith(tzdir.normalize()) || !Files.isRegularFile(file)) {
            errorCode[0] = TIMELIB_ERROR_NO_SUCH_TIMEZONE;
            return null;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            errorCode[0] = TIMELIB_ERROR_NO_SUCH_TIMEZONE;
            return null;
        }
        TimelibTzinfo parsed = ParseTz.parseTzfileBytes(canonical, bytes, errorCode);
        if (parsed != null) {
            TimelibTzinfo prev = cache.putIfAbsent(canonical, parsed);
            if (prev != null) {
                return prev;
            }
        }
        return parsed;
    }
}
