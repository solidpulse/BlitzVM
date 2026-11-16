package dev.phpruntime.nodes;

import com.oracle.truffle.api.nodes.ControlFlowException;

/**
 * Non-local control flow. These exceptions are cheap: ControlFlowException has no
 * stack trace, and in compiled code Graal turns throw/catch pairs into plain jumps.
 */
public final class ControlFlow {

    private ControlFlow() {
    }

    public static final class Return extends ControlFlowException {
        public final Object value;

        public Return(Object value) {
            this.value = value;
        }
    }

    /** {@code break N}: unwinds N enclosing loops/switches. */
    public static final class Break extends ControlFlowException {
        public final int levels;

        public Break(int levels) {
            this.levels = levels;
        }
    }

    /** {@code continue N}. */
    public static final class Continue extends ControlFlowException {
        public final int levels;

        public Continue(int levels) {
            this.levels = levels;
        }
    }

    /** {@code exit} / {@code die}: ends the script after flushing output. */
    public static final class Exit extends ControlFlowException {
    }

    /** Suspends a generator without unwinding its PHP finally blocks. */
    public static final class Yield extends ControlFlowException {
        public final Object key;
        public final Object value;

        public Yield(Object key, Object value) {
            this.key = key;
            this.value = value;
        }
    }

    /** Explicit native generator close: runs finally, but cannot be caught by PHP. */
    public static final class GeneratorClose extends ControlFlowException {
    }

    /** {@code goto label;}: jumps to the matching label within the same function. */
    public static final class Goto extends ControlFlowException {
        public final int labelId;

        public Goto(int labelId) {
            this.labelId = labelId;
        }
    }
}
