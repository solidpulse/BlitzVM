package dev.phpruntime.js.nodes;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import dev.phpruntime.js.runtime.JsError;
import dev.phpruntime.js.runtime.JsOps;

/**
 * Variable access. Locals of the running function (or module) live in its frame;
 * functions reach top-level bindings through the module frame passed as argument 0.
 * Numbers are stored unboxed in int or double slots, so arithmetic in loops does not
 * allocate.
 */
public final class JsVariables {

    private JsVariables() {
    }

    /**
     * Shared by every node that reads or writes one variable (and by parameter binding).
     * Until the variable first holds a double, ints are stored as ints. After that, every
     * int written to it is stored as a double, so a variable like {@code let zr = 0.0;
     * ... zr = t;} keeps one representation instead of changing tag on every iteration.
     * The decision is an Assumption, so all compiled code that relied on it is thrown
     * away together.
     */
    public static final class SlotKind {
        private final Assumption noDoubles = Truffle.getRuntime().createAssumption("variable never held a double");

        public boolean holdsDoubles() {
            return !noDoubles.isValid();
        }

        public void sawDouble() {
            if (noDoubles.isValid()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                noDoubles.invalidate();
            }
        }
    }

    /** Something that can be assigned to. */
    public abstract static class Target extends JsNode {
        public abstract void write(VirtualFrame frame, Object value);

        public void writeInt(VirtualFrame frame, int value) {
            write(frame, value);
        }

        public void writeDouble(VirtualFrame frame, double value) {
            write(frame, value);
        }

        public abstract Object read(VirtualFrame frame);

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            return read(frame);
        }
    }

    /** Reads and writes one slot of a frame with int/double/object tags. */
    abstract static class SlotTarget extends Target {
        protected final int slot;
        protected final String name;
        protected final SlotKind kind;

        SlotTarget(int slot, String name, SlotKind kind) {
            this.slot = slot;
            this.name = name;
            this.kind = kind;
        }

        protected abstract Frame frameOf(VirtualFrame frame);

        @Override
        public Object read(VirtualFrame frame) {
            Frame f = frameOf(frame);
            if (f.isInt(slot)) {
                return f.getInt(slot);
            }
            if (f.isDouble(slot)) {
                return f.getDouble(slot);
            }
            Object v = f.getObject(slot);
            if (v == null) {
                CompilerDirectives.transferToInterpreter();
                throw JsError.reference(name, this);
            }
            return v;
        }

        @Override
        public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
            Frame f = frameOf(frame);
            if (f.isInt(slot)) {
                return f.getInt(slot);
            }
            throw new UnexpectedResultException(read(frame));
        }

        @Override
        public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
            Frame f = frameOf(frame);
            if (f.isDouble(slot)) {
                return f.getDouble(slot);
            }
            if (f.isInt(slot)) {
                return f.getInt(slot);
            }
            throw new UnexpectedResultException(read(frame));
        }

        @Override
        public void write(VirtualFrame frame, Object value) {
            if (value instanceof Integer i) {
                writeInt(frame, i);
            } else if (value instanceof Double d) {
                writeDouble(frame, d);
            } else {
                frameOf(frame).setObject(slot, value);
            }
        }

        @Override
        public void writeInt(VirtualFrame frame, int value) {
            if (kind.holdsDoubles()) {
                frameOf(frame).setDouble(slot, value);
            } else {
                frameOf(frame).setInt(slot, value);
            }
        }

        @Override
        public void writeDouble(VirtualFrame frame, double value) {
            kind.sawDouble();
            frameOf(frame).setDouble(slot, value);
        }
    }

    public static final class Local extends SlotTarget {
        public Local(int slot, String name, SlotKind kind) {
            super(slot, name, kind);
        }

        @Override
        protected Frame frameOf(VirtualFrame frame) {
            return frame;
        }
    }

    public static final class Module extends SlotTarget {
        public Module(int slot, String name, SlotKind kind) {
            super(slot, name, kind);
        }

        @Override
        protected Frame frameOf(VirtualFrame frame) {
            return (MaterializedFrame) frame.getArguments()[0];
        }
    }

    /** {@code obj.name} as an assignment target. The object is evaluated once per read or write. */
    public static final class Property extends Target {
        @Child private JsNode object;
        private final String name;

        public Property(JsNode object, String name) {
            this.object = object;
            this.name = name;
        }

        @Override
        public Object read(VirtualFrame frame) {
            return JsOps.getProperty(object.executeGeneric(frame), name, this);
        }

        @Override
        public void write(VirtualFrame frame, Object value) {
            JsOps.setProperty(object.executeGeneric(frame), name, value, this);
        }
    }

    /**
     * {@code target = value}; also used for declarations and compound assignment.
     * Remembers what the value has been and moves it without boxing. The kinds only
     * widen: INT becomes DOUBLE the first time a double shows up (from then on ints are
     * stored as doubles), and only a non-number makes it OBJECT. A variable that holds
     * both ints and doubles, like the result of {@code a * t / 8}, is common in JS
     * and must not end up boxed.
     */
    public static final class Assign extends JsNode {
        private static final int UNKNOWN = 0;
        private static final int INT = 1;
        private static final int DOUBLE = 2;
        private static final int OBJECT = 3;

        @Child private Target target;
        @Child private JsNode value;
        @CompilationFinal private int kind = UNKNOWN;

        public Assign(Target target, JsNode value) {
            this.target = target;
            this.value = value;
        }

        @Override
        public void executeVoid(VirtualFrame frame) {
            switch (kind) {
                case INT -> {
                    try {
                        target.writeInt(frame, value.executeInt(frame));
                    } catch (UnexpectedResultException e) {
                        widen(frame, e.getResult());
                    }
                }
                case DOUBLE -> {
                    try {
                        target.writeDouble(frame, value.executeDouble(frame));
                    } catch (UnexpectedResultException e) {
                        widen(frame, e.getResult());
                    }
                }
                case OBJECT -> target.write(frame, value.executeGeneric(frame));
                default -> executeGeneric(frame);
            }
        }

        private void widen(VirtualFrame frame, Object v) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            kind = join(kind, v);
            store(frame, v);
        }

        private static int join(int kind, Object v) {
            if (kind == OBJECT || !(v instanceof Integer || v instanceof Double)) {
                return OBJECT;
            }
            if (v instanceof Double || kind == DOUBLE) {
                return DOUBLE;
            }
            return INT;
        }

        private void store(VirtualFrame frame, Object v) {
            if (kind == DOUBLE && v instanceof Integer i) {
                target.writeDouble(frame, i);
            } else {
                target.write(frame, v);
            }
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = value.executeGeneric(frame);
            int k = join(kind, v);
            if (k != kind) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                kind = k;
            }
            store(frame, v);
            return v;
        }
    }

    /** {@code x++} / {@code x--}: as a value it is the old number; as a statement it is just the assignment. */
    public static final class Postfix extends JsNode {
        @Child private JsNode oldValue;
        @Child private Assign assign;

        public Postfix(JsNode oldValue, Assign assign) {
            this.oldValue = oldValue;
            this.assign = assign;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object old = JsOps.toNumber(oldValue.executeGeneric(frame));
            assign.executeVoid(frame);
            return old;
        }

        @Override
        public void executeVoid(VirtualFrame frame) {
            assign.executeVoid(frame);
        }
    }
}
