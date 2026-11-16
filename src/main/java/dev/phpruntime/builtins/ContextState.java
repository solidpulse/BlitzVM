package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;

import java.time.ZoneId;

/**
 * Per-request state that a few builtins keep between calls (strtok's position, the default
 * timezone). Kept in a field of the request's {@link PhpContext}, so it needs no locking
 * and goes away with the context.
 */
final class ContextState {

    /** strtok: the string being tokenized and the position of the next token. */
    String strtokSubject;
    int strtokPos;

    /** date_default_timezone_set; PHP defaults to UTC when date.timezone is not configured. */
    ZoneId timezone = DateFunctions.UTC;
    String timezoneName = "UTC";

    /** Last errors/warnings from date parsing (date_get_last_errors, DateTime::getLastErrors). */
    dev.phpruntime.timelib.TimelibErrorContainer lastDateErrors;
    /** mbstring settings are request-local, never cached on AST nodes. */
    String mbEncoding = "UTF-8";
    Object mbSubstitute = 63L;
    java.util.List<String> mbDetectOrder = java.util.List.of("ASCII", "UTF-8");
    int pregLastError;
    final java.util.Map<Long, HashFunctions.HashContext> hashContexts = new java.util.HashMap<>();

    /** mysqli: per-object state keyed by object id, the last connect error and the report mode. */
    MysqliFunctions.State mysqli;

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
}
