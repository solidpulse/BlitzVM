package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpOps;

/**
 * Shared monomorphic-with-indirect-fallback call dispatch helper for method and
 * constructor call sites under ContextPolicy.SHARED.
 */
public final class DispatchCallNode extends Node {

    @Child private DirectCallNode directCall;
    @Child private IndirectCallNode indirectCall;

    public Object call(RootCallTarget target, Object[] args) {
        // Read each child once: another request may be installing it concurrently.
        DirectCallNode direct = directCall;
        if (direct != null && direct.getCallTarget() == target) {
            return direct.call(args);
        }
        IndirectCallNode indirect = indirectCall;
        if (indirect != null) {
            return indirect.call(target, args);
        }
        return callDynamic(target, args);
    }

    private Object callDynamic(RootCallTarget target, Object[] args) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        if (prepareCall(target)) {
            return directCall.call(args);
        }
        return indirectCall.call(target, args);
    }

    @TruffleBoundary
    private boolean prepareCall(RootCallTarget target) {
        java.util.concurrent.locks.Lock lock = getLock();
        lock.lock();
        try {
            if (directCall == null) {
                directCall = insert(DirectCallNode.create(target));
                return true;
            }
            if (directCall.getCallTarget() == target) {
                return true;
            }
            if (indirectCall == null) {
                indirectCall = insert(Truffle.getRuntime().createIndirectCallNode());
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    @ExplodeLoop
    public static PhpArray evalMagicArgs(VirtualFrame frame, PhpNode[] args) {
        PhpArray arr = new PhpArray();
        for (PhpNode arg : args) {
            arr.append(PhpOps.copyOnAssign(arg.execute(frame)));
        }
        return arr;
    }

    public static PhpArray evalMagicArgs(VirtualFrame frame, PhpNode[] args, String[] argNames, boolean[] spreads, Node site) {
        if (argNames == null && spreads == null) {
            return evalMagicArgs(frame, args);
        }
        CallArgHelper.RawCallArgs raw = CallArgHelper.evaluateCallSite(frame, args, null, argNames, spreads, null, site);
        return buildMagicArrayFromRaw(raw);
    }

    @TruffleBoundary
    private static PhpArray buildMagicArrayFromRaw(CallArgHelper.RawCallArgs raw) {
        PhpArray arr = new PhpArray();
        for (Object p : raw.positional()) {
            arr.append(PhpOps.copyOnAssign(p));
        }
        if (raw.named() != null) {
            for (java.util.Map.Entry<String, Object> e : raw.named().entrySet()) {
                arr.put(e.getKey(), PhpOps.copyOnAssign(e.getValue()));
            }
        }
        return arr;
    }
}
