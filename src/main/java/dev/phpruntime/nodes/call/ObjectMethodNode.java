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
        return callRaw(receiver, new Object[]{receiver, receiver.phpClass()}, null);
    }

    public Object call(PhpObject receiver, Object arg) {
        return callRaw(receiver, new Object[]{receiver, receiver.phpClass(), arg}, null);
    }

    public Object call(PhpObject receiver, Object arg, Object value) {
        return callRaw(receiver, new Object[]{receiver, receiver.phpClass(), arg, value}, null);
    }

    /** A call the engine makes on behalf of a language construct, attributed to {@code site}. */
    public Object callAt(PhpObject receiver, Node site) {
        return callRaw(receiver, new Object[]{receiver, receiver.phpClass()}, site);
    }

    public Object callAt(PhpObject receiver, Node site, Object arg) {
        return callRaw(receiver, new Object[]{receiver, receiver.phpClass(), arg}, site);
    }

    private Object callRaw(PhpObject receiver, Object[] args, Node site) {
        PhpClass.MethodEntry entry = receiver.phpClass().methodById(methodId);
        if (entry == null || entry.callTarget() == null) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("Call to undefined method " + receiver.phpClass().name() + "::" + name + "()", this);
        }
        RootCallTarget cached = dispatch.cachedTarget();
        if (cached == entry.callTarget()) {
            adaptArgs(cached, args);
            return site == null ? dispatch.callMethod(entry, args) : dispatch.callMethod(entry, args, site);
        }
        adaptArgs(entry.callTarget(), args);
        return site == null ? dispatch.callMethod(entry, args) : dispatch.callMethod(entry, args, site);
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
