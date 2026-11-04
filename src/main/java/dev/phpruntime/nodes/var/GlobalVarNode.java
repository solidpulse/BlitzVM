package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;

/**
 * A variable in the global symbol table: top-level script variables, variables
 * imported with {@code global $x}, and superglobals such as $_GET.
 */
public final class GlobalVarNode extends LValueNode {

    private final String name;

    public GlobalVarNode(String name) {
        this.name = name;
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys) {
        return PhpContext.get(this).readGlobal(name);
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        PhpContext.get(this).writeGlobal(name, value);
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        PhpContext.get(this).unsetGlobal(name);
    }

    @Override
    public String describe() {
        return "$" + name;
    }
}
