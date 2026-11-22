package dev.phpruntime.runtime;

import dev.phpruntime.PhpContext;

/**
 * Host side of FrankenPHP-style worker mode: the object behind
 * {@code frankenphp_handle_request()} and {@code frankenphp_finish_request()}.
 *
 * <p>One handler belongs to one long-lived worker context. The guest calls it from its
 * worker script; while it waits for a request it holds no scheduler slot.
 */
public interface WorkerHandler {

    /**
     * Blocks until a request arrives, runs {@code callback} with that request installed, sends
     * the response, clears the request state and returns whether the worker should keep running.
     */
    boolean handleRequest(PhpContext ctx, PhpCallable callback);

    /** {@code frankenphp_finish_request()}: send the response now and keep executing. */
    void finishRequest(PhpContext ctx);
}
