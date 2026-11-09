package dev.phpruntime.js.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;

/** A JavaScript runtime error such as {@code TypeError: x is not a function}. */
public final class JsError extends AbstractTruffleException {

    public JsError(String message, Node location) {
        super(message, location);
    }

    public static JsError type(String message, Node location) {
        return new JsError("TypeError: " + message, location);
    }

    public static JsError reference(String name, Node location) {
        return new JsError("ReferenceError: " + name + " is not defined", location);
    }
}
