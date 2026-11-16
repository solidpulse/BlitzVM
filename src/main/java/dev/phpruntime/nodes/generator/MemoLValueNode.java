package dev.phpruntime.nodes.generator;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.PhpReference;

/** Preserves an lvalue's evaluated receiver/keys when its assignment's RHS suspends. */
public final class MemoLValueNode extends LValueNode {
    @Child private LValueNode target;
    private final int slot;
    private final int resultSlot;
    private final int cellSlot;
    private record Result(Object value) {}

    public MemoLValueNode(LValueNode target, int slot, int resultSlot, int cellSlot) {
        this.target = target;
        this.slot = slot;
        this.resultSlot = resultSlot;
        this.cellSlot = cellSlot;
    }

    @Override
    public Object[] evalKeys(VirtualFrame frame) {
        Object saved = frame.getObject(slot);
        if (saved instanceof Object[] keys) {
            return keys;
        }
        Object[] keys = target.evalKeys(frame);
        frame.setObject(slot, keys);
        return keys;
    }

    @Override public Object read(VirtualFrame f, Object[] k) {
        Object saved = f.getObject(resultSlot);
        if (saved instanceof Result result) { return result.value; }
        Object value = target.read(f, k);
        f.setObject(resultSlot, new Result(value));
        return value;
    }
    @Override public Object readIfSet(VirtualFrame f, Object[] k) {
        Object saved = f.getObject(resultSlot);
        if (saved instanceof Result result) { return result.value; }
        Object value = target.readIfSet(f, k);
        f.setObject(resultSlot, new Result(value));
        return value;
    }
    @Override public boolean isSet(VirtualFrame f, Object[] k) {
        Object saved = f.getObject(resultSlot);
        if (saved instanceof Result result) { return (Boolean) result.value; }
        boolean value = target.isSet(f, k);
        f.setObject(resultSlot, new Result(value));
        return value;
    }
    @Override public void write(VirtualFrame f, Object[] k, Object v) { target.write(f, k, v); }
    @Override public String appendString(VirtualFrame f, Object[] k, String suffix, boolean resultNeeded) {
        return target.appendString(f, k, suffix, resultNeeded);
    }
    @Override public PhpReference obtainCell(VirtualFrame f, Object[] k) {
        Object saved = f.getObject(cellSlot);
        if (saved instanceof PhpReference cell) { return cell; }
        PhpReference cell = target.obtainCell(f, k);
        f.setObject(cellSlot, cell);
        return cell;
    }
    @Override public void bindCell(VirtualFrame f, Object[] k, PhpReference v) { target.bindCell(f, k, v); }
    @Override public void unset(VirtualFrame f, Object[] k) { target.unset(f, k); }
    @Override public String describe() { return target.describe(); }
    @Override public Object execute(VirtualFrame f) {
        Object saved = f.getObject(resultSlot);
        if (saved instanceof Result result) { return result.value; }
        Object value = target.execute(f);
        f.setObject(resultSlot, new Result(value));
        return value;
    }
}
