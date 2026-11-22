package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.WorkerHandler;

/**
 * The FrankenPHP worker functions. They are registered always, but
 * {@code function_exists('frankenphp_handle_request')} is true only on a worker context; the
 * runtime has no worker handler anywhere else.
 */
final class WorkerFunctions {

    private WorkerFunctions() {
    }

    /** Whether a builtin name exists in this context; the FrankenPHP functions are worker-only. */
    static boolean available(PhpContext ctx, String name) {
        return ctx.workerHandler() != null || !isWorkerFunction(name);
    }

    static boolean isWorkerFunction(String name) {
        return dev.phpruntime.runtime.PhpStrings.lower(name).startsWith("frankenphp_");
    }

    static void register() {        Builtins.def("frankenphp_handle_request", 1, 1, (ctx, a) -> {
            WorkerHandler handler = ctx.workerHandler();
            if (handler == null) {
                throw new PhpError("Call to undefined function frankenphp_handle_request()");
            }
            PhpCallable callback = PhpCallable.resolve(ctx, a[0], ctx.currentBuiltinCallSite());
            return handler.handleRequest(ctx, callback);
        });
        Builtins.def("frankenphp_finish_request", 0, 0, (ctx, a) -> {
            WorkerHandler handler = ctx.workerHandler();
            if (handler == null) {
                throw new PhpError("Call to undefined function frankenphp_finish_request()");
            }
            handler.finishRequest(ctx);
            return PhpNull.INSTANCE;
        });
    }
}
