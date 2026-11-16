package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.SplInterfaces;
import dev.phpruntime.runtime.SplIterators;

/** SPL inspection and callback functions. The common AST call path uses SplBuiltinNode. */
final class SplFunctions {
    private SplFunctions() {
    }

    static void register() {
        Builtins.def("is_iterable", 1, 1, (ctx, a) -> a[0] instanceof PhpArray || SplInterfaces.has(a[0], SplInterfaces.TRAVERSABLE));
        Builtins.def("is_countable", 1, 1, (ctx, a) -> a[0] instanceof PhpArray || SplInterfaces.has(a[0], SplInterfaces.COUNTABLE));
        for (String name : new String[]{"count", "sizeof"}) {
            Builtins.def(name, 1, 2, (ctx, a) -> {
                if (a[0] instanceof PhpObject obj && SplInterfaces.has(obj, SplInterfaces.COUNTABLE)) {
                    return PhpOps.toLong(SplIterators.call(obj, "count"));
                }
                if (a[0] instanceof PhpArray array) {
                    return (long) array.size();
                }
                throw new PhpError(PhpError.TYPE_ERROR, name + "(): Argument #1 ($value) must be of type Countable|array, "
                        + PhpOps.typeDescription(a[0]) + " given", null);
            });
        }
        Builtins.def("iterator_to_array", 1, 2, (ctx, a) -> {
            boolean preserve = a.length < 2 || PhpOps.toBool(a[1]);
            PhpArray out = new PhpArray();
            var entries = a[0] instanceof PhpArray array ? array.entries()
                    : SplIterators.entries(requireIterator("iterator_to_array", a[0], true), ctx.currentBuiltinCallSite());
            for (var entry : entries) {
                if (preserve) {
                    out.put(entry.getKey(), PhpOps.copyOnAssign(entry.getValue()));
                } else {
                    out.append(PhpOps.copyOnAssign(entry.getValue()));
                }
            }
            return out;
        });
        Builtins.def("iterator_count", 1, 1, (ctx, a) -> a[0] instanceof PhpArray array ? (long) array.size()
                : (long) SplIterators.entries(requireIterator("iterator_count", a[0], true), ctx.currentBuiltinCallSite()).size());
        Builtins.def("iterator_apply", 2, 3, (ctx, a) -> {
            PhpObject it = SplIterators.resolve(requireIterator("iterator_apply", a[0], false));
            PhpCallable cb = PhpCallable.resolve(ctx, a[1], ctx.currentBuiltinCallSite());
            Object[] args = a.length > 2 && a[2] instanceof PhpArray arr ? arr.values().toArray() : new Object[0];
            SplIterators.call(it, "rewind");
            long n = 0;
            while (PhpOps.toBool(SplIterators.call(it, "valid"))) {
                n++;
                if (!PhpOps.toBool(cb.call(ctx, null, ctx.currentBuiltinCallSite(), args))) {
                    break;
                }
                SplIterators.call(it, "next");
            }
            return n;
        });
        Builtins.def("class_implements", 1, 2, (ctx, a) -> {
            PhpClass cls = classArg(ctx, a);
            if (cls == null) {
                return false;
            }
            PhpArray out = new PhpArray();
            collectInterfaces(cls, out);
            return out;
        });
        Builtins.def("class_parents", 1, 2, (ctx, a) -> {
            PhpClass cls = classArg(ctx, a);
            if (cls == null) {
                return false;
            }
            PhpArray out = new PhpArray();
            for (PhpClass parent = cls.parent(); parent != null; parent = parent.parent()) {
                out.put(parent.name(), parent.name());
            }
            return out;
        });
    }

    private static PhpObject requireIterator(String name, Object arg, boolean arrayAllowed) {
        if (arg instanceof PhpObject obj && SplInterfaces.has(obj, SplInterfaces.TRAVERSABLE)) {
            return obj;
        }
        throw new PhpError(PhpError.TYPE_ERROR, name + "(): Argument #1 ($iterator) must be of type Traversable"
                + (arrayAllowed ? "|array" : "") + ", " + PhpOps.typeDescription(arg) + " given", null);
    }

    private static PhpClass classArg(dev.phpruntime.PhpContext ctx, Object[] args) {
        if (args[0] instanceof PhpObject obj) {
            return obj.phpClass();
        }
        return args[0] instanceof String name ? (args.length < 2 || PhpOps.toBool(args[1])
                ? ctx.lookupOrAutoloadClass(name) : ctx.lookupClass(name)) : null;
    }

    private static void collectInterfaces(PhpClass cls, PhpArray out) {
        if (cls.parent() != null) {
            collectInterfaces(cls.parent(), out);
        }
        for (PhpClass iface : cls.interfaces()) {
            out.put(iface.name(), iface.name());
            collectInterfaces(iface, out);
        }
    }
}
