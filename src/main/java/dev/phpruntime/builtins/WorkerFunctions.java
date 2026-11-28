package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.WorkerHandler;

/**
 * The FrankenPHP worker functions. They are registered always, but
 * {@code function_exists('frankenphp_handle_request')} is true only on a worker context; the
 * runtime has no worker handler anywhere else. {@code fastcgi_finish_request} is FrankenPHP's
 * alias of {@code frankenphp_finish_request}; frameworks call it when it exists to run deferred
 * work after the response is sent, so it is worker-only too until the other serve modes can
 * finish a response early.
 */
final class WorkerFunctions {

    private WorkerFunctions() {
    }

    /** Whether a builtin name exists in this context; the FrankenPHP functions are worker-only. */
    static boolean available(PhpContext ctx, String name) {
        return ctx.workerHandler() != null || !isWorkerFunction(name);
    }

    static boolean isWorkerFunction(String name) {
        String lower = dev.phpruntime.runtime.PhpStrings.lower(name);
        return lower.startsWith("frankenphp_") || lower.equals("fastcgi_finish_request");
    }

    static void register() {
        Builtins.def("frankenphp_handle_request", 1, 1, (ctx, a) -> {
            WorkerHandler handler = ctx.workerHandler();
            if (handler == null) {
                throw new PhpError("Call to undefined function frankenphp_handle_request()");
            }
            PhpCallable callback = PhpCallable.resolve(ctx, a[0], ctx.currentBuiltinCallSite());
            return handler.handleRequest(ctx, callback);
        });
        defFinish("frankenphp_finish_request");
        defFinish("fastcgi_finish_request");
    }

    private static void defFinish(String name) {
        Builtins.def(name, 0, 0, (ctx, a) -> {
            WorkerHandler handler = ctx.workerHandler();
            if (handler == null) {
                throw new PhpError("Call to undefined function " + name + "()");
            }
            return handler.finishRequest(ctx);
        });
    }
}
