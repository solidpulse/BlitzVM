package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.PhpContext;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.call.CallArgHelper;
import dev.phpruntime.nodes.call.CallNode;

import java.util.List;
import java.util.Map;

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
    private final PhpClass traitSelf;
    private final String magicMethodName;
    /** A Java-implemented method's declared parameter names (for named arguments); else empty. */
    private final java.util.List<String> parameterNames;
    /** The linked method when this callable is one, so named binding sees its full metadata. */
    private final PhpClass.MethodEntry methodEntry;

    private PhpCallable(Kind kind, PhpObject closureObj, RootCallTarget callTarget, Builtins.Def builtin,
                        Object boundThis, PhpClass calledClass, PhpClass traitSelf, String magicMethodName) {
        this(kind, closureObj, callTarget, builtin, boundThis, calledClass, traitSelf, magicMethodName,
                java.util.List.of(), null);
    }

    private PhpCallable(Kind kind, PhpObject closureObj, RootCallTarget callTarget, Builtins.Def builtin,
                        Object boundThis, PhpClass calledClass, PhpClass traitSelf, String magicMethodName,
                        java.util.List<String> parameterNames) {
        this(kind, closureObj, callTarget, builtin, boundThis, calledClass, traitSelf, magicMethodName,
                parameterNames, null);
    }

    private PhpCallable(Kind kind, PhpObject closureObj, RootCallTarget callTarget, Builtins.Def builtin,
                        Object boundThis, PhpClass calledClass, PhpClass traitSelf, String magicMethodName,
                        java.util.List<String> parameterNames, PhpClass.MethodEntry methodEntry) {
        this.parameterNames = parameterNames;
        this.methodEntry = methodEntry;
        this.kind = kind;
        this.closureObj = closureObj;
        this.callTarget = callTarget;
        this.builtin = builtin;
        this.boundThis = boundThis;
        this.calledClass = calledClass;
        this.traitSelf = traitSelf;
        this.magicMethodName = magicMethodName;
    }

    /** The using class when this callable is a method declared in a trait, else null. */
    public PhpClass traitSelf() {
        return traitSelf;
    }

    public Kind kind() {
        return kind;
    }

    /** The parameter names a Java-implemented method declares; empty for guest code. */
    public java.util.List<String> parameterNames() {
        return parameterNames;
    }

    /** The linked Java method this callable refers to, or null. */
    public PhpClass.MethodEntry methodEntry() {
        return methodEntry;
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
                PhpCallable cached = obj.cachedCallable();
                if (cached != null) {
                    return cached;
                }
                PhpClosure c = obj.closure();
                PhpCallable resolved = switch (c.kind()) {
                    case CLOSURE -> new PhpCallable(Kind.CLOSURE, obj, c.callTarget(), null, c.boundThis(), c.calledClass(), c.traitSelf(), null);
                    case USER_FUNCTION -> new PhpCallable(Kind.USER_FUNCTION, null, c.callTarget(), null, PhpNull.INSTANCE, null, null, null);
                    case METHOD -> new PhpCallable(Kind.METHOD, null, c.callTarget(), null, c.boundThis(), c.calledClass(), c.traitSelf(), null,
                            c.methodEntry() == null ? java.util.List.of() : c.methodEntry().parameterNames(), c.methodEntry());
                    case MAGIC_CALL -> new PhpCallable(Kind.MAGIC_CALL, null, c.callTarget(), null, c.boundThis(), c.calledClass(), null, c.magicMethodName());
                    case BUILTIN -> new PhpCallable(Kind.BUILTIN, null, null, c.builtin(), PhpNull.INSTANCE, null, null, null);
                };
                obj.cacheCallable(resolved);
                return resolved;
            }
            PhpClass.MethodEntry invoke = obj.phpClass().lookupMethod("__invoke");
            if (invoke != null && invoke.callTarget() != null) {
                return new PhpCallable(Kind.METHOD, null, invoke.callTarget(), null, obj, obj.phpClass(),
                        invoke.fromTrait() ? invoke.declaringClass() : null, null,
                        invoke.parameterNames(), invoke);
            }
            return null;
        }
        if (val instanceof String s) {
            String n = s.startsWith("\\") ? s.substring(1) : s;
            int sep = n.indexOf("::");
            if (sep > 0) {
                return resolveStaticMethod(ctx, n.substring(0, sep), n.substring(sep + 2), callerThis);
            }
            // Function names cannot be redeclared or removed in a request, so a hit stays valid.
            PhpCallable cached = ctx.cachedFunctionCallable(s);
            if (cached != null) {
                return cached;
            }
            PhpFunction fn = ctx.lookupFunction(n);
            if (fn != null) {
                PhpCallable cb = new PhpCallable(Kind.USER_FUNCTION, null, fn.callTarget(), null, PhpNull.INSTANCE, null, null, null);
                ctx.cacheFunctionCallable(s, cb);
                return cb;
            }
            Builtins.Def def = Builtins.lookup(n);
            if (def != null) {
                PhpCallable cb = new PhpCallable(Kind.BUILTIN, null, null, def, PhpNull.INSTANCE, null, null, null);
                ctx.cacheFunctionCallable(s, cb);
                return cb;
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
                PhpClass.MethodEntry entry = ctx.cachedMethod(cls, methName);
                if (entry != null) {
                    if (entry.callTarget() == null) {
                        return null;
                    }
                    Object thisArg = entry.isStatic() ? PhpNull.INSTANCE : obj;
                    return new PhpCallable(Kind.METHOD, null, entry.callTarget(), null, thisArg, cls,
                            entry.fromTrait() ? entry.declaringClass() : null, null, entry.parameterNames(), entry);
                }
                PhpClass.MethodEntry magic = cls.callMethod();
                if (magic != null && magic.callTarget() != null) {
                    return new PhpCallable(Kind.MAGIC_CALL, null, magic.callTarget(), null, obj, cls, null, methName);
                }
                return null;
            }
            if (el0 instanceof String clsName) {
                return resolveStaticMethodCached(ctx, clsName, methName, callerThis);
            }
        }
        return null;
    }

    private static PhpCallable resolveStaticMethod(PhpContext ctx, String clsName, String methName, Object callerThis) {
        PhpClass cls = ctx.lookupOrAutoloadClass(clsName);
        if (cls == null) {
            return null;
        }
        return staticMethodCallable(cls, cls.lookupMethod(methName), methName, callerThis);
    }

    /** The {@code ['Cls', 'm']} path: the class and method lookup are cached per request by String identity. */
    private static PhpCallable resolveStaticMethodCached(PhpContext ctx, String clsName, String methName, Object callerThis) {
        PhpContext.StaticMethodLookup lookup = ctx.lookupStaticMethod(clsName, methName);
        if (lookup == null) {
            return null;
        }
        return staticMethodCallable(lookup.cls(), lookup.entry(), methName, callerThis);
    }

    private static PhpCallable staticMethodCallable(PhpClass cls, PhpClass.MethodEntry entry,
                                                    String methName, Object callerThis) {
        if (entry != null) {
            if (entry.callTarget() == null) {
                return null;
            }
            PhpClass ts = entry.fromTrait() ? entry.declaringClass() : null;
            if (entry.isStatic()) {
                return new PhpCallable(Kind.METHOD, null, entry.callTarget(), null, PhpNull.INSTANCE, cls, ts, null,
                        entry.parameterNames(), entry);
            }
            if (callerThis instanceof PhpObject co && co.phpClass().isInstanceOf(entry.declaringClass())) {
                return new PhpCallable(Kind.METHOD, null, entry.callTarget(), null, co, co.phpClass(), ts, null,
                        entry.parameterNames(), entry);
            }
            return null;
        }
        PhpClass.MethodEntry magicStatic = cls.callStaticMethod();
        if (magicStatic != null && magicStatic.callTarget() != null) {
            return new PhpCallable(Kind.MAGIC_CALL, null, magicStatic.callTarget(), null, PhpNull.INSTANCE, cls, null, methName);
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
            case METHOD -> PhpClosure.method(cb.callTarget, cb.boundThis, cb.calledClass, cb.traitSelf, cb.methodEntry);
            case MAGIC_CALL -> PhpClosure.magicCall(cb.callTarget, cb.boundThis, cb.calledClass, cb.magicMethodName);
            case BUILTIN -> PhpClosure.builtin(cb.builtin);
        };
        return ctx.newClosure(closure);
    }

    /** Invokes this callable via {@code indirectCall} (or uncached {@code RootCallTarget.call} when null). */
    @TruffleBoundary
    public Object call(PhpContext ctx, IndirectCallNode indirectCall, Node loc, Object... userArgs) {
        return CallArgHelper.unwrapReturn(callRaw(ctx, indirectCall, loc, userArgs));
    }

    /**
     * One-argument call from a builtin callback (array_map, array_filter, ...). Avoids the
     * varargs array of {@link #call}: the callee's argument array is built once instead of
     * copying a temporary. Named {@code call1} so a four-argument {@code call} with two nulls
     * cannot bind here by overload resolution.
     */
    @TruffleBoundary
    public Object call1(PhpContext ctx, Node loc, Object arg) {
        if (kind == Kind.CLOSURE) {
            return CallArgHelper.unwrapReturn(invokeTarget(ctx, null, callTarget, closureArgs(ctx, loc, arg)));
        }
        if (kind == Kind.USER_FUNCTION) {
            return CallArgHelper.unwrapReturn(invokeTarget(ctx, null, callTarget, userArgs(ctx, loc, arg)));
        }
        return CallArgHelper.unwrapReturn(callRaw(ctx, null, loc, arg));
    }

    /** Two-argument call, as {@link #call1}. */
    @TruffleBoundary
    public Object call2(PhpContext ctx, Node loc, Object first, Object second) {
        if (kind == Kind.CLOSURE) {
            return CallArgHelper.unwrapReturn(invokeTarget(ctx, null, callTarget, closureArgs(ctx, loc, first, second)));
        }
        if (kind == Kind.USER_FUNCTION) {
            return CallArgHelper.unwrapReturn(invokeTarget(ctx, null, callTarget, userArgs(ctx, loc, first, second)));
        }
        return CallArgHelper.unwrapReturn(callRaw(ctx, null, loc, first, second));
    }

    private Object[] closureArgs(PhpContext ctx, Node loc, Object arg) {
        Object[] args = new Object[4];
        args[0] = boundThis;
        args[1] = calledClass;
        args[2] = closureObj;
        args[3] = adaptUserArg(rootOf(callTarget), 0, arg, ctx, loc);
        return args;
    }

    private Object[] closureArgs(PhpContext ctx, Node loc, Object first, Object second) {
        PhpFunctionRootNode fnRoot = rootOf(callTarget);
        Object[] args = new Object[5];
        args[0] = boundThis;
        args[1] = calledClass;
        args[2] = closureObj;
        args[3] = adaptUserArg(fnRoot, 0, first, ctx, loc);
        args[4] = adaptUserArg(fnRoot, 1, second, ctx, loc);
        return args;
    }

    private Object[] userArgs(PhpContext ctx, Node loc, Object arg) {
        Object[] args = new Object[1];
        args[0] = adaptUserArg(rootOf(callTarget), 0, arg, ctx, loc);
        return args;
    }

    private Object[] userArgs(PhpContext ctx, Node loc, Object first, Object second) {
        PhpFunctionRootNode fnRoot = rootOf(callTarget);
        Object[] args = new Object[2];
        args[0] = adaptUserArg(fnRoot, 0, first, ctx, loc);
        args[1] = adaptUserArg(fnRoot, 1, second, ctx, loc);
        return args;
    }

    /**
     * Invokes this callable with named arguments php forwards through a builtin or a
     * called-through method. A name the callee does not declare follows its own rules:
     * a variadic parameter collects it, otherwise it is "Unknown named parameter".
     */
    @TruffleBoundary
    public Object callNamed(PhpContext ctx, Node loc, List<Object> positional, Map<String, Object> named) {
        if (named.isEmpty()) {
            return call(ctx, null, loc, positional.toArray());
        }
        Object res = switch (kind) {
            case CLOSURE -> invokeTarget(ctx, null, callTarget,
                    CallArgHelper.bindForwardedToFunction(ctx, rootOf(callTarget),
                            new Object[]{boundThis, calledClass, closureObj}, positional, named, loc));
            case USER_FUNCTION -> invokeTarget(ctx, null, callTarget,
                    CallArgHelper.bindForwardedToFunction(ctx, rootOf(callTarget),
                            new Object[0], positional, named, loc));
            case METHOD -> {
                Object[] prefix = traitSelf != null
                        ? new Object[]{boundThis, calledClass, traitSelf}
                        : new Object[]{boundThis, calledClass};
                PhpFunctionRootNode root = rootOf(callTarget);
                if (root == null) {
                    yield invokeTarget(ctx, null, callTarget, CallArgHelper.bindToInternal(prefix,
                            CallArgHelper.newRawCallArgs(positional, named), methodEntry, loc));
                }
                yield invokeTarget(ctx, null, callTarget,
                        CallArgHelper.bindForwardedToFunction(ctx, root, prefix, positional, named, loc));
            }
            case MAGIC_CALL -> invokeTarget(ctx, null, callTarget,
                    new Object[]{boundThis, calledClass, magicMethodName, namedMagicArray(positional, named)});
            case BUILTIN -> CallArgHelper.callForwardedBuiltin(ctx, builtin, positional, named, loc);
        };
        return CallArgHelper.unwrapReturn(res);
    }

    /** The guest function a call target belongs to, or null for a generator entry or wrapper. */
    public static PhpFunctionRootNode rootOf(RootCallTarget target) {
        return target != null && PhpFunctionRootNode.of(target.getRootNode()) instanceof PhpFunctionRootNode r ? r : null;
    }

    private static PhpArray namedMagicArray(List<Object> positional, Map<String, Object> named) {
        PhpArray arr = new PhpArray();
        for (Object p : positional) {
            arr.append(PhpOps.copyOnAssign(p));
        }
        for (Map.Entry<String, Object> e : named.entrySet()) {
            arr.put(e.getKey(), PhpOps.copyOnAssign(e.getValue()));
        }
        return arr;
    }

    @TruffleBoundary
    public Object callRaw(PhpContext ctx, IndirectCallNode indirectCall, Node loc, Object... userArgs) {
        return switch (kind) {
            case CLOSURE -> invokeTarget(ctx, indirectCall, callTarget,
                    buildClosureArgs(callTarget, boundThis, calledClass, closureObj, userArgs, ctx, loc));
            case USER_FUNCTION -> invokeTarget(ctx, indirectCall, callTarget,
                    prepareUserArgs(callTarget, userArgs, ctx, loc));
            case METHOD -> invokeTarget(ctx, indirectCall, callTarget,
                    withTraitSelf(buildMethodArgs(callTarget, boundThis, calledClass, userArgs, ctx, loc), traitSelf));
            case MAGIC_CALL -> invokeTarget(ctx, indirectCall, callTarget,
                    new Object[]{boundThis, calledClass, magicMethodName, buildMagicArray(userArgs)});
            case BUILTIN -> callBuiltin(ctx, builtin, userArgs, loc);
        };
    }

    /** Invokes a closure with named arguments forwarded by Closure::call. */
    @TruffleBoundary
    public static Object invokeClosureNamed(PhpContext ctx, Node loc, PhpObject closureObj, PhpClosure c,
                                            Object[] userArgs, Map<String, Object> named) {
        PhpCallable callable = switch (c.kind()) {
            case CLOSURE -> new PhpCallable(Kind.CLOSURE, closureObj, c.callTarget(), null, c.boundThis(),
                    c.calledClass(), c.traitSelf(), null);
            case USER_FUNCTION -> new PhpCallable(Kind.USER_FUNCTION, null, c.callTarget(), null,
                    PhpNull.INSTANCE, null, null, null);
            case METHOD -> new PhpCallable(Kind.METHOD, null, c.callTarget(), null, c.boundThis(),
                    c.calledClass(), c.traitSelf(), null,
                    c.methodEntry() == null ? java.util.List.of() : c.methodEntry().parameterNames(),
                    c.methodEntry());
            case MAGIC_CALL -> new PhpCallable(Kind.MAGIC_CALL, null, c.callTarget(), null, c.boundThis(),
                    c.calledClass(), null, c.magicMethodName());
            case BUILTIN -> new PhpCallable(Kind.BUILTIN, null, null, c.builtin(), PhpNull.INSTANCE,
                    null, null, null);
        };
        return callable.callNamed(ctx, loc, java.util.Arrays.asList(userArgs), named);
    }

    /** Invokes a {@link PhpClosure} directly without allocating a temporary {@link PhpCallable}. */
    @TruffleBoundary
    public static Object invokeClosure(PhpContext ctx, IndirectCallNode indirectCall, Node loc,
                                       PhpObject closureObj, PhpClosure c, Object[] userArgs) {
        Object res = switch (c.kind()) {
            case CLOSURE -> invokeTarget(ctx, indirectCall, c.callTarget(),
                    buildClosureArgs(c.callTarget(), c.boundThis(), c.calledClass(), closureObj, userArgs, ctx, loc));
            case USER_FUNCTION -> invokeTarget(ctx, indirectCall, c.callTarget(),
                    prepareUserArgs(c.callTarget(), userArgs, ctx, loc));
            case METHOD -> invokeTarget(ctx, indirectCall, c.callTarget(),
                    withTraitSelf(buildMethodArgs(c.callTarget(), c.boundThis(), c.calledClass(), userArgs, ctx, loc),
                            c.traitSelf()));
            case MAGIC_CALL -> invokeTarget(ctx, indirectCall, c.callTarget(),
                    new Object[]{c.boundThis(), c.calledClass(), c.magicMethodName(), buildMagicArray(userArgs)});
            case BUILTIN -> callBuiltin(ctx, c.builtin(), userArgs, loc);
        };
        return CallArgHelper.unwrapReturn(res);
    }

    /**
     * One-argument call of a closure object that skips the {@link PhpCallable} wrapper. Builtin
     * callbacks (array_map and friends) hold the closure object anyway; resolving it into a
     * callable allocated a wrapper per call that only forwarded here.
     */
    @TruffleBoundary
    public static Object invokeClosure1(PhpContext ctx, Node loc, PhpObject closureObj, PhpClosure c, Object arg) {
        if (c.kind() != dev.phpruntime.runtime.PhpClosure.Kind.CLOSURE) {
            return invokeClosure(ctx, null, loc, closureObj, c, new Object[]{arg});
        }
        Object[] args = new Object[4];
        args[0] = c.boundThis();
        args[1] = c.calledClass();
        args[2] = closureObj;
        args[3] = adaptUserArg(rootOf(c.callTarget()), 0, arg, ctx, loc);
        return CallArgHelper.unwrapReturn(invokeTarget(ctx, null, c.callTarget(), args));
    }

    /** Two-argument variant of {@link #invokeClosure1}. */
    @TruffleBoundary
    public static Object invokeClosure2(PhpContext ctx, Node loc, PhpObject closureObj, PhpClosure c,
                                        Object first, Object second) {
        if (c.kind() != dev.phpruntime.runtime.PhpClosure.Kind.CLOSURE) {
            return invokeClosure(ctx, null, loc, closureObj, c, new Object[]{first, second});
        }
        PhpFunctionRootNode fnRoot = rootOf(c.callTarget());
        Object[] args = new Object[5];
        args[0] = c.boundThis();
        args[1] = c.calledClass();
        args[2] = closureObj;
        args[3] = adaptUserArg(fnRoot, 0, first, ctx, loc);
        args[4] = adaptUserArg(fnRoot, 1, second, ctx, loc);
        return CallArgHelper.unwrapReturn(invokeTarget(ctx, null, c.callTarget(), args));
    }

    public static Object[] buildClosureArgs(Object boundThis, PhpClass calledClass, PhpObject closureObj, Object[] userArgs) {
        return buildClosureArgs(null, boundThis, calledClass, closureObj, userArgs, null, null);
    }

    public static Object[] buildClosureArgs(RootCallTarget target, Object boundThis, PhpClass calledClass,
                                            PhpObject closureObj, Object[] userArgs, PhpContext ctx, Node loc) {
        PhpFunctionRootNode fnRoot = target != null && dev.phpruntime.nodes.PhpFunctionRootNode.of(target.getRootNode()) instanceof dev.phpruntime.nodes.PhpFunctionRootNode r ? r : null;
        Object[] args = new Object[3 + userArgs.length];
        args[0] = boundThis;
        args[1] = calledClass;
        args[2] = closureObj;
        for (int i = 0; i < userArgs.length; i++) {
            args[3 + i] = adaptUserArg(fnRoot, i, userArgs[i], ctx, loc);
        }
        return args;
    }

    public static Object[] buildMethodArgs(Object boundThis, PhpClass calledClass, Object[] userArgs) {
        return buildMethodArgs(null, boundThis, calledClass, userArgs, null, null);
    }

    public static Object[] buildMethodArgs(RootCallTarget target, Object boundThis, PhpClass calledClass,
                                           Object[] userArgs, PhpContext ctx, Node loc) {
        PhpFunctionRootNode fnRoot = target != null && dev.phpruntime.nodes.PhpFunctionRootNode.of(target.getRootNode()) instanceof dev.phpruntime.nodes.PhpFunctionRootNode r ? r : null;
        Object[] args = new Object[2 + userArgs.length];
        args[0] = boundThis;
        args[1] = calledClass;
        for (int i = 0; i < userArgs.length; i++) {
            args[2 + i] = adaptUserArg(fnRoot, i, userArgs[i], ctx, loc);
        }
        return args;
    }

    /** Inserts the using class at index 2 for methods declared in a trait ({@code traitSelf} non-null). */
    private static Object[] withTraitSelf(Object[] args, PhpClass traitSelf) {
        if (traitSelf == null) {
            return args;
        }
        Object[] out = new Object[args.length + 1];
        out[0] = args[0];
        out[1] = args[1];
        out[2] = traitSelf;
        System.arraycopy(args, 2, out, 3, args.length - 2);
        return out;
    }

    private static Object[] prepareUserArgs(RootCallTarget target, Object[] userArgs, PhpContext ctx, Node loc) {
        PhpFunctionRootNode fnRoot = target != null && dev.phpruntime.nodes.PhpFunctionRootNode.of(target.getRootNode()) instanceof dev.phpruntime.nodes.PhpFunctionRootNode r ? r : null;
        Object[] args = new Object[userArgs.length];
        for (int i = 0; i < userArgs.length; i++) {
            args[i] = adaptUserArg(fnRoot, i, userArgs[i], ctx, loc);
        }
        return args;
    }

    /** Parameter coercion and by-value copy for an argument a builtin passes to a callback. */
    public static Object adaptUserArg(PhpFunctionRootNode fnRoot, int paramIdx, Object arg, PhpContext ctx, Node loc) {
        boolean expectsRef = fnRoot != null && fnRoot.isParamByRef(paramIdx);
        if (expectsRef) {
            if (arg instanceof PhpReference ref) {
                ref.ensureDefined();
                return ref;
            }
            if (ctx != null) {
                warnValueForRef(fnRoot, paramIdx, ctx, loc);
            }
            return new PhpReference(PhpOps.copyOnAssign(arg));
        }
        if (arg instanceof PhpReference ref) {
            return ref.getValue() == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(ref.getValue());
        }
        return PhpOps.copyOnAssign(arg);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static void warnValueForRef(PhpFunctionRootNode fnRoot, int paramIdx, PhpContext ctx, Node loc) {
        String fnDesc = fnRoot.qualifiedCallableName();
        String pName = fnRoot.paramName(paramIdx);
        String desc = pName != null ? fnDesc + "(): Argument #" + (paramIdx + 1) + " ($" + pName + ")" : fnDesc + "(): Argument #" + (paramIdx + 1);
        ctx.warning(desc + " must be passed by reference, value given", loc instanceof dev.phpruntime.nodes.PhpNode pn ? pn.line() : 0);
    }

    private static PhpArray buildMagicArray(Object[] userArgs) {
        PhpArray arr = new PhpArray();
        for (Object u : userArgs) {
            Object val = u instanceof PhpReference ref ? ref.getValue() : u;
            arr.append(PhpOps.copyOnAssign(val == null ? PhpNull.INSTANCE : val));
        }
        return arr;
    }

    private static Object invokeTarget(PhpContext ctx, IndirectCallNode indirectCall, RootCallTarget target, Object[] args) {
        CallMode previous = ctx == null ? null : ctx.pendingCallMode();
        if (ctx != null && previous == null) {
            ctx.setPendingCallMode(CallMode.internal(dev.phpruntime.nodes.PhpFunctionRootNode.of(target.getRootNode())));
        }
        int depth = ctx == null ? 0 : ctx.pushTraceFrame(traceSiteOf(ctx, previous));
        try {
            return indirectCall != null ? indirectCall.call(target, args) : target.call(args);
        } finally {
            if (ctx != null) {
                ctx.popTraceFrame(depth);
                ctx.setPendingCallMode(previous);
            }
        }
    }

    /**
     * The call site of a callback a builtin invokes: the builtin's own call site when it calls
     * the callback in its caller's frame ({@code call_user_func}), else no guest location
     * ({@code array_map} runs the callback from an internal frame).
     */
    private static Object traceSiteOf(PhpContext ctx, CallMode pending) {
        if (pending == null) {
            Node builtinSite = ctx.currentBuiltinCallSite();
            String builtin = ctx.currentBuiltinName();
            return builtinSite != null && builtin != null
                    ? new dev.phpruntime.runtime.PhpBacktrace.InternalSite(builtinSite, builtin)
                    : dev.phpruntime.runtime.PhpBacktrace.INTERNAL_SITE;
        }
        Node site = ctx.currentBuiltinCallSite();
        return site != null ? site : dev.phpruntime.runtime.PhpBacktrace.INTERNAL_SITE;
    }

    private static Object callBuiltin(PhpContext ctx, Builtins.Def def, Object[] userArgs, Node loc) {
        CallNode.checkArity(def, userArgs.length, loc);
        Object[] args = new Object[userArgs.length];
        for (int i = 0; i < userArgs.length; i++) {
            if (userArgs[i] instanceof PhpReference ref) {
                args[i] = ref.getValue() == null ? PhpNull.INSTANCE : (def.isRef(i) ? ref.getValue() : PhpOps.copyOnAssign(ref.getValue()));
            } else {
                args[i] = PhpOps.copyOnAssign(userArgs[i]);
            }
        }
        Object result;
        Node previousSite = ctx.currentBuiltinCallSite();
        String previousName = ctx.currentBuiltinName();
        if (loc != null) {
            ctx.enterBuiltin(loc, def.name());
        }
        try {
            result = def.call(ctx, args);
        } catch (PhpError e) {
            throw loc != null ? e.at(loc) : e;
        } finally {
            if (loc != null) {
                ctx.exitBuiltin(previousSite, previousName);
            }
        }
        for (int i = 0; i < userArgs.length; i++) {
            if (def.isRef(i) && userArgs[i] instanceof PhpReference ref) {
                ref.setValue(args[i]);
            }
        }
        return result;
    }
}
