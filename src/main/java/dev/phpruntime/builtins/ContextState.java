package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;

import java.time.ZoneId;

/**
 * Per-request state that a few builtins keep between calls (strtok's position, the default
 * timezone). Kept in a field of the request's {@link PhpContext}, so it needs no locking
 * and goes away with the context.
 */
public final class ContextState {

    /** strtok: the string being tokenized and the position of the next token. */
    String strtokSubject;
    int strtokPos;

    /** date_default_timezone_set; PHP defaults to UTC when date.timezone is not configured. */
    ZoneId timezone = DateFunctions.UTC;
    String timezoneName = "UTC";

    /** Last errors/warnings from date parsing (date_get_last_errors, DateTime::getLastErrors). */
    dev.phpruntime.timelib.TimelibErrorContainer lastDateErrors;
    /** ignore_user_abort(): 0 or 1; PHP starts at 0. */
    long ignoreUserAbort;
    /** gc_enabled(); the runtime has no collector but scripts still observe the toggle. */
    boolean gcEnabled = true;
    /** Locale::setDefault(); null means the ICU default (en_US_POSIX). */
    String intlDefaultLocale;
    /** The next openssl_error_string() message, or null when the queue is empty. */
    String opensslError;
    /** putenv() changes for this request; null values mean the variable was unset. */
    java.util.Map<String, String> envOverrides = new java.util.HashMap<>();
    /** mbstring settings are request-local, never cached on AST nodes. */
    String mbEncoding = "UTF-8";
    Object mbSubstitute = 63L;
    java.util.List<String> mbDetectOrder = java.util.List.of("ASCII", "UTF-8");
    int pregLastError;
    final java.util.Map<Long, HashFunctions.HashContext> hashContexts = new java.util.HashMap<>();
    /** unserialize_callback_func: name of the function called when a serialized class is missing. */
    String unserializeCallbackFunc = "";

    /** mysqli: per-object state keyed by object id, the last connect error and the report mode. */
    MysqliFunctions.State mysqli;

    /** PDO: connections and statements keyed by object id, all owned by this request. */
    PdoFunctions.State pdo;

    /** Next resource id. PHP's CLI has STDIN, STDOUT and STDERR as 1 to 3, so the first is 4. */
    long nextResourceId = 4;
    /** Whether the default stream context has taken its id (the first stream open does this). */
    boolean defaultStreamContext;
    /** The directory handle readdir() and friends use when called without one. */
    dev.phpruntime.runtime.PhpResource lastDir;
    /** umask(): kept per request; files are created with the host's own umask. */
    long umask = 022;

    private ContextState() {
    }

    static ContextState of(PhpContext ctx) {
        Object state = ctx.builtinState();
        if (state == null) {
            state = new ContextState();
            ctx.setBuiltinState(state);
        }
        return (ContextState) state;
    }

    // ------------------------------------------------------------ snapshot support

    /** The putenv() overrides this request has made, in insertion order; null means unset. */
    public static java.util.Map<String, String> envOverrides(PhpContext ctx) {
        return new java.util.LinkedHashMap<>(of(ctx).envOverrides);
    }

    /** Replays one putenv() effect in a restored request. */
    public static void putEnvOverride(PhpContext ctx, String name, String value) {
        of(ctx).envOverrides.put(name, value);
    }

    /** Reasons this request's builtin state cannot go into a boot snapshot, in a stable order. */
    public static java.util.List<String> snapshotRefusals(PhpContext ctx) {
        java.util.List<String> reasons = new java.util.ArrayList<>();
        Object state = ctx.builtinState();
        if (!(state instanceof ContextState cs)) {
            return reasons;
        }
        if (cs.mysqli != null) {
            reasons.addAll(MysqliFunctions.snapshotRefusals(ctx));
        }
        if (cs.pdo != null) {
            reasons.add("PDO state was left by boot");
        }
        if (!cs.hashContexts.isEmpty()) {
            reasons.add("a hash context was left by boot");
        }
        if (cs.lastDir != null && !cs.lastDir.isClosed()) {
            reasons.add("an open file, stream or directory handle was left by boot");
        }
        return reasons;
    }

    /** The copyable part of this request's builtin state. */
    public static ContextStateSnapshot snapshot(PhpContext ctx) {
        ContextState cs = of(ctx);
        return new ContextStateSnapshot(cs.timezoneName, cs.mbEncoding, cs.mbSubstitute,
                cs.mbDetectOrder, cs.nextResourceId, cs.defaultStreamContext, cs.umask, cs.ignoreUserAbort,
                MysqliFunctions.snapshot(ctx));
    }

    public static void restore(PhpContext ctx, ContextStateSnapshot snap) {
        ContextState cs = of(ctx);
        cs.timezoneName = snap.timezoneName();
        cs.timezone = DateFunctions.UTC;
        try {
            cs.timezone = java.time.ZoneId.of(snap.timezoneName());
        } catch (java.time.DateTimeException ignored) {
            // keep UTC, as php does for a name the runtime does not know
        }
        cs.mbEncoding = snap.mbEncoding();
        cs.mbSubstitute = snap.mbSubstitute();
        cs.mbDetectOrder = java.util.List.copyOf(snap.mbDetectOrder());
        cs.nextResourceId = snap.nextResourceId();
        cs.defaultStreamContext = snap.defaultStreamContext();
        cs.umask = snap.umask();
        cs.ignoreUserAbort = snap.ignoreUserAbort();
        cs.mysqli = MysqliFunctions.restore(ctx, snap.mysqli());
    }
}
