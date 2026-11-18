package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpSymbolTable;

/** Include-local named reads resolve once at entry to invocation-owned binding handles. */
public final class IncludedVarNode extends LValueNode {
    private final int slot;
    private final String name;
    public IncludedVarNode(int slot, String name) { this.slot = slot; this.name = name; }
    public int slot() { return slot; }
    public String varName() { return name; }
    public IncludedVarNode copyForRead() { return new IncludedVarNode(slot, name).at(line()); }
    private PhpSymbolTable.Access access(VirtualFrame frame) { return (PhpSymbolTable.Access) frame.getObject(slot); }
    @Override public Object read(VirtualFrame frame, Object[] keys) { return access(frame).read(); }
    @Override public void write(VirtualFrame frame, Object[] keys, Object value) { access(frame).write(value); }
    @Override public String appendString(VirtualFrame frame, Object[] keys, String suffix, boolean resultNeeded) {
        return access(frame).appendString(suffix, resultNeeded);
    }
    @Override public PhpReference obtainCell(VirtualFrame frame, Object[] keys) { return access(frame).obtain(); }
    @Override public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell) { access(frame).bind(cell.alias()); }
    @Override public void unset(VirtualFrame frame, Object[] keys) { access(frame).bind(null); }
    @Override public String describe() { return "$" + name; }
    @Override public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.place(this, dev.phpruntime.bytecode.PhpBytecodeRootNode.P_READ, 0, null);
    }
}
