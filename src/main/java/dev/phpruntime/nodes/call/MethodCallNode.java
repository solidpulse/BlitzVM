package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;

/** {@code $obj->method(args...)} or {@code $obj->$name(args...)}. */
public final class MethodCallNode extends PhpNode {

    @Child private PhpNode targetExpr;
    private final String staticName;
    private final int methodId;
    @Child private PhpNode dynamicNameExpr;
    @Children private final PhpNode[] args;
    @Child private DispatchCallNode dispatch;

    public MethodCallNode(PhpNode targetExpr, String staticName, int methodId, PhpNode dynamicNameExpr, PhpNode[] args) {
        this.targetExpr = targetExpr;
        this.staticName = staticName;
        this.methodId = methodId;
        this.dynamicNameExpr = dynamicNameExpr;
        this.args = args;
        this.dispatch = new DispatchCallNode();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return executeOnTarget(frame, targetExpr.execute(frame));
    }

    /** Executes this call given an already-evaluated receiver (used by nullsafe {@code ?->}). */
    public Object executeOnTarget(VirtualFrame frame, Object target) {
        String name = staticName != null ? staticName : PhpOps.toStr(dynamicNameExpr.execute(frame));
        if (!(target instanceof PhpObject obj)) {
            throw new PhpError("Call to a member function " + name + "() on " + PhpOps.typeName(target), this);
        }
        PhpClass cls = obj.phpClass();
        PhpClass.MethodEntry entry = methodId >= 0 ? cls.methodById(methodId) : cls.lookupMethod(name);
        if (entry == null) {
            PhpClass.MethodEntry magic = cls.callMethod();
            if (magic != null && magic.callTarget() != null) {
                return dispatch.call(magic.callTarget(),
                        new Object[]{obj, cls, name, DispatchCallNode.evalMagicArgs(frame, args)});
            }
            throw new PhpError("Call to undefined method " + cls.name() + "::" + name + "()", this);
        }
        if (entry.callTarget() == null) {
            throw new PhpError("Cannot call abstract method " + entry.declaringClass().name() + "::" + entry.name() + "()", this);
        }
        Object thisArg = entry.isStatic() ? PhpNull.INSTANCE : obj;
        return dispatch.call(entry.callTarget(), evalArgs(frame, thisArg, cls));
    }

    @ExplodeLoop
    private Object[] evalArgs(VirtualFrame frame, Object thisArg, PhpClass calledClass) {
        Object[] values = new Object[2 + args.length];
        values[0] = thisArg;
        values[1] = calledClass;
        for (int i = 0; i < args.length; i++) {
            values[2 + i] = PhpOps.copyOnAssign(args[i].execute(frame));
        }
        return values;
    }
}
