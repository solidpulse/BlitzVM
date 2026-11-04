package dev.phpruntime.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;

/** A PHP fatal error. Terminates the script and is reported to the embedder. */
public final class PhpError extends AbstractTruffleException {

    public PhpError(String message) {
        super(message);
    }

    public PhpError(String message, Node location) {
        super(message, location);
    }
}
