package dev.phpruntime.nodes.var;

import dev.phpruntime.nodes.PhpNode;

/** {@code isset($a, $b['k'], static::C['k'])}: true only if every operand exists and is not null. Never warns. */
public final class IssetNode extends PhpNode {

    @Children private final PhpNode[] targets;

    public IssetNode(PhpNode[] targets) {
        this.targets = targets;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        if (targets.length > 1) {
            b.beginPhpAnd();
        }
        for (PhpNode t : targets) {
            if (t instanceof LocalVarNode lv) {
                b.emitIssetVar(e.accessor(lv), e.info(lv));
            } else if (t instanceof IndexReadNode ir) {
                ir.emitIsset(e);
            } else if (t instanceof IndexLValueNode il) {
                il.emitRead(e, 2);
            } else if (t instanceof PropertyLValueNode || t instanceof GlobalVarNode
                    || t instanceof GlobalsElementLValueNode
                    || t instanceof StaticPropertyLValueNode
                    || dev.phpruntime.bytecode.BytecodeEmitter.isTableVar(t)) {
                e.place((LValueNode) t, dev.phpruntime.bytecode.PhpBytecodeRootNode.P_ISSET, 0, null);
            } else if (t instanceof LValueNode) {
                throw e.unsupported("isset of " + t.getClass().getSimpleName());
            } else {
                b.emitLoadConstant(true);
            }
        }
        if (targets.length > 1) {
            b.endPhpAnd();
        }
    }
}
