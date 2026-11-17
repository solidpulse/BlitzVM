package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpObject;

/** {@code .=}: statement execution leaves the append storage unflattened. */
public final class StringAppendNode extends PhpNode {
    @Child private LValueNode target;
    @Child private PhpNode value;

    public StringAppendNode(LValueNode target, PhpNode value) {
        this.target = target;
        this.value = value;
    }

    @Override public Object execute(VirtualFrame frame) {
        return append(frame, true);
    }

    @Override public void executeVoid(VirtualFrame frame) { append(frame, false); }

    private String append(VirtualFrame frame, boolean resultNeeded) {
        Object[] keys = target.evalKeys(frame);
        try {
            // PHP evaluates the RHS before fetching the current LHS (including rebinding).
            Object suffix = value.execute(frame);
            if (suffix instanceof String s) {
                return target.appendString(frame, keys, s, resultNeeded);
            }
            if (!(suffix instanceof PhpObject)) {
                // Scalar conversion has no PHP side effects, so it can use append storage too.
                return target.appendString(frame, keys, PhpOps.toStr(suffix), resultNeeded);
            }
            // Conversions can call PHP (__toString): snapshot and convert the LHS first.
            PhpReference nested = target instanceof IndexLValueNode index && index.isNested()
                    && index.usesArrayAccess(frame, keys) ? index.obtainCell(frame, keys) : null;
            Object current = nested == null ? target.read(frame, keys) : nested.getValue();
            String result = concat(PhpOps.toStr(current == null ? PhpNull.INSTANCE : current), PhpOps.toStr(suffix));
            if (nested == null) { target.write(frame, keys, result); }
            else { nested.setValue(result); }
            return result;
        } catch (PhpError e) {
            throw e.at(this);
        }
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static String concat(String left, String right) {
        return left.concat(right);
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        emitAppend(e, true);
    }

    @Override
    public void emitStatement(dev.phpruntime.bytecode.BytecodeEmitter e) {
        emitAppend(e, false);
    }

    private void emitAppend(dev.phpruntime.bytecode.BytecodeEmitter e, boolean resultNeeded) {
        if (dev.phpruntime.bytecode.BytecodeEmitter.isPlace(target)) {
            e.place(target, dev.phpruntime.bytecode.PhpBytecodeRootNode.P_APPEND, resultNeeded ? 1 : 0, () -> e.expr(value));
            return;
        }
        if (!(target instanceof LocalVarNode lv) || "this".equals(lv.varName())) {
            throw e.unsupported("append to " + target.getClass().getSimpleName());
        }
        e.builder().beginAppendVar(e.accessor(lv), e.info(lv), resultNeeded);
        e.expr(value);
        e.builder().endAppendVar();
    }
}
