package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

import java.util.Set;

/** Reads a variable whose name is computed at runtime, e.g. {@code "${$x}"} in string interpolation. */
public final class ReadDynamicVarNode extends PhpNode {

    private static final Set<String> SUPERGLOBALS = Set.of(
            "_GET", "_POST", "_SERVER", "_REQUEST", "_COOKIE", "_ENV", "_FILES", "GLOBALS");

    @Child private PhpNode nameExpr;
    private final boolean isGlobal;

    public ReadDynamicVarNode(PhpNode nameExpr, boolean isGlobal) {
        this.nameExpr = nameExpr;
        this.isGlobal = isGlobal;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        String name = PhpOps.toStr(nameExpr.execute(frame));
        if (isGlobal || SUPERGLOBALS.contains(name)) {
            return readGlobalVar(name);
        }
        int slot = findSlot(frame.getFrameDescriptor(), name);
        if (slot >= 0) {
            if (frame.isLong(slot)) {
                return frame.getLong(slot);
            }
            if (frame.isDouble(slot)) {
                return frame.getDouble(slot);
            }
            if (frame.isObject(slot)) {
                Object v = frame.getObject(slot);
                if (v instanceof PhpReference ref) {
                    return ref.value;
                }
                if (v != null) {
                    return v;
                }
            }
        }
        warnUndefined(name);
        return PhpNull.INSTANCE;
    }

    @TruffleBoundary
    private Object readGlobalVar(String name) {
        PhpContext ctx = PhpContext.get(this);
        Object v = ctx.readGlobal(name);
        if (v == null) {
            ctx.warning("Undefined variable $" + name, line());
            return PhpNull.INSTANCE;
        }
        return v;
    }

    @TruffleBoundary
    private static int findSlot(FrameDescriptor fd, String name) {
        int n = fd.getNumberOfSlots();
        for (int i = 0; i < n; i++) {
            if (name.equals(fd.getSlotName(i))) {
                return i;
            }
        }
        return -1;
    }

    @TruffleBoundary
    private void warnUndefined(String name) {
        PhpContext.get(this).warning("Undefined variable $" + name, line());
    }
}
