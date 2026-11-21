package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.expr.IncludeNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.CallMode;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

import java.util.Arrays;
import java.util.List;

import static dev.phpruntime.builtins.Builtins.def;

/** Phase A/B callable invocation and callback registration builtins. */
final class CallableFunctions {

    static void register() {
        def("call_user_func", 1, -1, CallableFunctions::callUserFunc);
        def("call_user_func_array", 2, 2, CallableFunctions::callUserFuncArray);
        def("forward_static_call", 1, -1, CallableFunctions::callUserFunc);
        def("forward_static_call_array", 2, 2, CallableFunctions::callUserFuncArray);
        def("is_callable", 1, 3, (ctx, a) -> PhpCallable.resolveOrNull(ctx, a[0]) != null);

        def("spl_autoload_register", 0, 3, CallableFunctions::splAutoloadRegister);
        def("spl_autoload_unregister", 1, 1, (ctx, a) -> ctx.unregisterAutoload(PhpOps.copyOnAssign(a[0])));
        def("spl_autoload_functions", 0, 0, (ctx, a) -> ctx.autoloadFunctions());
        def("spl_autoload", 1, 2, CallableFunctions::splAutoload);

        def("register_shutdown_function", 1, -1, CallableFunctions::registerShutdownFunction);
        def("set_error_handler", 1, 2, CallableFunctions::setErrorHandler);
        def("restore_error_handler", 0, 0, (ctx, a) -> ctx.restoreErrorHandler());
        def("set_exception_handler", 1, 1, CallableFunctions::setExceptionHandler);
        def("restore_exception_handler", 0, 0, (ctx, a) -> ctx.restoreExceptionHandler());
    }

    private static Object callUserFunc(PhpContext ctx, Object[] a) {
        PhpCallable cb = PhpCallable.resolve(ctx, a[0], null);
        CallMode previous = ctx.pendingCallMode();
        ctx.setPendingCallMode(CallMode.atBuiltin(ctx.currentBuiltinCallSite(), targetFunction(cb)));
        try {
            return cb.call(ctx, null, null, Arrays.copyOfRange(a, 1, a.length));
        } finally {
            ctx.setPendingCallMode(previous);
        }
    }

    private static dev.phpruntime.nodes.PhpFunctionRootNode targetFunction(PhpCallable cb) {
        return cb.callTarget() == null ? null
                : dev.phpruntime.nodes.PhpFunctionRootNode.of(cb.callTarget().getRootNode());
    }

    private static Object callUserFuncArray(PhpContext ctx, Object[] a) {
        CallMode previous = ctx.pendingCallMode();
        PhpCallable resolved = PhpCallable.resolve(ctx, a[0], ctx.currentBuiltinCallSite());
        ctx.setPendingCallMode(CallMode.atBuiltin(ctx.currentBuiltinCallSite(), targetFunction(resolved)));
        try {
            return callUserFuncArrayBound(ctx, a, resolved);
        } finally {
            ctx.setPendingCallMode(previous);
        }
    }

    private static Object callUserFuncArrayBound(PhpContext ctx, Object[] a, PhpCallable cb) {
        if (!(a[1] instanceof PhpArray arr)) {
            throw new PhpError(PhpError.TYPE_ERROR, "call_user_func_array(): Argument #2 ($args) must be of type array", null);
        }
        if (cb.kind() != PhpCallable.Kind.BUILTIN && cb.kind() != PhpCallable.Kind.MAGIC_CALL
                && cb.callTarget() != null && dev.phpruntime.nodes.PhpFunctionRootNode.of(cb.callTarget().getRootNode()) instanceof dev.phpruntime.nodes.PhpFunctionRootNode fnRoot) {
            if (arr.hasStringKey()) {
                java.util.List<Object> pos = new java.util.ArrayList<>();
                java.util.Map<String, Object> named = new java.util.LinkedHashMap<>();
                boolean seenNamed = false;
                for (Object k : arr.keys()) {
                    Object rawVal = arr.getRaw(k);
                    Object v = rawVal instanceof dev.phpruntime.runtime.PhpReference ref ? ref : PhpOps.copyOnAssign(arr.get(k));
                    if (k instanceof String s) {
                        seenNamed = true;
                        if (named.containsKey(s)) {
                            throw new PhpError("Named parameter $" + s + " overwrites previous argument", ctx.currentBuiltinCallSite());
                        }
                        named.put(s, v);
                    } else {
                        if (seenNamed) {
                            throw new PhpError("Cannot use positional argument after named argument", ctx.currentBuiltinCallSite());
                        }
                        pos.add(v);
                    }
                }
                for (int i = 0; i < pos.size(); i++) {
                    if (fnRoot.isParamByRef(i) && !(pos.get(i) instanceof dev.phpruntime.runtime.PhpReference)) {
                        String pName = fnRoot.paramName(i);
                        String desc = pName != null ? fnRoot.qualifiedCallableName() + "(): Argument #" + (i + 1) + " ($" + pName + ")"
                                : fnRoot.qualifiedCallableName() + "(): Argument #" + (i + 1);
                        ctx.warning(desc + " must be passed by reference, value given", 0);
                        pos.set(i, new dev.phpruntime.runtime.PhpReference(PhpOps.copyOnAssign(pos.get(i))));
                    }
                }
                for (java.util.Map.Entry<String, Object> e : named.entrySet()) {
                    int pIdx = -1;
                    String[] pNames = fnRoot.paramNames();
                    for (int i = 0; i < fnRoot.normalParamCount(); i++) {
                        if (e.getKey().equals(pNames[i])) {
                            pIdx = i;
                            break;
                        }
                    }
                    boolean needsRef = pIdx >= 0 ? fnRoot.isParamByRef(pIdx)
                            : (fnRoot.isVariadic() && fnRoot.isParamByRef(fnRoot.normalParamCount()));
                    if (needsRef && !(e.getValue() instanceof dev.phpruntime.runtime.PhpReference)) {
                        int argNum = pIdx >= 0 ? pIdx + 1 : fnRoot.normalParamCount() + 1;
                        ctx.warning(fnRoot.qualifiedCallableName() + "(): Argument #" + argNum + " ($" + e.getKey()
                                + ") must be passed by reference, value given", 0);
                        e.setValue(new dev.phpruntime.runtime.PhpReference(PhpOps.copyOnAssign(e.getValue())));
                    }
                }
                Object[] prefix = switch (cb.kind()) {
                    case CLOSURE -> new Object[]{cb.boundThis(), cb.calledClass(), cb.closureObj()};
                    case METHOD -> new Object[]{cb.boundThis(), cb.calledClass()};
                    default -> new Object[0];
                };
                dev.phpruntime.nodes.call.CallArgHelper.RawCallArgs rawArgs =
                        new dev.phpruntime.nodes.call.CallArgHelper.RawCallArgs(pos, List.of(), List.of(), List.of(), named, null, null, null);
                Object[] frameArgs = dev.phpruntime.nodes.call.CallArgHelper.bindToFunction(prefix, rawArgs, fnRoot, ctx.currentBuiltinCallSite());
                return dev.phpruntime.nodes.call.CallArgHelper.unwrapReturn(cb.callTarget().call(frameArgs));
            }
        }
        return cb.call(ctx, null, ctx.currentBuiltinCallSite(), arr.rawValueArray());
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
        String cls = dev.phpruntime.runtime.PhpStrings.lower(PhpOps.toStr(a[0]));
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

    private static Object setExceptionHandler(PhpContext ctx, Object[] a) {
        Object cb = a[0] == PhpNull.INSTANCE ? PhpNull.INSTANCE : PhpOps.copyOnAssign(a[0]);
        if (cb != PhpNull.INSTANCE) {
            PhpCallable.resolve(ctx, cb, null);
        }
        return ctx.setExceptionHandler(cb);
    }
}
