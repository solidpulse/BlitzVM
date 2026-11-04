package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;

/** A function-local variable stored in a Truffle frame slot. */
public final class LocalVarNode extends LValueNode {

    private final int slot;
    private final String name;

    public LocalVarNode(int slot, String name) {
        this.slot = slot;
        this.name = name;
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys) {
        return frame.getObject(slot);
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        frame.setObject(slot, value);
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        frame.setObject(slot, null);
    }

    @Override
    public String describe() {
        return "$" + name;
    }
}
