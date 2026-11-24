package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpStrings;

/** EmptyIterator: valid() is always false and the accessors are undefined methods. */
public final class SplEmptyMethodRootNode extends RootNode {
    private final String method;

    public SplEmptyMethodRootNode(PhpLanguage language, String method) {
        super(language);
        this.method = method;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (!PhpStrings.CHECK) { return executeMethod(frame); }
        if (PhpStrings.CHECK) {
            PhpStrings.checkArguments(frame.getArguments(), "EmptyIterator::" + method);
        }
        Object result = executeMethod(frame);
        return PhpStrings.CHECK ? PhpStrings.checkReturn(result, "EmptyIterator::" + method) : result;
    }

    private Object executeMethod(VirtualFrame frame) {
        Object[] raw = frame.getArguments();
        PhpObject self = (PhpObject) raw[0];
        int n = raw.length - 2;
        if (n != 0) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.ARGUMENT_COUNT, "EmptyIterator::" + method
                    + "() expects exactly 0 arguments, " + n + " given", this);
        }
        return switch (method) {
            case "valid" -> false;
            case "current" -> throw new PhpError("BadMethodCallException",
                    "Accessing the value of an EmptyIterator", this);
            case "key" -> throw new PhpError("BadMethodCallException",
                    "Accessing the key of an EmptyIterator", this);
            case "rewind", "next" -> PhpNull.INSTANCE;
            default -> throw new IllegalStateException("EmptyIterator::" + method);
        };
    }
}
