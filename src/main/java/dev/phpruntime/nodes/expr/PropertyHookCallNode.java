package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.call.CallNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.PhpNull;

/**
 * {@code parent::$prop::get()} / {@code parent::$prop::set($value)} inside a property hook.
 * Emitted as a call to the internal builtin that dispatches to the parent's hook target.
 */
public final class PropertyHookCallNode extends PhpNode {

    private final String propertyName;
    private final boolean get;
    @Child private CallNode call;

    public PropertyHookCallNode(ClassRefNode classRef, String propertyName, boolean get, PhpNode valueExpr,
                                int thisSlot, int calledClassSlot) {
        this.propertyName = propertyName;
        this.get = get;
        PhpNode[] args = new PhpNode[]{
                new SlotValueNode(thisSlot, calledClassSlot),
                new ClassRefValueNode(classRef),
                new ConstNode(propertyName),
                new ConstNode(get ? 0L : 1L),
                valueExpr != null ? valueExpr : new ConstNode(PhpNull.INSTANCE)
        };
        this.call = new CallNode("__php_hook_call", -1, args, new LValueNode[args.length]);
    }

    /** Loads {@code $this} and the called class together for the builtin. */
    private static final class SlotValueNode extends PhpNode {
        private final int thisSlot;
        private final int calledClassSlot;

        SlotValueNode(int thisSlot, int calledClassSlot) {
            this.thisSlot = thisSlot;
            this.calledClassSlot = calledClassSlot;
        }

        @Override
        public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
            e.loadSlotOrNull(thisSlot);
        }
    }

    private static final class ClassRefValueNode extends PhpNode {
        @Child private ClassRefNode classRef;

        ClassRefValueNode(ClassRefNode classRef) {
            this.classRef = classRef;
        }

        @Override
        public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
            e.classRef(classRef);
        }
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        call.emit(e);
    }
}
