package dev.phpruntime.builtins;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpRootNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;

/** Class, interface, and object inspection builtins. */
final class ClassFunctions {

    private ClassFunctions() {
    }

    static void register() {
        Builtins.def("is_object", 1, 1, (ctx, a) -> a[0] instanceof PhpObject);

        Builtins.def("get_class", 0, 1, (ctx, a) -> {
            if (a.length == 0) {
                String caller = callingClass();
                if (caller == null) {
                    throw new PhpError("get_class() without arguments must be called from within a class");
                }
                PhpClass cls = ctx.lookupClass(caller);
                return cls != null ? cls.name() : caller;
            }
            if (a[0] instanceof PhpObject obj) {
                return obj.phpClass().name();
            }
            throw new PhpError(
                    PhpError.TYPE_ERROR,
                    "get_class(): Argument #1 ($object) must be of type object, " + PhpOps.typeName(a[0]) + " given",
                    null);
        });

        Builtins.def("get_parent_class", 0, 1, (ctx, a) -> {
            PhpClass cls;
            if (a.length == 0) {
                String caller = callingClass();
                if (caller == null) {
                    return false;
                }
                cls = ctx.lookupClass(caller);
            } else {
                cls = resolveClassArg(ctx, a[0]);
            }
            return cls != null && cls.parent() != null ? cls.parent().name() : false;
        });

        Builtins.def("class_exists", 1, 2, (ctx, a) -> {
            boolean autoload = a.length < 2 || PhpOps.toBool(a[1]);
            String name = PhpOps.toStr(a[0]);
            PhpClass cls = autoload ? ctx.lookupOrAutoloadClass(name) : ctx.lookupClass(name);
            return cls != null && !cls.isInterface();
        });

        Builtins.def("interface_exists", 1, 2, (ctx, a) -> {
            boolean autoload = a.length < 2 || PhpOps.toBool(a[1]);
            String name = PhpOps.toStr(a[0]);
            PhpClass cls = autoload ? ctx.lookupOrAutoloadClass(name) : ctx.lookupClass(name);
            return cls != null && cls.isInterface();
        });

        Builtins.def("method_exists", 2, 2, (ctx, a) -> {
            PhpClass cls = resolveClassArg(ctx, a[0]);
            return cls != null && cls.hasMethod(PhpOps.toStr(a[1]));
        });

        Builtins.def("property_exists", 2, 2, (ctx, a) -> {
            String prop = PhpOps.toStr(a[1]);
            if (a[0] instanceof PhpObject obj) {
                if (obj.phpClass().hasDeclaredProperty(prop)) {
                    return true;
                }
                return DynamicObject.ContainsKeyNode.getUncached().execute(obj, prop);
            }
            PhpClass cls = resolveClassArg(ctx, a[0]);
            return cls != null && cls.hasDeclaredProperty(prop);
        });

        Builtins.def("is_a", 2, 3, (ctx, a) -> {
            boolean allowString = a.length >= 3 && PhpOps.toBool(a[2]);
            PhpClass subject = resolveSubjectClass(ctx, a[0], allowString);
            PhpClass target = ctx.lookupClass(PhpOps.toStr(a[1]));
            return subject != null && target != null && subject.isInstanceOf(target);
        });

        Builtins.def("is_subclass_of", 2, 3, (ctx, a) -> {
            boolean allowString = a.length < 3 || PhpOps.toBool(a[2]);
            PhpClass subject = resolveSubjectClass(ctx, a[0], allowString);
            PhpClass target = ctx.lookupClass(PhpOps.toStr(a[1]));
            return subject != null && target != null && subject != target && subject.isInstanceOf(target);
        });

        Builtins.def("get_object_vars", 1, 1, (ctx, a) -> {
            if (!(a[0] instanceof PhpObject obj)) {
                throw new PhpError(
                        PhpError.TYPE_ERROR,
                        "get_object_vars(): Argument #1 ($object) must be of type object, "
                                + PhpOps.typeName(a[0]) + " given",
                        null);
            }
            return collectVisibleVars(ctx, obj, callingClass());
        });

        Builtins.def("spl_object_id", 1, 1, (ctx, a) -> requireObject("spl_object_id", a[0]).id());
        Builtins.def("spl_object_hash", 1, 1, (ctx, a) ->
                String.format("%032x", requireObject("spl_object_hash", a[0]).id()));
    }

    static PhpArray collectVisibleVars(PhpContext ctx, PhpObject obj, String callerClassName) {
        PhpArray result = new PhpArray();
        for (var e : PhpClass.visibleEntries(ctx, obj, callerClassName)) {
            result.put(e.getKey(), PhpOps.copyOnAssign(e.getValue()));
        }
        return result;
    }

    private static PhpObject requireObject(String fn, Object arg) {
        if (arg instanceof PhpObject obj) {
            return obj;
        }
        throw new PhpError(
                PhpError.TYPE_ERROR,
                fn + "(): Argument #1 ($object) must be of type object, " + PhpOps.typeName(arg) + " given",
                null);
    }

    private static PhpClass resolveClassArg(PhpContext ctx, Object arg) {
        if (arg instanceof PhpObject obj) {
            return obj.phpClass();
        }
        if (arg instanceof String s) {
            return ctx.lookupClass(s);
        }
        return null;
    }

    private static PhpClass resolveSubjectClass(PhpContext ctx, Object arg, boolean allowString) {
        if (arg instanceof PhpObject obj) {
            return obj.phpClass();
        }
        if (allowString && arg instanceof String s) {
            return ctx.lookupClass(s);
        }
        return null;
    }

    private static String callingClass() {
        String[] found = new String[1];
        Truffle.getRuntime().iterateFrames(frameInstance -> {
            if (frameInstance.getCallTarget() instanceof RootCallTarget rct
                    && rct.getRootNode() instanceof PhpRootNode root) {
                if (root instanceof PhpFunctionRootNode fn) {
                    found[0] = fn.declaringClass();
                }
                return Boolean.TRUE;
            }
            return null;
        });
        return found[0];
    }
}
