package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.expr.ClassRefNode;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;

/** {@code Foo::method(...)}, {@code self::method(...)}, {@code parent::method(...)}, {@code static::method(...)}. */
public final class StaticMethodCallNode extends PhpNode {

    @Child private ClassRefNode classRef;
    private final String staticName;
    private final int methodId;
    @Child private PhpNode dynamicNameExpr;
    private final int thisSlot;
    private final int calledClassSlot;
    @Children private final PhpNode[] args;
    @Child private DispatchCallNode dispatch;

    public StaticMethodCallNode(ClassRefNode classRef, String staticName, int methodId, PhpNode dynamicNameExpr,
                                int thisSlot, int calledClassSlot, PhpNode[] args) {
        this.classRef = classRef;
        this.staticName = staticName;
        this.methodId = methodId;
        this.dynamicNameExpr = dynamicNameExpr;
        this.thisSlot = thisSlot;
        this.calledClassSlot = calledClassSlot;
        this.args = args;
        this.dispatch = new DispatchCallNode();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpClass targetClass = classRef.resolve(frame);
        String name = staticName != null ? staticName : PhpOps.toStr(dynamicNameExpr.execute(frame));
        PhpClass.MethodEntry entry = methodId >= 0 ? targetClass.methodById(methodId) : targetClass.lookupMethod(name);
        PhpClass calledArg = classRef.isForwarding() && calledClassSlot >= 0
                ? (PhpClass) frame.getObject(calledClassSlot)
                : targetClass;
        if (entry == null) {
            if (classRef.isForwarding() && thisSlot >= 0
                    && frame.getObject(thisSlot) instanceof PhpObject thisObj
                    && targetClass.callMethod() != null && targetClass.callMethod().callTarget() != null) {
                return dispatch.call(targetClass.callMethod().callTarget(),
                        new Object[]{thisObj, calledArg, name, DispatchCallNode.evalMagicArgs(frame, args)});
            }
            PhpClass.MethodEntry magicStatic = targetClass.callStaticMethod();
            if (magicStatic != null && magicStatic.callTarget() != null) {
                return dispatch.call(magicStatic.callTarget(),
                        new Object[]{PhpNull.INSTANCE, calledArg, name, DispatchCallNode.evalMagicArgs(frame, args)});
            }
            throw new PhpError("Call to undefined method " + targetClass.name() + "::" + name + "()", this);
        }
        if (entry.callTarget() == null) {
            throw new PhpError("Cannot call abstract method " + entry.declaringClass().name() + "::" + entry.name() + "()", this);
        }
        Object thisArg;
        if (entry.isStatic()) {
            thisArg = PhpNull.INSTANCE;
        } else if (thisSlot >= 0
                && frame.getObject(thisSlot) instanceof PhpObject thisObj
                && thisObj.phpClass().isInstanceOf(entry.declaringClass())) {
            thisArg = thisObj;
        } else {
            throw new PhpError(
                    "Non-static method " + entry.declaringClass().name() + "::" + entry.name()
                            + "() cannot be called statically",
                    this);
        }
        return dispatch.call(entry.callTarget(), evalArgs(frame, thisArg, calledArg));
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
