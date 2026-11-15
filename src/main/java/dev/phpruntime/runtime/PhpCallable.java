package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.PhpContext;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.call.CallNode;

/**
 * Unified runtime representation and invocation helper for all PHP callables:
 * {@code Closure} objects, function name strings, {@code "Class::method"} strings,
 * {@code [$obj, 'method']} / {@code ['Class', 'method']} arrays, and invokable objects ({@code __invoke}).
 */
public final class PhpCallable {

    public enum Kind {
        CLOSURE,
        USER_FUNCTION,
        METHOD,
        MAGIC_CALL,
        BUILTIN
    }

    private final Kind kind;
    private final PhpObject closureObj;
    private final RootCallTarget callTarget;
    private final Builtins.Def builtin;
    private final Object boundThis;
    private final PhpClass calledClass;
    private final String magicMethodName;

    private PhpCallable(Kind kind, PhpObject closureObj, RootCallTarget callTarget, Builtins.Def builtin,
                        Object boundThis, PhpClass calledClass, String magicMethodName) {
        this.kind = kind;
        this.closureObj = closureObj;
        this.callTarget = callTarget;
        this.builtin = builtin;
        this.boundThis = boundThis;
        this.calledClass = calledClass;
        this.magicMethodName = magicMethodName;
    }

    public Kind kind() {
        return kind;
    }

    public PhpObject closureObj() {
        return closureObj;
    }

    public RootCallTarget callTarget() {
        return callTarget;
    }

    public Builtins.Def builtin() {
        return builtin;
    }

    public Object boundThis() {
        return boundThis;
    }

    public PhpClass calledClass() {
        return calledClass;
    }

    public String magicMethodName() {
        return magicMethodName;
    }

    @TruffleBoundary
    public static PhpCallable resolve(PhpContext ctx, Object val, Node loc) {
        return resolve(ctx, val, PhpNull.INSTANCE, loc);
    }

    @TruffleBoundary
    public static PhpCallable resolve(PhpContext ctx, Object val, Object callerThis, Node loc) {
        PhpCallable cb = resolveOrNull(ctx, val, callerThis);
        if (cb == null) {
            throw new PhpError("Value not of type callable", loc);
        }
        return cb;
    }

    @TruffleBoundary
    public static PhpCallable resolveOrNull(PhpContext ctx, Object val) {
        return resolveOrNull(ctx, val, PhpNull.INSTANCE);
    }

    @TruffleBoundary
    public static PhpCallable resolveOrNull(PhpContext ctx, Object val, Object callerThis) {
        if (val instanceof PhpObject obj) {
            if (obj.closure() != null) {
                PhpClosure c = obj.closure();
                return switch (c.kind()) {
                    case CLOSURE -> new PhpCallable(Kind.CLOSURE, obj, c.callTarget(), null, c.boundThis(), c.calledClass(), null);
                    case USER_FUNCTION -> new PhpCallable(Kind.USER_FUNCTION, null, c.callTarget(), null, PhpNull.INSTANCE, null, null);
                    case METHOD -> new PhpCallable(Kind.METHOD, null, c.callTarget(), null, c.boundThis(), c.calledClass(), null);
                    case MAGIC_CALL -> new PhpCallable(Kind.MAGIC_CALL, null, c.callTarget(), null, c.boundThis(), c.calledClass(), c.magicMethodName());
                    case BUILTIN -> new PhpCallable(Kind.BUILTIN, null, null, c.builtin(), PhpNull.INSTANCE, null, null);
                };
            }
            PhpClass.MethodEntry invoke = obj.phpClass().lookupMethod("__invoke");
            if (invoke != null && invoke.callTarget() != null) {
                return new PhpCallable(Kind.METHOD, null, invoke.callTarget(), null, obj, obj.phpClass(), null);
            }
            return null;
        }
        if (val instanceof String s) {
            int sep = s.indexOf("::");
            if (sep > 0) {
                return resolveStaticMethod(ctx, s.substring(0, sep), s.substring(sep + 2), callerThis);
            }
            PhpFunction fn = ctx.lookupFunction(s);
            if (fn != null) {
                return new PhpCallable(Kind.USER_FUNCTION, null, fn.callTarget(), null, PhpNull.INSTANCE, null, null);
            }
            Builtins.Def def = Builtins.lookup(s);
            if (def != null) {
                return new PhpCallable(Kind.BUILTIN, null, null, def, PhpNull.INSTANCE, null, null);
            }
            return null;
        }
        if (val instanceof PhpArray arr && arr.size() == 2) {
            Object el0 = arr.get(0L);
            Object el1 = arr.get(1L);
            if (!(el1 instanceof String methName)) {
                return null;
            }
            if (el0 instanceof PhpObject obj) {
                PhpClass cls = obj.phpClass();
                PhpClass.MethodEntry entry = cls.lookupMethod(methName);
                if (entry != null) {
                    if (entry.callTarget() == null) {
                        return null;
                    }
                    Object thisArg = entry.isStatic() ? PhpNull.INSTANCE : obj;
                    return new PhpCallable(Kind.METHOD, null, entry.callTarget(), null, thisArg, cls, null);
                }
                PhpClass.MethodEntry magic = cls.callMethod();
                if (magic != null && magic.callTarget() != null) {
                    return new PhpCallable(Kind.MAGIC_CALL, null, magic.callTarget(), null, obj, cls, methName);
                }
                return null;
            }
            if (el0 instanceof String clsName) {
                return resolveStaticMethod(ctx, clsName, methName, callerThis);
            }
        }
        return null;
    }

    private static PhpCallable resolveStaticMethod(PhpContext ctx, String clsName, String methName, Object callerThis) {
        PhpClass cls = ctx.lookupOrAutoloadClass(clsName);
        if (cls == null) {
            return null;
        }
        PhpClass.MethodEntry entry = cls.lookupMethod(methName);
        if (entry != null) {
            if (entry.callTarget() == null) {
                return null;
            }
            if (entry.isStatic()) {
                return new PhpCallable(Kind.METHOD, null, entry.callTarget(), null, PhpNull.INSTANCE, cls, null);
            }
            if (callerThis instanceof PhpObject co && co.phpClass().isInstanceOf(entry.declaringClass())) {
                return new PhpCallable(Kind.METHOD, null, entry.callTarget(), null, co, co.phpClass(), null);
            }
            return null;
        }
        PhpClass.MethodEntry magicStatic = cls.callStaticMethod();
        if (magicStatic != null && magicStatic.callTarget() != null) {
            return new PhpCallable(Kind.MAGIC_CALL, null, magicStatic.callTarget(), null, PhpNull.INSTANCE, cls, methName);
        }
        return null;
    }

    /** Wraps any resolved callable (or an existing Closure) into a {@code Closure} {@link PhpObject}. */
    @TruffleBoundary
    public static PhpObject toClosure(PhpContext ctx, Object val, Object callerThis, Node loc) {
        if (val instanceof PhpObject obj && obj.closure() != null) {
            return obj;
        }
        PhpCallable cb = resolve(ctx, val, callerThis, loc);
        PhpClosure closure = switch (cb.kind) {
            case CLOSURE -> throw new IllegalStateException();
            case USER_FUNCTION -> PhpClosure.userFunction(cb.callTarget);
            case METHOD -> PhpClosure.method(cb.callTarget, cb.boundThis, cb.calledClass);
            case MAGIC_CALL -> PhpClosure.magicCall(cb.callTarget, cb.boundThis, cb.calledClass, cb.magicMethodName);
            case BUILTIN -> PhpClosure.builtin(cb.builtin);
        };
        return ctx.newClosure(closure);
    }

    /** Invokes this callable via {@code indirectCall} (or uncached {@code RootCallTarget.call} when null). */
    @TruffleBoundary
    public Object call(PhpContext ctx, IndirectCallNode indirectCall, Node loc, Object... userArgs) {
        return switch (kind) {
            case CLOSURE -> invokeTarget(indirectCall, callTarget, buildClosureArgs(boundThis, calledClass, closureObj, userArgs));
            case USER_FUNCTION -> invokeTarget(indirectCall, callTarget, copyArgs(userArgs));
            case METHOD -> invokeTarget(indirectCall, callTarget, buildMethodArgs(boundThis, calledClass, userArgs));
            case MAGIC_CALL -> invokeTarget(indirectCall, callTarget,
                    new Object[]{boundThis, calledClass, magicMethodName, buildMagicArray(userArgs)});
            case BUILTIN -> callBuiltin(ctx, builtin, userArgs, loc);
        };
    }

    /** Invokes a {@link PhpClosure} directly without allocating a temporary {@link PhpCallable}. */
    @TruffleBoundary
    public static Object invokeClosure(PhpContext ctx, IndirectCallNode indirectCall, Node loc,
                                       PhpObject closureObj, PhpClosure c, Object[] userArgs) {
        return switch (c.kind()) {
            case CLOSURE -> invokeTarget(indirectCall, c.callTarget(),
                    buildClosureArgs(c.boundThis(), c.calledClass(), closureObj, userArgs));
            case USER_FUNCTION -> invokeTarget(indirectCall, c.callTarget(), copyArgs(userArgs));
            case METHOD -> invokeTarget(indirectCall, c.callTarget(),
                    buildMethodArgs(c.boundThis(), c.calledClass(), userArgs));
            case MAGIC_CALL -> invokeTarget(indirectCall, c.callTarget(),
                    new Object[]{c.boundThis(), c.calledClass(), c.magicMethodName(), buildMagicArray(userArgs)});
            case BUILTIN -> callBuiltin(ctx, c.builtin(), userArgs, loc);
        };
    }

    public static Object[] buildClosureArgs(Object boundThis, PhpClass calledClass, PhpObject closureObj, Object[] userArgs) {
        Object[] args = new Object[3 + userArgs.length];
        args[0] = boundThis;
        args[1] = calledClass;
        args[2] = closureObj;
        for (int i = 0; i < userArgs.length; i++) {
            args[3 + i] = PhpOps.copyOnAssign(userArgs[i]);
        }
        return args;
    }

    public static Object[] buildMethodArgs(Object boundThis, PhpClass calledClass, Object[] userArgs) {
        Object[] args = new Object[2 + userArgs.length];
        args[0] = boundThis;
        args[1] = calledClass;
        for (int i = 0; i < userArgs.length; i++) {
            args[2 + i] = PhpOps.copyOnAssign(userArgs[i]);
        }
        return args;
    }

    private static Object[] copyArgs(Object[] userArgs) {
        Object[] args = new Object[userArgs.length];
        for (int i = 0; i < userArgs.length; i++) {
            args[i] = PhpOps.copyOnAssign(userArgs[i]);
        }
        return args;
    }

    private static PhpArray buildMagicArray(Object[] userArgs) {
        PhpArray arr = new PhpArray();
        for (Object u : userArgs) {
            arr.append(PhpOps.copyOnAssign(u));
        }
        return arr;
    }

    private static Object invokeTarget(IndirectCallNode indirectCall, RootCallTarget target, Object[] args) {
        return indirectCall != null ? indirectCall.call(target, args) : target.call(args);
    }

    private static Object callBuiltin(PhpContext ctx, Builtins.Def def, Object[] userArgs, Node loc) {
        CallNode.checkArity(def, userArgs.length, loc);
        Object[] args = copyArgs(userArgs);
        try {
            return def.impl().call(ctx, args);
        } catch (PhpError e) {
            throw loc != null ? e.at(loc) : e;
        }
    }
}
