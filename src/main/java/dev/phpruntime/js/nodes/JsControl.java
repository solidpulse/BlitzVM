package dev.phpruntime.js.nodes;

import com.oracle.truffle.api.nodes.ControlFlowException;

/** Non-local control flow inside a function body. */
public final class JsControl {

    private JsControl() {
    }

    public static final class Return extends ControlFlowException {
        public final Object value;

        public Return(Object value) {
            this.value = value;
        }
    }

    public static final class Break extends ControlFlowException {
        public static final Break INSTANCE = new Break();
    }

    public static final class Continue extends ControlFlowException {
        public static final Continue INSTANCE = new Continue();
    }
}
