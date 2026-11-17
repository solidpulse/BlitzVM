package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * The base of a bytecode index path whose variable lives in a bytecode local: the operation
 * passes the variable's value as base key 0 and a one-element holder as base key 1, and stores
 * back whatever this node was asked to write there.
 */
public final class HeldValueBase extends LValueNode {

    private final String name;

    public HeldValueBase(String name) {
        this.name = name;
    }

    public static Object[] keys(Object value, Object[] holder) {
        return new Object[]{value, holder};
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys) {
        return keys[0];
    }

    @Override
    public Object readIfSet(VirtualFrame frame, Object[] keys) {
        return keys[0] == dev.phpruntime.runtime.PhpNull.INSTANCE ? null : keys[0];
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        ((Object[]) keys[1])[0] = value;
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        throw new IllegalStateException("unset of a held base");
    }

    @Override
    public String describe() {
        return "$" + name;
    }
}
