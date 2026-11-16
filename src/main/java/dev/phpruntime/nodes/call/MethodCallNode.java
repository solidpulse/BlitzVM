package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
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
    @Children private final LValueNode[] refTargets;
    @CompilationFinal(dimensions = 1) private final String[] argNames;
    @CompilationFinal(dimensions = 1) private final boolean[] spreads;
    private final boolean hasSpreadOrNamed;
    @Child private DispatchCallNode dispatch;

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
        this.dispatch = new DispatchCallNode();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return CallArgHelper.unwrapReturn(executeOnTargetRaw(frame, targetExpr.execute(frame)));
    }

    @Override
    public Object executeForRef(VirtualFrame frame) {
        return executeOnTargetRaw(frame, targetExpr.execute(frame));
    }

    /** Executes this call given an already-evaluated receiver (used by nullsafe {@code ?->}). */
    public Object executeOnTarget(VirtualFrame frame, Object target) {
        return CallArgHelper.unwrapReturn(executeOnTargetRaw(frame, target));
    }

    public Object executeOnTargetRaw(VirtualFrame frame, Object target) {
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
                        new Object[]{obj, cls, name, DispatchCallNode.evalMagicArgs(frame, args, argNames, spreads, this)});
            }
            throw new PhpError("Call to undefined method " + cls.name() + "::" + name + "()", this);
        }
        if (entry.callTarget() == null) {
            throw new PhpError("Cannot call abstract method " + entry.declaringClass().name() + "::" + entry.name() + "()", this);
        }
        Object thisArg = entry.isStatic() ? PhpNull.INSTANCE : obj;
        try {
            return dispatch.call(entry.callTarget(), evalMethodArgs(frame, entry.callTarget(), thisArg, cls));
        } catch (PhpError e) {
            throw e.at(this);
        }
    }

    private Object[] evalMethodArgs(VirtualFrame frame, RootCallTarget target, Object thisArg, PhpClass calledClass) {
        RootCallTarget sigTarget = target;
        if (thisArg instanceof PhpObject o && o.closure() != null && o.closure().callTarget() != null
                && target.getRootNode() instanceof ClosureInvokeRootNode) {
            sigTarget = o.closure().callTarget();
        }
        if (hasSpreadOrNamed) {
            return CallArgHelper.evalComplexUserArgs(frame, sigTarget, new Object[]{thisArg, calledClass},
                    args, refTargets, argNames, spreads, this);
        }
        PhpFunctionRootNode fnRoot = sigTarget.getRootNode() instanceof PhpFunctionRootNode r ? r : null;
        if (fnRoot != null && fnRoot.hasAnyRefParam()) {
            return evalArgsWithRefs(frame, fnRoot, thisArg, calledClass);
        }
        return evalArgs(frame, thisArg, calledClass);
    }

    @ExplodeLoop
    private Object[] evalArgsWithRefs(VirtualFrame frame, PhpFunctionRootNode fnRoot, Object thisArg, PhpClass calledClass) {
        Object[] values = new Object[2 + args.length];
        values[0] = thisArg;
        values[1] = calledClass;
        for (int i = 0; i < args.length; i++) {
            values[2 + i] = CallArgHelper.evalArgForParam(frame, args[i], refTargets[i], fnRoot, i, this);
        }
        return values;
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
