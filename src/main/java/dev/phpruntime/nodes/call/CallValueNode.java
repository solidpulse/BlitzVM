package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClosure;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpFunction;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;

/**
 * {@code $expr(args...)}: calls any callable expression (Closure, function name string,
 * {@code "Class::method"}, {@code [$obj, 'm']} / {@code ['Class', 'm']}, or invokable object).
 * Caches monomorphic Closure/function targets directly via {@link DirectCallNode} and
 * single-string function names via a single immutable {@link StringCache} record.
 */
public final class CallValueNode extends PhpNode {

    private record StringCache(String rawName, int functionId, Builtins.Def builtin) {
    }

    @Child private PhpNode targetExpr;
    @Children private final PhpNode[] args;
    @Children private final dev.phpruntime.nodes.var.LValueNode[] refTargets;
    @CompilationFinal(dimensions = 1) private final String[] argNames;
    @CompilationFinal(dimensions = 1) private final boolean[] spreads;
    private final boolean hasSpreadOrNamed;
    private final int outerThisSlot;
    @Child private DirectCallNode directCall;
    @Child private IndirectCallNode indirectCall;
    @CompilationFinal private volatile StringCache stringCache;
    /*
     * Rare paths, kept out of compiled code until a site uses them. They pass the frame to
     * helpers that partial evaluation cannot fully inline, which would make the frame escape and
     * slow down the whole calling function. Each flag only goes false -> true (safe to race).
     */
    @CompilationFinal private boolean seenComplexBuiltin;
    @CompilationFinal private boolean seenMagic;
    @CompilationFinal private boolean seenSlow;

    public CallValueNode(PhpNode targetExpr, PhpNode[] args, int outerThisSlot) {
        this(targetExpr, args, new dev.phpruntime.nodes.var.LValueNode[args.length], null, null, outerThisSlot);
    }

    public CallValueNode(PhpNode targetExpr, PhpNode[] args, dev.phpruntime.nodes.var.LValueNode[] refTargets,
                         String[] argNames, boolean[] spreads, int outerThisSlot) {
        this.targetExpr = targetExpr;
        this.args = args;
        this.refTargets = refTargets;
        this.argNames = argNames;
        this.spreads = spreads;
        boolean complex = false;
        if (argNames != null) {
            for (String s : argNames) {
                if (s != null) {
                    complex = true;
                    break;
                }
            }
        }
        if (!complex && spreads != null) {
            for (boolean b : spreads) {
                if (b) {
                    complex = true;
                    break;
                }
            }
        }
        this.hasSpreadOrNamed = complex;
        this.outerThisSlot = outerThisSlot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return CallArgHelper.unwrapReturn(executeRaw(frame));
    }

    @Override
    public Object executeForRef(VirtualFrame frame) {
        return executeRaw(frame);
    }

    private Object executeRaw(VirtualFrame frame) {
        Object target = targetExpr.execute(frame);
        if (target instanceof PhpObject obj && obj.closure() != null) {
            PhpClosure c = obj.closure();
            RootCallTarget ct = c.callTarget();
            DirectCallNode direct = directCall;
            if (direct != null && direct.getCallTarget() == ct) {
                // The cached target is a PE constant, so argument evaluation folds its by-ref checks.
                return callClosure(frame, obj, c, (RootCallTarget) direct.getCallTarget());
            }
            if (direct != null && indirectCall == null) {
                // Monomorphic sites compile without the generic path; a new target re-specializes.
                CompilerDirectives.transferToInterpreterAndInvalidate();
            }
            return callClosure(frame, obj, c, ct);
        }
        if (target instanceof String s) {
            StringCache sc = stringCache;
            if (sc != null && sc.rawName.equals(s)) {
                return callCachedString(frame, sc);
            }
            if (sc == null && !isStaticCallable(s)) {
                sc = specializeString(s);
                if (sc != null) {
                    return callCachedString(frame, sc);
                }
            }
        }
        return callSlow(frame, target, outerThisSlot >= 0 ? frame.getObject(outerThisSlot) : PhpNull.INSTANCE);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static boolean isStaticCallable(String name) {
        return name.indexOf("::") >= 0;
    }

    private Object callCachedString(VirtualFrame frame, StringCache sc) {
        if (sc.builtin != null) {
            return callBuiltinFast(frame, sc.builtin);
        }
        PhpFunction fn = PhpContext.get(this).functionById(sc.functionId);
        if (fn == null) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Value not of type callable", this);
        }
        RootCallTarget target = fn.callTarget();
        DirectCallNode direct = directCall;
        if (direct != null && direct.getCallTarget() == target) {
            // The cached target is a PE constant, so argument evaluation folds its by-ref checks.
            return direct.call(evalUserPlainArgs(frame, (RootCallTarget) direct.getCallTarget()));
        }
        if (direct != null && indirectCall == null) {
            // Monomorphic sites compile without the generic path; a new target re-specializes.
            CompilerDirectives.transferToInterpreterAndInvalidate();
        }
        return callTargetFast(target, evalUserPlainArgs(frame, target));
    }

    private Object callBuiltinFast(VirtualFrame frame, Builtins.Def def) {
        if (hasSpreadOrNamed || def.refMask() != 0) {
            if (!seenComplexBuiltin) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seenComplexBuiltin = true;
            }
            return CallArgHelper.callComplexBuiltin(frame, def, args, refTargets, argNames, spreads, this);
        }
        Object[] values = evalPlainArgs(frame);
        CallNode.checkArity(def, values.length, this);
        return invokeBuiltin(def, values);
    }

    private Object callClosure(VirtualFrame frame, PhpObject obj, PhpClosure c, RootCallTarget target) {
        return switch (c.kind()) {
            case CLOSURE -> callTargetFast(target, evalClosureArgs(frame, target, c.boundThis(), c.calledClass(), obj));
            case USER_FUNCTION -> callTargetFast(target, evalUserPlainArgs(frame, target));
            case METHOD -> {
                Object[] margs = evalMethodArgs(frame, target, c.boundThis(), c.calledClass());
                yield callTargetFast(target, c.traitSelf() != null ? DispatchCallNode.withTraitSelf(margs, c.traitSelf()) : margs);
            }
            case MAGIC_CALL -> {
                if (!seenMagic) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    seenMagic = true;
                }
                ensureIndirectCall();
                yield indirectCall.call(target, new Object[]{
                        c.boundThis(), c.calledClass(), c.magicMethodName(),
                        DispatchCallNode.evalMagicArgs(frame, args, argNames, spreads, this)});
            }
            case BUILTIN -> callBuiltinFast(frame, c.builtin());
        };
    }

    private Object callTargetFast(RootCallTarget target, Object[] frameArgs) {
        DirectCallNode direct = directCall;
        if (direct != null && direct.getCallTarget() == target) {
            return direct.call(frameArgs);
        }
        IndirectCallNode indirect = indirectCall;
        if (indirect != null) {
            return indirect.call(target, frameArgs);
        }
        return callDynamic(target, frameArgs);
    }

    private Object callDynamic(RootCallTarget target, Object[] frameArgs) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        if (prepareCall(target)) {
            return directCall.call(frameArgs);
        }
        return indirectCall.call(target, frameArgs);
    }

    @TruffleBoundary
    private boolean prepareCall(RootCallTarget target) {
        java.util.concurrent.locks.Lock lock = getLock();
        lock.lock();
        try {
            if (directCall == null) {
                directCall = insert(DirectCallNode.create(target));
                return true;
            }
            if (directCall.getCallTarget() == target) {
                return true;
            }
            if (indirectCall == null) {
                indirectCall = insert(Truffle.getRuntime().createIndirectCallNode());
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    private void ensureIndirectCall() {
        if (indirectCall != null) {
            return;
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        java.util.concurrent.locks.Lock lock = getLock();
        lock.lock();
        try {
            if (indirectCall == null) {
                indirectCall = insert(Truffle.getRuntime().createIndirectCallNode());
            }
        } finally {
            lock.unlock();
        }
    }

    @TruffleBoundary
    private StringCache specializeString(String s) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        java.util.concurrent.locks.Lock lock = getLock();
        lock.lock();
        try {
            if (stringCache != null) {
                return stringCache.rawName.equals(s) ? stringCache : null;
            }
            Builtins.Def def = Builtins.lookup(s);
            if (def != null) {
                stringCache = new StringCache(s, -1, def);
                return stringCache;
            }
            PhpContext ctx = PhpContext.get(this);
            if (ctx.lookupFunction(s) != null) {
                stringCache = new StringCache(s, PhpLanguage.get(this).functionId(s), null);
                return stringCache;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    private Object callSlow(VirtualFrame frame, Object target, Object callerThis) {
        if (!seenSlow) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            seenSlow = true;
        }
        ensureIndirectCall();
        PhpCallable cb = resolveCallableSlow(target, callerThis);
        if (cb.kind() == PhpCallable.Kind.BUILTIN) {
            return callBuiltinFast(frame, cb.builtin());
        }
        if (cb.kind() == PhpCallable.Kind.MAGIC_CALL) {
            return indirectCall.call(cb.callTarget(), new Object[]{
                    cb.boundThis(), cb.calledClass(), cb.magicMethodName(),
                    DispatchCallNode.evalMagicArgs(frame, args, argNames, spreads, this)});
        }
        Object[] prefix = switch (cb.kind()) {
            case CLOSURE -> new Object[]{cb.boundThis(), cb.calledClass(), cb.closureObj()};
            case METHOD -> cb.traitSelf() != null
                    ? new Object[]{cb.boundThis(), cb.calledClass(), cb.traitSelf()}
                    : new Object[]{cb.boundThis(), cb.calledClass()};
            default -> new Object[0];
        };
        if (!hasSpreadOrNamed) {
            // Plain positional arguments: keep the general named/spread binder out of compiled code.
            return indirectCall.call(cb.callTarget(), evalArgsWithPrefix(frame, cb.callTarget(), prefix));
        }
        Object[] frameArgs = CallArgHelper.evalComplexUserArgs(
                frame, cb.callTarget(), prefix, args, refTargets, argNames, spreads, this);
        return indirectCall.call(cb.callTarget(), frameArgs);
    }

    @ExplodeLoop
    private Object[] evalArgsWithPrefix(VirtualFrame frame, RootCallTarget target, Object[] prefix) {
        dev.phpruntime.nodes.PhpFunctionRootNode fnRoot =
                dev.phpruntime.nodes.PhpFunctionRootNode.of(target.getRootNode()) instanceof dev.phpruntime.nodes.PhpFunctionRootNode r ? r : null;
        Object[] values = new Object[prefix.length + args.length];
        System.arraycopy(prefix, 0, values, 0, prefix.length);
        for (int i = 0; i < args.length; i++) {
            values[prefix.length + i] = CallArgHelper.evalArgForParam(frame, args[i], refTargets[i], fnRoot, i, this);
        }
        return values;
    }

    @TruffleBoundary
    private PhpCallable resolveCallableSlow(Object target, Object callerThis) {
        return PhpCallable.resolve(PhpContext.get(this), target, callerThis, this);
    }

    @TruffleBoundary
    private Object invokeBuiltin(Builtins.Def def, Object[] values) {
        try {
            return def.call(PhpContext.get(this), values);
        } catch (PhpError e) {
            throw e.at(this);
        }
    }

    private Object[] evalClosureArgs(VirtualFrame frame, RootCallTarget target, Object boundThis, PhpClass calledClass, PhpObject closureObj) {
        if (hasSpreadOrNamed) {
            return CallArgHelper.evalComplexUserArgs(frame, target, new Object[]{boundThis, calledClass, closureObj},
                    args, refTargets, argNames, spreads, this);
        }
        dev.phpruntime.nodes.PhpFunctionRootNode fnRoot = dev.phpruntime.nodes.PhpFunctionRootNode.of(target.getRootNode()) instanceof dev.phpruntime.nodes.PhpFunctionRootNode r ? r : null;
        if (fnRoot != null && fnRoot.hasAnyRefParam()) {
            return evalClosureArgsWithRefs(frame, fnRoot, boundThis, calledClass, closureObj);
        }
        return evalClosureArgsFast(frame, boundThis, calledClass, closureObj);
    }

    @ExplodeLoop
    private Object[] evalClosureArgsWithRefs(VirtualFrame frame, dev.phpruntime.nodes.PhpFunctionRootNode fnRoot,
                                             Object boundThis, PhpClass calledClass, PhpObject closureObj) {
        Object[] values = new Object[3 + args.length];
        values[0] = boundThis;
        values[1] = calledClass;
        values[2] = closureObj;
        for (int i = 0; i < args.length; i++) {
            values[3 + i] = CallArgHelper.evalArgForParam(frame, args[i], refTargets[i], fnRoot, i, this);
        }
        return values;
    }

    @ExplodeLoop
    private Object[] evalClosureArgsFast(VirtualFrame frame, Object boundThis, PhpClass calledClass, PhpObject closureObj) {
        Object[] values = new Object[3 + args.length];
        values[0] = boundThis;
        values[1] = calledClass;
        values[2] = closureObj;
        for (int i = 0; i < args.length; i++) {
            values[3 + i] = PhpOps.copyOnAssign(args[i].execute(frame));
        }
        return values;
    }

    private Object[] evalMethodArgs(VirtualFrame frame, RootCallTarget target, Object boundThis, PhpClass calledClass) {
        if (hasSpreadOrNamed) {
            return CallArgHelper.evalComplexUserArgs(frame, target, new Object[]{boundThis, calledClass},
                    args, refTargets, argNames, spreads, this);
        }
        dev.phpruntime.nodes.PhpFunctionRootNode fnRoot = dev.phpruntime.nodes.PhpFunctionRootNode.of(target.getRootNode()) instanceof dev.phpruntime.nodes.PhpFunctionRootNode r ? r : null;
        if (fnRoot != null && fnRoot.hasAnyRefParam()) {
            return evalMethodArgsWithRefs(frame, fnRoot, boundThis, calledClass);
        }
        return evalMethodArgsFast(frame, boundThis, calledClass);
    }

    @ExplodeLoop
    private Object[] evalMethodArgsWithRefs(VirtualFrame frame, dev.phpruntime.nodes.PhpFunctionRootNode fnRoot,
                                            Object boundThis, PhpClass calledClass) {
        Object[] values = new Object[2 + args.length];
        values[0] = boundThis;
        values[1] = calledClass;
        for (int i = 0; i < args.length; i++) {
            values[2 + i] = CallArgHelper.evalArgForParam(frame, args[i], refTargets[i], fnRoot, i, this);
        }
        return values;
    }

    @ExplodeLoop
    private Object[] evalMethodArgsFast(VirtualFrame frame, Object boundThis, PhpClass calledClass) {
        Object[] values = new Object[2 + args.length];
        values[0] = boundThis;
        values[1] = calledClass;
        for (int i = 0; i < args.length; i++) {
            values[2 + i] = PhpOps.copyOnAssign(args[i].execute(frame));
        }
        return values;
    }

    private Object[] evalUserPlainArgs(VirtualFrame frame, RootCallTarget target) {
        if (hasSpreadOrNamed) {
            return CallArgHelper.evalComplexUserArgs(frame, target, new Object[0],
                    args, refTargets, argNames, spreads, this);
        }
        dev.phpruntime.nodes.PhpFunctionRootNode fnRoot = dev.phpruntime.nodes.PhpFunctionRootNode.of(target.getRootNode()) instanceof dev.phpruntime.nodes.PhpFunctionRootNode r ? r : null;
        if (fnRoot != null && fnRoot.hasAnyRefParam()) {
            return evalPlainArgsWithRefs(frame, fnRoot);
        }
        return evalPlainArgs(frame);
    }

    @ExplodeLoop
    private Object[] evalPlainArgsWithRefs(VirtualFrame frame, dev.phpruntime.nodes.PhpFunctionRootNode fnRoot) {
        Object[] values = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            values[i] = CallArgHelper.evalArgForParam(frame, args[i], refTargets[i], fnRoot, i, this);
        }
        return values;
    }


    @ExplodeLoop
    private Object[] evalPlainArgs(VirtualFrame frame) {
        Object[] values = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            values[i] = PhpOps.copyOnAssign(args[i].execute(frame));
        }
        return values;
    }
}
