package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpConstants;
import dev.phpruntime.runtime.PhpError;

/** Reads a constant from the per-context constant table at runtime. */
public final class ReadConstantNode extends PhpNode {

    private final String name;
    private final String nsName;
    /** No leading backslash and no "::": a global constant name. */
    private final boolean plain;
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal private boolean resolved;
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal private Object builtin;

    public ReadConstantNode(String name) {
        this(name, null);
    }

    public ReadConstantNode(String name, String nsName) {
        this.name = name;
        this.nsName = nsName;
        this.plain = !name.startsWith("\\") && !name.contains("::");
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpContext ctx = PhpContext.get(this);
        if (nsName != null) {
            Object nsValue = ctx.readConstant(nsName);
            if (nsValue != null) {
                return nsValue;
            }
            Object compileTime = PhpConstants.compileTime(name);
            if (compileTime != null) {
                return compileTime;
            }
        }
        Object value;
        if (plain) {
            // Built-in constants never change: resolve once per node, then a user constant is
            // one map read instead of lowercasing and probing the built-in tables each time.
            if (!resolved) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
                builtin = PhpConstants.lookupBuiltin(name);
                resolved = true;
            }
            value = builtin != null ? builtin : ctx.readUserConstant(name);
        } else {
            value = ctx.readConstant(name);
        }
        if (value == null) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("Undefined constant \"" + (nsName != null ? nsName : name) + "\"", this);
        }
        return value;
    }
}
