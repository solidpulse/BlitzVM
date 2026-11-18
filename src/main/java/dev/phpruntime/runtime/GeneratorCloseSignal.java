package dev.phpruntime.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;

/**
 * Closing a bytecode generator suspended at a yield: thrown at the yield so its finally blocks
 * run, as {@link dev.phpruntime.nodes.ControlFlow.GeneratorClose} does on the AST. It is a
 * Truffle exception because bytecode handlers only see those; no catch clause matches it.
 */
public final class GeneratorCloseSignal extends AbstractTruffleException {
    private static final long serialVersionUID = 1L;

    public GeneratorCloseSignal() {
        super("generator closed");
    }
}
