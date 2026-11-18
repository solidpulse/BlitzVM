package dev.phpruntime.nodes;

import com.oracle.truffle.api.nodes.ControlFlowException;

/**
 * Non-local control flow. These exceptions are cheap: ControlFlowException has no
 * stack trace, and in compiled code Graal turns throw/catch pairs into plain jumps.
 */
public final class ControlFlow {

    private ControlFlow() {
    }

    /** {@code exit} / {@code die}: ends the script after flushing output. */
    public static final class Exit extends ControlFlowException {
    }
}
