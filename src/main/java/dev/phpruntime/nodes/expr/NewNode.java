package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;

/** {@code new ClassName(args...)}. */
public final class NewNode extends PhpNode {

    @Child private ClassRefNode classRef;
    @Children private final PhpNode[] args;
    @Children private final LValueNode[] refTargets;
    @CompilationFinal(dimensions = 1) private final String[] argNames;
    @CompilationFinal(dimensions = 1) private final boolean[] spreads;
    private final boolean hasSpreadOrNamed;

    public NewNode(ClassRefNode classRef, PhpNode[] args) {
        this(classRef, args, new LValueNode[args.length], null, null);
    }

    public NewNode(ClassRefNode classRef, PhpNode[] args, LValueNode[] refTargets, String[] argNames, boolean[] spreads) {
        this.classRef = classRef;
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
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        com.oracle.truffle.api.bytecode.BytecodeLocal callee = e.enterCall();
        com.oracle.truffle.api.bytecode.BytecodeLocal object = e.temp();
        try {
            b.beginBlock();
            b.beginStoreLocal(object);
            b.beginNewObject();
            e.classRef(classRef);
            b.endNewObject();
            b.endStoreLocal();
            b.beginResolveConstructor(callee);
            b.emitLoadLocal(object);
            b.endResolveConstructor();
            if (hasSpreadOrNamed) {
                com.oracle.truffle.api.bytecode.BytecodeLocal raw = e.temp();
                e.beginRawArgs(raw);
                e.complexArgs(args, refTargets, argNames, spreads, callee, raw);
                b.beginDoComplexConstructor();
                b.emitLoadLocal(object);
                b.emitLoadLocal(callee);
                b.emitLoadLocal(raw);
                b.endDoComplexConstructor();
            } else {
                b.beginDoConstructorCall(callee);
                b.emitLoadLocal(object);
                for (int i = 0; i < args.length; i++) {
                    e.sendArgument(args[i], refTargets[i], i, callee);
                }
                b.endDoConstructorCall();
            }
            b.endBlock();
        } finally {
            e.exitCall();
        }
    }
}
