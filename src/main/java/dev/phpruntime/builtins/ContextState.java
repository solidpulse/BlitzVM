package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Per-request state that a few builtins keep between calls (strtok's position, the default
 * timezone). Held outside {@link PhpContext} so builtins can add state without touching it;
 * the weak map lets the entry go when the request's context is collected.
 */
final class ContextState {

    private static final Map<PhpContext, ContextState> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    /** strtok: the string being tokenized and the position of the next token. */
    String strtokSubject;
    int strtokPos;

    /** date_default_timezone_set; PHP defaults to UTC when date.timezone is not configured. */
    ZoneId timezone = ZoneOffset.UTC;
    String timezoneName = "UTC";

    private ContextState() {
    }

    static ContextState of(PhpContext ctx) {
        return STATES.computeIfAbsent(ctx, c -> new ContextState());
    }
}
