package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpOps;

/** {@code [1, 'k' => 2]} or {@code array(...)}. A null key node means "next index". */
public final class ArrayLiteralNode extends PhpNode {

    @Children private final PhpNode[] keys;
    @Children private final PhpNode[] values;

    public ArrayLiteralNode(PhpNode[] keys, PhpNode[] values) {
        this.keys = keys;
        this.values = values;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        PhpArray a = new PhpArray();
        for (int i = 0; i < values.length; i++) {
            Object k = keys[i] == null ? null : keys[i].execute(frame);
            Object v = PhpOps.copyOnAssign(values[i].execute(frame));
            if (k == null) {
                a.append(v);
            } else {
                a.put(k, v);
            }
        }
        return a;
    }
}
