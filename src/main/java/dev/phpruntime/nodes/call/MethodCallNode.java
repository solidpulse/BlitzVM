package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;

/** {@code $obj->method(args...)} or {@code $obj->$name(args...)}. */
public final class MethodCallNode extends PhpNode {

    @Child private PhpNode targetExpr;
    private final String staticName;
    private final int methodId;
    @Child private PhpNode dynamicNameExpr;
    @Children private final PhpNode[] args;
    @Children private final LValueNode[] refTargets;
    @CompilationFinal(dimensions = 1) private final String[] argNames;
    @CompilationFinal(dimensions = 1) private final boolean[] spreads;
    private final boolean hasSpreadOrNamed;
    private final boolean hasSpread;

    public MethodCallNode(PhpNode targetExpr, String staticName, int methodId, PhpNode dynamicNameExpr, PhpNode[] args) {
        this(targetExpr, staticName, methodId, dynamicNameExpr, args, new LValueNode[args.length], null, null);
    }

    public MethodCallNode(PhpNode targetExpr, String staticName, int methodId, PhpNode dynamicNameExpr,
                          PhpNode[] args, LValueNode[] refTargets, String[] argNames, boolean[] spreads) {
        this.targetExpr = targetExpr;
        this.staticName = staticName;
        this.methodId = methodId;
        this.dynamicNameExpr = dynamicNameExpr;
        this.args = args;
        this.refTargets = refTargets;
        this.argNames = argNames;
        this.spreads = spreads;
        boolean complex = false;
        boolean spreadSeen = false;
        if (argNames != null) {
            for (String s : argNames) {
                if (s != null) {
                    complex = true;
                    break;
                }
            }
        }
        if (spreads != null) {
            for (boolean b : spreads) {
                if (b) {
                    complex = true;
                    spreadSeen = true;
                    break;
                }
            }
        }
        this.hasSpreadOrNamed = complex;
        this.hasSpread = spreadSeen;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        emitOnTarget(e, () -> e.expr(targetExpr));
    }

    /** Emits this call with an already-evaluated receiver (nullsafe {@code ?->}). */
    public void emitOnTarget(dev.phpruntime.bytecode.BytecodeEmitter e, Runnable target) {
        var b = e.builder();
        boolean keepRef = e.takeRef(this);
        com.oracle.truffle.api.bytecode.BytecodeLocal callee = e.enterCall();
        try {
            b.beginBlock();
            b.beginResolveMethod(new dev.phpruntime.bytecode.PhpBytecodeRootNode.MethodSite(staticName, methodId), callee);
            target.run();
            if (staticName != null) {
                b.emitLoadConstant(staticName);
            } else {
                e.expr(dynamicNameExpr);
            }
            b.endResolveMethod();
            if (hasSpreadOrNamed) {
                if (!hasSpread) {
                    com.oracle.truffle.api.bytecode.BytecodeLocal args = e.temp();
                    e.namedArgs(this.args, this.refTargets, this.argNames, callee, args);
                    b.beginDoNamedMethod(keepRef);
                    b.emitLoadLocal(callee);
                    b.emitLoadLocal(args);
                    b.endDoNamedMethod();
                } else {
                    com.oracle.truffle.api.bytecode.BytecodeLocal raw = e.temp();
                    e.beginRawArgs(raw);
                    e.complexArgs(args, refTargets, argNames, spreads, callee, raw);
                    b.beginDoComplexMethod(keepRef);
                    b.emitLoadLocal(callee);
                    b.emitLoadLocal(raw);
                    b.endDoComplexMethod();
                }
            } else {
                b.beginDoMethodCall(keepRef, callee);
                for (int i = 0; i < args.length; i++) {
                    e.sendArgument(args[i], refTargets[i], i, callee);
                }
                b.endDoMethodCall();
            }
            b.endBlock();
        } finally {
            e.exitCall();
        }
    }
}
