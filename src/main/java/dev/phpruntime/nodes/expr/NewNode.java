package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.call.CallArgHelper;
import dev.phpruntime.nodes.call.DispatchCallNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;

/** {@code new ClassName(args...)}. */
public final class NewNode extends PhpNode {

    @Child private ClassRefNode classRef;
    @Children private final PhpNode[] args;
    @Children private final LValueNode[] refTargets;
    @CompilationFinal(dimensions = 1) private final String[] argNames;
    @CompilationFinal(dimensions = 1) private final boolean[] spreads;
    private final boolean hasSpreadOrNamed;
    @Child private DispatchCallNode dispatch;

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
        this.dispatch = new DispatchCallNode();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpClass cls = classRef.resolve(frame);
        PhpContext ctx = PhpContext.get(this);
        if (cls == ctx.closureClass()) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Instantiation of Closure is not allowed", this);
        }
        if (cls.isInterface() || cls.isTrait() || cls.isAbstract()) {
            String kind = cls.isInterface() ? "interface " : (cls.isTrait() ? "trait " : "abstract class ");
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Cannot instantiate " + kind + cls.name(), this);
        }
        PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
        cls.populateDefaults(obj);
        if (cls.isThrowable()) {
            dev.phpruntime.runtime.PhpExceptions.initNewThrowable(obj, this);
        }
        PhpClass.MethodEntry ctor = cls.constructMethod();
        if (ctor != null) {
            RootCallTarget target = ctor.callTarget();
            RootCallTarget cached = dispatch.cachedTarget();
            try {
                // The cached target is a PE constant; callMethod adds the using class for traits.
                Object[] callArgs;
                if (cached == target) {
                    callArgs = evalCtorArgs(frame, cached, obj, cls);
                } else {
                    if (!dispatch.isPolymorphic()) {
                        // Monomorphic sites compile without the generic path; a new target re-specializes.
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                    }
                    callArgs = evalCtorArgs(frame, target, obj, cls);
                }
                dispatch.callMethod(ctor, callArgs);
            } catch (PhpError e) {
                throw e.at(this);
            }
        } else {
            discardArgs(frame, cls);
        }
        return obj;
    }

    private Object[] evalCtorArgs(VirtualFrame frame, RootCallTarget target, PhpObject obj, PhpClass cls) {
        if (hasSpreadOrNamed) {
            return CallArgHelper.evalComplexUserArgs(frame, target, new Object[]{obj, cls},
                    args, refTargets, argNames, spreads, this);
        }
        PhpFunctionRootNode fnRoot = target.getRootNode() instanceof PhpFunctionRootNode r ? r : null;
        if (fnRoot != null && fnRoot.hasAnyRefParam()) {
            return evalArgsWithRefs(frame, fnRoot, obj, cls);
        }
        return evalArgs(frame, obj, cls);
    }

    @ExplodeLoop
    private Object[] evalArgsWithRefs(VirtualFrame frame, PhpFunctionRootNode fnRoot, PhpObject obj, PhpClass cls) {
        Object[] values = new Object[2 + args.length];
        values[0] = obj;
        values[1] = cls;
        for (int i = 0; i < args.length; i++) {
            values[2 + i] = CallArgHelper.evalArgForParam(frame, args[i], refTargets[i], fnRoot, i, this);
        }
        return values;
    }

    @ExplodeLoop
    private Object[] evalArgs(VirtualFrame frame, PhpObject obj, PhpClass cls) {
        Object[] values = new Object[2 + args.length];
        values[0] = obj;
        values[1] = cls;
        for (int i = 0; i < args.length; i++) {
            values[2 + i] = PhpOps.copyOnAssign(args[i].execute(frame));
        }
        return values;
    }

    private void discardArgs(VirtualFrame frame, PhpClass cls) {
        if (hasSpreadOrNamed) {
            CallArgHelper.RawCallArgs raw = CallArgHelper.evaluateCallSite(frame, args, refTargets, argNames, spreads, null, this);
            if (raw.named() != null && !raw.named().isEmpty()) {
                String first = raw.named().keySet().iterator().next();
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Unknown named parameter $" + first, this);
            }
            return;
        }
        discardPlainArgs(frame);
    }

    @ExplodeLoop
    private void discardPlainArgs(VirtualFrame frame) {
        for (PhpNode arg : args) {
            arg.execute(frame);
        }
    }
}
