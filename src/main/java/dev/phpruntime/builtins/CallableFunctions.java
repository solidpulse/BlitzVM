package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.expr.IncludeNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

import java.util.Arrays;

import static dev.phpruntime.builtins.Builtins.def;

/** Phase A/B callable invocation and callback registration builtins. */
final class CallableFunctions {

    static void register() {
        def("call_user_func", 1, -1, CallableFunctions::callUserFunc);
        def("call_user_func_array", 2, 2, CallableFunctions::callUserFuncArray);
        def("is_callable", 1, 3, (ctx, a) -> PhpCallable.resolveOrNull(ctx, a[0]) != null);

        def("spl_autoload_register", 0, 3, CallableFunctions::splAutoloadRegister);
        def("spl_autoload_unregister", 1, 1, (ctx, a) -> ctx.unregisterAutoload(PhpOps.copyOnAssign(a[0])));
        def("spl_autoload_functions", 0, 0, (ctx, a) -> ctx.autoloadFunctions());
        def("spl_autoload", 1, 2, CallableFunctions::splAutoload);

        def("register_shutdown_function", 1, -1, CallableFunctions::registerShutdownFunction);
        def("set_error_handler", 1, 2, CallableFunctions::setErrorHandler);
        def("restore_error_handler", 0, 0, (ctx, a) -> ctx.restoreErrorHandler());
    }

    private static Object callUserFunc(PhpContext ctx, Object[] a) {
        PhpCallable cb = PhpCallable.resolve(ctx, a[0], null);
        return cb.call(ctx, null, null, Arrays.copyOfRange(a, 1, a.length));
    }

    private static Object callUserFuncArray(PhpContext ctx, Object[] a) {
        if (!(a[1] instanceof PhpArray arr)) {
            throw new PhpError(PhpError.TYPE_ERROR, "call_user_func_array(): Argument #2 ($args) must be of type array", null);
        }
        PhpCallable cb = PhpCallable.resolve(ctx, a[0], null);
        return cb.call(ctx, null, null, arr.values().toArray());
    }

    private static Object splAutoloadRegister(PhpContext ctx, Object[] a) {
        Object callback = a.length == 0 || a[0] == PhpNull.INSTANCE ? "spl_autoload" : PhpOps.copyOnAssign(a[0]);
        if (a.length > 0 && a[0] != PhpNull.INSTANCE) {
            PhpCallable.resolve(ctx, callback, null);
        }
        boolean prepend = a.length > 2 && PhpOps.toBool(a[2]);
        return ctx.registerAutoload(callback, prepend);
    }

    private static Object splAutoload(PhpContext ctx, Object[] a) {
        String cls = PhpOps.toStr(a[0]).toLowerCase();
        String exts = a.length > 1 && a[1] != PhpNull.INSTANCE ? PhpOps.toStr(a[1]) : ".php,.inc";
        for (String ext : exts.split(",")) {
            if (IncludeNode.tryIncludeOnce(ctx, cls + ext.trim())) {
                return PhpNull.INSTANCE;
            }
        }
        return PhpNull.INSTANCE;
    }

    private static Object registerShutdownFunction(PhpContext ctx, Object[] a) {
        PhpCallable.resolve(ctx, a[0], null);
        ctx.registerShutdownFunction(PhpOps.copyOnAssign(a[0]), Arrays.copyOfRange(a, 1, a.length));
        return PhpNull.INSTANCE;
    }

    private static Object setErrorHandler(PhpContext ctx, Object[] a) {
        Object cb = a[0] == PhpNull.INSTANCE ? PhpNull.INSTANCE : PhpOps.copyOnAssign(a[0]);
        if (cb != PhpNull.INSTANCE) {
            PhpCallable.resolve(ctx, cb, null);
        }
        long levels = a.length > 1 ? PhpOps.toLong(a[1]) : 32767L;
        return ctx.setErrorHandler(cb, levels);
    }
}
