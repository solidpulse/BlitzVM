package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.call.DispatchCallNode;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;

/** {@code new ClassName(args...)}. */
public final class NewNode extends PhpNode {

    @Child private ClassRefNode classRef;
    @Children private final PhpNode[] args;
    @Child private DispatchCallNode dispatch;

    public NewNode(ClassRefNode classRef, PhpNode[] args) {
        this.classRef = classRef;
        this.args = args;
        this.dispatch = new DispatchCallNode();
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpClass cls = classRef.resolve(frame);
        if (cls.isInterface() || cls.isAbstract()) {
            throw new PhpError(
                    "Cannot instantiate " + (cls.isInterface() ? "interface " : "abstract class ") + cls.name(),
                    this);
        }
        PhpObject obj = new PhpObject(cls, PhpContext.get(this).nextObjectId());
        cls.populateDefaults(obj);
        if (cls.isThrowable()) {
            dev.phpruntime.runtime.PhpExceptions.initNewThrowable(obj, this);
        }
        PhpClass.MethodEntry ctor = cls.constructMethod();
        if (ctor != null) {
            try {
                dispatch.call(ctor.callTarget(), evalArgs(frame, obj, cls));
            } catch (PhpError e) {
                throw e.at(this);
            }
        } else {
            discardArgs(frame);
        }
        return obj;
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

    @ExplodeLoop
    private void discardArgs(VirtualFrame frame) {
        for (PhpNode arg : args) {
            arg.execute(frame);
        }
    }
}
