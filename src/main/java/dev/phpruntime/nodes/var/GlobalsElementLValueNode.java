package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

/**
 * {@code $GLOBALS[key]}: reads, writes, binds, or unsets a single global variable directly
 * without materializing the entire {@code $GLOBALS} snapshot array.
 */
public final class GlobalsElementLValueNode extends LValueNode {

    @Child private PhpNode keyNode;
    private final String constName;
    private final int constGlobalId;

    public GlobalsElementLValueNode(PhpNode keyNode, String constName, int constGlobalId) {
        this.keyNode = keyNode;
        this.constName = constName;
        this.constGlobalId = constGlobalId;
    }

    @Override
    public Object[] evalKeys(VirtualFrame frame) {
        if (constName != null) {
            return NO_KEYS;
        }
        if (keyNode == null) {
            throw PhpError.fatal("Cannot append to $GLOBALS").at(this);
        }
        return new Object[]{keyNode.execute(frame)};
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys) {
        PhpContext ctx = PhpContext.get(this);
        if (constName != null) {
            return constGlobalId >= 0 ? ctx.readGlobalById(constGlobalId) : null;
        }
        String name = toGlobalKey(keys[0]);
        return "GLOBALS".equals(name) ? null : ctx.readGlobal(name);
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        PhpContext ctx = PhpContext.get(this);
        if (constName != null) {
            if (constGlobalId < 0) {
                throw PhpError.fatal("$GLOBALS can only be modified using the $GLOBALS[$name] = $value syntax").at(this);
            }
            ctx.writeGlobalById(constGlobalId, value);
            return;
        }
        ctx.writeGlobal(toGlobalKey(keys[0]), value);
    }

    @Override
    public PhpReference obtainCell(VirtualFrame frame, Object[] keys) {
        PhpContext ctx = PhpContext.get(this);
        if (constName != null) {
            if (constGlobalId < 0) {
                throw PhpError.fatal("Cannot acquire reference to $GLOBALS").at(this);
            }
            return ctx.globalRefById(constGlobalId);
        }
        return ctx.globalRef(toGlobalKey(keys[0]));
    }

    @Override
    public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell) {
        PhpContext ctx = PhpContext.get(this);
        if (constName != null) {
            if (constGlobalId < 0) {
                throw PhpError.fatal("$GLOBALS can only be modified using the $GLOBALS[$name] = $value syntax").at(this);
            }
            ctx.bindGlobalById(constGlobalId, cell);
            return;
        }
        ctx.bindGlobal(toGlobalKey(keys[0]), cell);
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        PhpContext ctx = PhpContext.get(this);
        if (constName != null) {
            if (constGlobalId < 0) {
                throw PhpError.fatal("$GLOBALS can only be modified using the $GLOBALS[$name] = $value syntax").at(this);
            }
            ctx.unsetGlobalById(constGlobalId);
            return;
        }
        ctx.unsetGlobal(toGlobalKey(keys[0]));
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] keys = evalKeys(frame);
        Object v = read(frame, keys);
        if (v == null) {
            warnMissing(keys);
            return PhpNull.INSTANCE;
        }
        return v;
    }

    @TruffleBoundary
    private String toGlobalKey(Object rawKey) {
        Object norm = PhpArray.normalizeKey(rawKey);
        return norm instanceof String s ? s : String.valueOf(norm);
    }

    @TruffleBoundary
    private void warnMissing(Object[] keys) {
        String shown;
        if (constName != null) {
            shown = '"' + constName + '"';
        } else {
            Object k = PhpArray.normalizeKey(keys[0]);
            shown = k instanceof String s ? '"' + s + '"' : PhpOps.toStr(k);
        }
        PhpContext.get(this).warning("Undefined array key " + shown, line());
    }

    @Override
    public String describe() {
        return "$GLOBALS[...]";
    }
}
