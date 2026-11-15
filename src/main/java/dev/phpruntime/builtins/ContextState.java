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

    /** mysqli: per-object state keyed by object id, the last connect error and the report mode. */
    MysqliFunctions.State mysqli;

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
