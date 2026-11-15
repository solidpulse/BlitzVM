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
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpFunction;
import dev.phpruntime.runtime.PhpOps;

/**
 * {@code name(args...)}. Resolved on first execution: a user function becomes a
 * DirectCallNode (which Graal can inline), a builtin is called directly. The resolved
 * target is cached in the node, like a monomorphic inline cache.
 */
public final class CallNode extends PhpNode {

    private final String name;
    /** {@link dev.phpruntime.PhpLanguage#functionId} of {@link #name}. */
    private final int functionId;
    @Children private final PhpNode[] args;
    /** For each argument, the lvalue it came from (for by-reference builtins), else null. */
    @Children private final LValueNode[] refTargets;

    @Child private DirectCallNode callNode;
    @Child private IndirectCallNode indirectCall;
    @CompilationFinal private Builtins.Def builtin;

    public CallNode(String name, int functionId, PhpNode[] args, LValueNode[] refTargets) {
        this.name = name;
        this.functionId = functionId;
        this.args = args;
        this.refTargets = refTargets;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (builtin != null) {
            return callBuiltin(frame);
        }
        // The name can mean a different function in another request (conditional
        // declarations, includes), so check which one this context has on every call.
        PhpFunction fn = PhpContext.get(this).functionById(functionId);
        if (fn == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            Builtins.Def def = resolveBuiltin();
            if (def != null) {
                return callBuiltin(frame);
            }
            throw new PhpError("Call to undefined function " + name + "()", this);
        }
        RootCallTarget target = fn.callTarget();
        // Read each child once: another request may be installing it concurrently.
        DirectCallNode direct = callNode;
        if (direct != null && direct.getCallTarget() == target) {
            return direct.call(evalArgs(frame));
        }
        IndirectCallNode indirect = indirectCall;
        if (indirect != null) {
            return indirect.call(target, evalArgs(frame));
        }
        return callDynamic(frame, fn);
    }

    /** The AST is shared by concurrent requests, so node specialization takes the AST lock. */
    @TruffleBoundary
    private Builtins.Def resolveBuiltin() {
        java.util.concurrent.locks.Lock lock = getLock();
        lock.lock();
        try {
            if (builtin == null) {
                builtin = Builtins.lookup(name);
            }
            return builtin;
        } finally {
            lock.unlock();
        }
    }

    private Object callDynamic(VirtualFrame frame, PhpFunction fn) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        if (prepareCall(fn.callTarget())) {
            return callNode.call(evalArgs(frame));
        }
        return indirectCall.call(fn.callTarget(), evalArgs(frame));
    }

    @TruffleBoundary
    private boolean prepareCall(RootCallTarget target) {
        java.util.concurrent.locks.Lock lock = getLock();
        lock.lock();
        try {
            if (callNode == null) {
                callNode = insert(DirectCallNode.create(target));
                return true;
            }
            if (callNode.getCallTarget() == target) {
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

    @ExplodeLoop
    private Object[] evalArgs(VirtualFrame frame) {
        Object[] values = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            values[i] = PhpOps.copyOnAssign(args[i].execute(frame));
        }
        return values;
    }

    private Object callBuiltin(VirtualFrame frame) {
        Builtins.Def def = builtin;
        Object[] values = new Object[args.length];
        Object[][] refKeys = new Object[args.length][];
        for (int i = 0; i < args.length; i++) {
            if (def.isRef(i) && refTargets[i] != null) {
                refKeys[i] = refTargets[i].evalKeys(frame);
                Object v = refTargets[i].read(frame, refKeys[i]);
                values[i] = v == null ? dev.phpruntime.runtime.PhpNull.INSTANCE : v;
            } else {
                values[i] = args[i].execute(frame);
            }
        }
        checkArity(def, values.length);
        Object result = invoke(def, values);
        for (int i = 0; i < args.length; i++) {
            if (refKeys[i] != null) {
                refTargets[i].write(frame, refKeys[i], values[i]);
            }
        }
        return result;
    }

    public String functionName() {
        return name;
    }

    @TruffleBoundary
    private Object invoke(Builtins.Def def, Object[] values) {
        PhpContext ctx = PhpContext.get(this);
        com.oracle.truffle.api.nodes.Node prev = ctx.currentBuiltinCallSite();
        ctx.setCurrentBuiltinCallSite(this);
        try {
            return def.impl().call(ctx, values);
        } catch (PhpError e) {
            throw e.withTrace(dev.phpruntime.runtime.PhpBacktrace.captureFromBuiltin(this, def.name())).at(this);
        } finally {
            ctx.setCurrentBuiltinCallSite(prev);
        }
    }

    private void checkArity(Builtins.Def def, int n) {
        checkArity(def, n, this);
    }

    public static void checkArity(Builtins.Def def, int n, com.oracle.truffle.api.nodes.Node location) {
        if (n < def.minArgs() || (def.maxArgs() >= 0 && n > def.maxArgs())) {
            CompilerDirectives.transferToInterpreter();
            PhpError err = new PhpError(PhpError.ARGUMENT_COUNT, def.name() + "() expects " + arity(def, n) + ", " + n + " given", location);
            if (location != null) {
                err.withTrace(dev.phpruntime.runtime.PhpBacktrace.captureFromBuiltin(location, def.name()));
            }
            throw err;
        }
    }

    private static String arity(Builtins.Def def, int n) {
        String quantifier = def.minArgs() == def.maxArgs() ? "exactly" : (n < def.minArgs() ? "at least" : "at most");
        int count = n < def.minArgs() ? def.minArgs() : def.maxArgs();
        return quantifier + " " + count + " argument" + (count == 1 ? "" : "s");
    }
}
