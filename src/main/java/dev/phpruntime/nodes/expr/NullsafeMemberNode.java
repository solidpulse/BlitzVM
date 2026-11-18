package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.call.MethodCallNode;
import dev.phpruntime.nodes.var.PropertyLValueNode;
import dev.phpruntime.runtime.PhpNull;

/** {@code $obj?->prop} or {@code $obj?->method(args...)}: returns {@code null} when receiver is {@code null}. */
public final class NullsafeMemberNode extends PhpNode {

    @Child private PhpNode targetExpr;
    @Child private PropertyLValueNode propertyNode;
    @Child private MethodCallNode methodNode;

    public NullsafeMemberNode(PhpNode targetExpr, PropertyLValueNode propertyNode) {
        this.targetExpr = targetExpr;
        this.propertyNode = propertyNode;
        this.methodNode = null;
    }

    public NullsafeMemberNode(PhpNode targetExpr, MethodCallNode methodNode) {
        this.targetExpr = targetExpr;
        this.propertyNode = null;
        this.methodNode = methodNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object target = targetExpr.execute(frame);
        if (target == PhpNull.INSTANCE) {
            return PhpNull.INSTANCE;
        }
        if (propertyNode != null) {
            return propertyNode.executeOnTarget(frame, target);
        }
        return methodNode.executeOnTarget(frame, target);
    }

    @Override
    public Object executeForRef(VirtualFrame frame) {
        Object target = targetExpr.execute(frame);
        if (target == PhpNull.INSTANCE) {
            return PhpNull.INSTANCE;
        }
        if (propertyNode != null) {
            return propertyNode.executeOnTarget(frame, target);
        }
        return methodNode.executeOnTargetRaw(frame, target);
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginBlock();
        com.oracle.truffle.api.bytecode.BytecodeLocal target = e.temp();
        b.beginStoreLocal(target);
        e.expr(targetExpr);
        b.endStoreLocal();
        com.oracle.truffle.api.bytecode.BytecodeLocal result = e.temp();
        b.beginStoreLocal(result);
        b.emitLoadConstant(PhpNull.INSTANCE);
        b.endStoreLocal();
        b.beginIfThen();
        b.beginNot();
        b.beginIdentical(false);
        b.emitLoadLocal(target);
        b.emitLoadConstant(PhpNull.INSTANCE);
        b.endIdentical();
        b.endNot();
        b.beginBlock();
        b.beginStoreLocal(result);
        if (propertyNode != null) {
            var spec = new dev.phpruntime.bytecode.PhpBytecodeRootNode.PlaceSpec(
                    propertyNode.headless(), 0, propertyNode.usesTraitSelf() ? 3 : 2);
            b.beginObjPlace(spec, dev.phpruntime.bytecode.PhpBytecodeRootNode.P_READ, 0);
            b.emitLoadLocal(target);
            if (propertyNode.staticName() != null) {
                b.emitLoadConstant(propertyNode.staticName());
            } else {
                b.beginToStr();
                e.expr(propertyNode.dynamicNameExpr());
                b.endToStr();
            }
            if (propertyNode.usesTraitSelf()) {
                e.loadSlotOrNull(propertyNode.traitSelfSlot());
            }
            b.endObjPlace();
        } else {
            methodNode.emitOnTarget(e, () -> b.emitLoadLocal(target));
        }
        b.endStoreLocal();
        b.endBlock();
        b.endIfThen();
        b.emitLoadLocal(result);
        b.endBlock();
    }
}
