package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.PhpNode;

/**
 * {@code $expr(args...)}: calls any callable expression (Closure, function name string,
 * {@code "Class::method"}, {@code [$obj, 'm']} / {@code ['Class', 'm']}, or invokable object).
 */
public final class CallValueNode extends PhpNode {

    @Child private PhpNode targetExpr;
    @Children private final PhpNode[] args;
    @Children private final dev.phpruntime.nodes.var.LValueNode[] refTargets;
    @CompilationFinal(dimensions = 1) private final String[] argNames;
    @CompilationFinal(dimensions = 1) private final boolean[] spreads;
    private final boolean hasSpreadOrNamed;
    private final boolean hasSpread;
    private final int outerThisSlot;

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
        this.outerThisSlot = outerThisSlot;
    }


    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        boolean keepRef = e.takeRef(this);
        com.oracle.truffle.api.bytecode.BytecodeLocal callee = e.enterCall();
        try {
            b.beginBlock();
            b.beginResolveCallable(callee);
            e.expr(targetExpr);
            e.loadSlotOrNull(outerThisSlot);
            b.endResolveCallable();
            if (hasSpreadOrNamed) {
                if (!hasSpread) {
                    com.oracle.truffle.api.bytecode.BytecodeLocal args = e.temp();
                    e.namedArgs(this.args, this.refTargets, this.argNames, callee, args);
                    b.beginDoNamedValue(keepRef);
                    b.emitLoadLocal(callee);
                    b.emitLoadLocal(args);
                    b.endDoNamedValue();
                } else {
                    com.oracle.truffle.api.bytecode.BytecodeLocal raw = e.temp();
                    e.beginRawArgs(raw);
                    e.complexArgs(args, refTargets, argNames, spreads, callee, raw);
                    b.beginDoComplexValue(keepRef);
                    b.emitLoadLocal(callee);
                    b.emitLoadLocal(raw);
                    b.endDoComplexValue();
                }
            } else {
                b.beginDoValueCall(keepRef);
                b.emitLoadLocal(callee);
                for (int i = 0; i < args.length; i++) {
                    e.sendArgument(args[i], refTargets[i], i, callee);
                }
                b.endDoValueCall();
            }
            b.endBlock();
        } finally {
            e.exitCall();
        }
    }
}
