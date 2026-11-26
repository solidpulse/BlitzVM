package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

/** Engine-initiated PHP method call, with the same target/trait cache as an explicit call. */
public final class ObjectMethodNode extends Node {
    private final int methodId;
    private final String name;
    @Child private MethodEntryNode entries;
    @Child private DispatchCallNode dispatch = new DispatchCallNode();

    public ObjectMethodNode(PhpLanguage language, String name) {
        this.methodId = language.methodId(name);
        this.name = name;
        this.entries = MethodEntryNode.create(methodId);
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
        PhpClass.MethodEntry entry = entries.execute(receiver);
        if (entry == null || entry.callTarget() == null) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("Call to undefined method " + receiver.phpClass().name() + "::" + name + "()", this);
        }
        if (entry.refMask() != 0) {
            adaptArgs(entry, args);
        }
        return site == null ? callUnsited(entry, args) : dispatch.callMethod(entry, args, site);
    }

    /** The entry this site caches for {@code receiver}, for callers that read its target. */
    public PhpClass.MethodEntry entryFor(PhpObject receiver) {
        return entries.execute(receiver);
    }

    /** An engine call without a guest location (ArrayAccess, Countable): see UNKNOWN_SITE. */
    private Object callUnsited(PhpClass.MethodEntry entry, Object[] args) {
        dev.phpruntime.PhpContext ctx = dev.phpruntime.PhpContext.get(this);
        int depth = ctx.pushTraceFrame(dev.phpruntime.runtime.PhpBacktrace.UNKNOWN_SITE);
        try {
            return dispatch.callMethod(entry, args);
        } finally {
            ctx.popTraceFrame(depth);
        }
    }

    /**
     * Wraps the arguments of by-reference parameters in cells. The method entry carries the
     * same mask its root node does, so a site that sees two classes does not walk from a
     * non-constant call target to its root on every call.
     */
    private static void adaptArgs(PhpClass.MethodEntry entry, Object[] args) {
        long mask = entry.refMask();
        int variadic = entry.variadicIndex();
        if (mask == 0) {
            return;
        }
        for (int i = 2; i < args.length; i++) {
            int param = i - 2;
            boolean byRef = param < 63 ? ((mask >>> param) & 1L) != 0 : mask < 0;
            if (!byRef && variadic >= 0 && param >= variadic) {
                byRef = variadic < 63 ? ((mask >>> variadic) & 1L) != 0 : mask < 0;
            }
            if (byRef && !(args[i] instanceof PhpReference)) {
                args[i] = new PhpReference(PhpOps.copyOnAssign(args[i]));
            }
        }
    }
}
