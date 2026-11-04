package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
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
    @Children private final PhpNode[] args;
    /** For each argument, the lvalue it came from (for by-reference builtins), else null. */
    @Children private final LValueNode[] refTargets;

    @CompilationFinal private PhpFunction function;
    @Child private DirectCallNode callNode;
    @CompilationFinal private Builtins.Def builtin;

    public CallNode(String name, PhpNode[] args, LValueNode[] refTargets) {
        this.name = name;
        this.args = args;
        this.refTargets = refTargets;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (callNode == null && builtin == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            resolve();
        }
        if (callNode != null) {
            return callNode.call(evalArgs(frame));
        }
        return callBuiltin(frame);
    }

    private void resolve() {
        PhpFunction fn = PhpContext.get(this).lookupFunction(name);
        if (fn != null) {
            function = fn;
            callNode = insert(DirectCallNode.create(fn.callTarget()));
            return;
        }
        Builtins.Def def = Builtins.lookup(name);
        if (def == null) {
            throw new PhpError("Call to undefined function " + name + "()", this);
        }
        builtin = def;
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

    @TruffleBoundary
    private Object invoke(Builtins.Def def, Object[] values) {
        try {
            return def.impl().call(PhpContext.get(this), values);
        } catch (PhpError e) {
            throw e.at(this);
        }
    }

    private void checkArity(Builtins.Def def, int n) {
        if (n < def.minArgs() || (def.maxArgs() >= 0 && n > def.maxArgs())) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.ARGUMENT_COUNT, def.name() + "() expects " + arity(def, n) + ", " + n + " given", this);
        }
    }

    private static String arity(Builtins.Def def, int n) {
        String quantifier = def.minArgs() == def.maxArgs() ? "exactly" : (n < def.minArgs() ? "at least" : "at most");
        int count = n < def.minArgs() ? def.minArgs() : def.maxArgs();
        return quantifier + " " + count + " argument" + (count == 1 ? "" : "s");
    }
}
