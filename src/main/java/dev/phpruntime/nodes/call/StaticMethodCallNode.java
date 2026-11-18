package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.expr.ClassRefNode;
import dev.phpruntime.nodes.var.LValueNode;

/** {@code Foo::method(...)}, {@code self::method(...)}, {@code parent::method(...)}, {@code static::method(...)}. */
public final class StaticMethodCallNode extends PhpNode {

    @Child private ClassRefNode classRef;
    private final String staticName;
    private final int methodId;
    @Child private PhpNode dynamicNameExpr;
    private final int thisSlot;
    private final int calledClassSlot;
    @Children private final PhpNode[] args;
    @Children private final LValueNode[] refTargets;
    @CompilationFinal(dimensions = 1) private final String[] argNames;
    @CompilationFinal(dimensions = 1) private final boolean[] spreads;
    private final boolean hasSpreadOrNamed;

    public StaticMethodCallNode(ClassRefNode classRef, String staticName, int methodId, PhpNode dynamicNameExpr,
                                int thisSlot, int calledClassSlot, PhpNode[] args) {
        this(classRef, staticName, methodId, dynamicNameExpr, thisSlot, calledClassSlot,
                args, new LValueNode[args.length], null, null);
    }

    public StaticMethodCallNode(ClassRefNode classRef, String staticName, int methodId, PhpNode dynamicNameExpr,
                                int thisSlot, int calledClassSlot, PhpNode[] args, LValueNode[] refTargets,
                                String[] argNames, boolean[] spreads) {
        this.classRef = classRef;
        this.staticName = staticName;
        this.methodId = methodId;
        this.dynamicNameExpr = dynamicNameExpr;
        this.thisSlot = thisSlot;
        this.calledClassSlot = calledClassSlot;
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
        boolean keepRef = e.takeRef(this);
        com.oracle.truffle.api.bytecode.BytecodeLocal callee = e.enterCall();
        try {
            b.beginBlock();
            b.beginResolveStaticMethod(new dev.phpruntime.bytecode.PhpBytecodeRootNode.MethodSite(staticName, methodId),
                    callee, classRef.isForwarding(), calledClassSlot >= 0);
            e.classRef(classRef);
            if (staticName != null) {
                b.emitLoadConstant(staticName);
            } else {
                e.expr(dynamicNameExpr);
            }
            e.loadSlotOrNull(thisSlot);
            e.loadSlotOrNull(calledClassSlot);
            b.endResolveStaticMethod();
            if (hasSpreadOrNamed) {
                com.oracle.truffle.api.bytecode.BytecodeLocal raw = e.temp();
                e.beginRawArgs(raw);
                e.complexArgs(args, refTargets, argNames, spreads, callee, raw);
                b.beginDoComplexMethod(keepRef);
                b.emitLoadLocal(callee);
                b.emitLoadLocal(raw);
                b.endDoComplexMethod();
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
