package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

/** Engine-initiated PHP method call, with the same target/trait cache as an explicit call. */
public final class ObjectMethodNode extends Node {
    private final int methodId;
    private final String name;
    @Child private DispatchCallNode dispatch = new DispatchCallNode();

    public ObjectMethodNode(PhpLanguage language, String name) {
        this.methodId = language.methodId(name);
        this.name = name;
    }

    public Object call(PhpObject receiver) {
        return callRaw(receiver, new Object[]{receiver, receiver.phpClass()});
    }

    public Object call(PhpObject receiver, Object arg) {
        return callRaw(receiver, new Object[]{receiver, receiver.phpClass(), arg});
    }

    public Object call(PhpObject receiver, Object arg, Object value) {
        return callRaw(receiver, new Object[]{receiver, receiver.phpClass(), arg, value});
    }

    private Object callRaw(PhpObject receiver, Object[] args) {
        PhpClass.MethodEntry entry = receiver.phpClass().methodById(methodId);
        if (entry == null || entry.callTarget() == null) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("Call to undefined method " + receiver.phpClass().name() + "::" + name + "()", this);
        }
        RootCallTarget cached = dispatch.cachedTarget();
        if (cached == entry.callTarget()) {
            adaptArgs(cached, args);
            return dispatch.callMethod(entry, args);
        }
        adaptArgs(entry.callTarget(), args);
        return dispatch.callMethod(entry, args);
    }

    private static void adaptArgs(RootCallTarget target, Object[] args) {
        if (dev.phpruntime.nodes.PhpFunctionRootNode.of(target.getRootNode()) instanceof dev.phpruntime.nodes.PhpFunctionRootNode root && root.hasAnyRefParam()) {
            for (int i = 2; i < args.length; i++) {
                if (root.isParamByRef(i - 2) && !(args[i] instanceof PhpReference)) {
                    args[i] = new PhpReference(PhpOps.copyOnAssign(args[i]));
                }
            }
        }
    }
}
