package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpException;
import dev.phpruntime.runtime.PhpObject;

/** {@code throw expr} (usable both as a statement and as a PHP 8 expression). */
public final class ThrowNode extends PhpNode {

    @Child private PhpNode expr;

    public ThrowNode(PhpNode expr) {
        this.expr = expr;
    }

    /** The validation and throw itself, shared with the bytecode {@code Throw} operation. */
    public static Object throwValue(Object val, Node location) {
        if (!(val instanceof PhpObject obj)) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("Can only throw objects", location);
        }
        if (!obj.phpClass().isThrowable()) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("Cannot throw objects that do not implement Throwable", location);
        }
        throw new PhpException(obj, location);
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginThrow();
        e.expr(expr);
        e.builder().endThrow();
    }
}
