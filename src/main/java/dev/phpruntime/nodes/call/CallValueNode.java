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
    private final int outerThisSlot;
    @Child private DirectCallNode directCall;
    @Child private IndirectCallNode indirectCall;
    @CompilationFinal private volatile StringCache stringCache;

    public CallValueNode(PhpNode targetExpr, PhpNode[] args, int outerThisSlot) {
        this.targetExpr = targetExpr;
        this.args = args;
        this.outerThisSlot = outerThisSlot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object target = targetExpr.execute(frame);
        if (target instanceof PhpObject obj && obj.closure() != null) {
            PhpClosure c = obj.closure();
            return switch (c.kind()) {
                case CLOSURE -> callTargetFast(
                        c.callTarget(), evalClosureArgs(frame, c.boundThis(), c.calledClass(), obj));
                case USER_FUNCTION -> callTargetFast(c.callTarget(), evalPlainArgs(frame));
                case METHOD -> callTargetFast(c.callTarget(), evalMethodArgs(frame, c.boundThis(), c.calledClass()));
                case MAGIC_CALL, BUILTIN -> {
                    ensureIndirectCall();
                    yield PhpCallable.invokeClosure(
                            PhpContext.get(this), indirectCall, this, obj, c, evalPlainArgs(frame));
                }
            };
        }
        if (target instanceof String s) {
            StringCache sc = stringCache;
            if (sc != null && sc.rawName.equals(s)) {
                return callCachedString(frame, sc);
            }
            if (sc == null && s.indexOf("::") < 0) {
                sc = specializeString(s);
                if (sc != null) {
                    return callCachedString(frame, sc);
                }
            }
        }
        return callSlow(frame, target, outerThisSlot >= 0 ? frame.getObject(outerThisSlot) : PhpNull.INSTANCE);
    }

    private Object callCachedString(VirtualFrame frame, StringCache sc) {
        if (sc.builtin != null) {
            Object[] values = evalPlainArgs(frame);
            CallNode.checkArity(sc.builtin, values.length, this);
            return invokeBuiltin(sc.builtin, values);
        }
        PhpFunction fn = PhpContext.get(this).functionById(sc.functionId);
        if (fn == null) {
            throw new PhpError("Value not of type callable", this);
        }
        return callTargetFast(fn.callTarget(), evalPlainArgs(frame));
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
        ensureIndirectCall();
        Object[] userArgs = evalPlainArgs(frame);
        return dispatchSlow(target, callerThis, userArgs);
    }

    @TruffleBoundary
    private Object dispatchSlow(Object target, Object callerThis, Object[] userArgs) {
        PhpContext ctx = PhpContext.get(this);
        PhpCallable cb = PhpCallable.resolve(ctx, target, callerThis, this);
        return cb.call(ctx, indirectCall, this, userArgs);
    }

    @TruffleBoundary
    private Object invokeBuiltin(Builtins.Def def, Object[] values) {
        try {
            return def.impl().call(PhpContext.get(this), values);
        } catch (PhpError e) {
            throw e.at(this);
        }
    }

    @ExplodeLoop
    private Object[] evalClosureArgs(VirtualFrame frame, Object boundThis, PhpClass calledClass, PhpObject closureObj) {
        Object[] values = new Object[3 + args.length];
        values[0] = boundThis;
        values[1] = calledClass;
        values[2] = closureObj;
        for (int i = 0; i < args.length; i++) {
            values[3 + i] = PhpOps.copyOnAssign(args[i].execute(frame));
        }
        return values;
    }

    @ExplodeLoop
    private Object[] evalMethodArgs(VirtualFrame frame, Object boundThis, PhpClass calledClass) {
        Object[] values = new Object[2 + args.length];
        values[0] = boundThis;
        values[1] = calledClass;
        for (int i = 0; i < args.length; i++) {
            values[2 + i] = PhpOps.copyOnAssign(args[i].execute(frame));
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
