package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
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

    @Override
    public Object execute(VirtualFrame frame) {
        Object val = expr.execute(frame);
        if (!(val instanceof PhpObject obj)) {
            throw new PhpError("Can only throw objects", this);
        }
        if (!obj.phpClass().isThrowable()) {
            throw new PhpError("Cannot throw objects that do not implement Throwable", this);
        }
        throw new PhpException(obj, this);
    }
}
