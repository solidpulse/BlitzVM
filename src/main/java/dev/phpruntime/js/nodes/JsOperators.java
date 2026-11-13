package dev.phpruntime.js.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import dev.phpruntime.js.runtime.JsOps;

/**
 * Operators. Integer specializations keep exact int arithmetic while every result is
 * an integer JavaScript would also produce; overflow, inexact division and -0 move to
 * the double specialization, which is JavaScript's own number semantics. The double
 * specialization replaces the int one, so a node that has seen a double always
 * produces doubles: one result type is what lets Graal keep the value unboxed.
 */
public final class JsOperators {

    private JsOperators() {
    }

    @NodeChild("left")
    @NodeChild("right")
    public abstract static class Binary extends JsNode {
    }

    @NodeChild("operand")
    public abstract static class Unary extends JsNode {
    }

    public abstract static class Add extends Binary {
        @Specialization(rewriteOn = ArithmeticException.class)
        protected int doInt(int a, int b) {
            return Math.addExact(a, b);
        }

        @Specialization(replaces = "doInt")
        protected double doDouble(double a, double b) {
            return a + b;
        }

        @Specialization
        @TruffleBoundary
        protected String doString(String a, String b) {
            return a.concat(b);
        }

        @Fallback
        protected Object doGeneric(Object a, Object b) {
            return JsOps.add(a, b);
        }
    }

    public abstract static class Sub extends Binary {
        @Specialization(rewriteOn = ArithmeticException.class)
        protected int doInt(int a, int b) {
            return Math.subtractExact(a, b);
        }

        @Specialization(replaces = "doInt")
        protected double doDouble(double a, double b) {
            return a - b;
        }

        @Fallback
        protected Object doGeneric(Object a, Object b) {
            return JsOps.normalize(JsOps.toDouble(a) - JsOps.toDouble(b));
        }
    }

    public abstract static class Mul extends Binary {
        @Specialization(guards = "!negativeZero(a, b)", rewriteOn = ArithmeticException.class)
        protected int doInt(int a, int b) {
            return Math.multiplyExact(a, b);
        }

        @Specialization(replaces = "doInt")
        protected double doDouble(double a, double b) {
            return a * b;
        }

        @Fallback
        protected Object doGeneric(Object a, Object b) {
            return JsOps.normalize(JsOps.toDouble(a) * JsOps.toDouble(b));
        }

        static boolean negativeZero(int a, int b) {
            return (a == 0 && b < 0) || (b == 0 && a < 0);
        }
    }

    public abstract static class Div extends Binary {
        @Specialization(guards = "isExact(a, b)")
        protected int doInt(int a, int b) {
            return a / b;
        }

        @Specialization(replaces = "doInt")
        protected double doDouble(double a, double b) {
            return a / b;
        }

        @Fallback
        protected Object doGeneric(Object a, Object b) {
            return JsOps.normalize(JsOps.toDouble(a) / JsOps.toDouble(b));
        }

        static boolean isExact(int a, int b) {
            return b != 0 && a % b == 0 && !(a == Integer.MIN_VALUE && b == -1) && !(a == 0 && b < 0);
        }
    }

    public abstract static class Mod extends Binary {
        @Specialization(guards = "isSafe(a, b)")
        protected int doInt(int a, int b) {
            return a % b;
        }

        @Specialization(replaces = "doInt")
        protected double doDouble(double a, double b) {
            return a % b;
        }

        @Fallback
        protected Object doGeneric(Object a, Object b) {
            return JsOps.normalize(JsOps.toDouble(a) % JsOps.toDouble(b));
        }

        /** Excludes division by zero (NaN), MIN % -1, and results that would be -0. */
        static boolean isSafe(int a, int b) {
            return b != 0 && !(a == Integer.MIN_VALUE && b == -1) && !(a < 0 && a % b == 0);
        }
    }

    public abstract static class Neg extends Unary {
        @Specialization(guards = "isSafe(a)")
        protected int doInt(int a) {
            return -a;
        }

        @Specialization(replaces = "doInt")
        protected double doDouble(double a) {
            return -a;
        }

        @Fallback
        protected Object doGeneric(Object a) {
            return JsOps.normalize(-JsOps.toDouble(a));
        }

        static boolean isSafe(int a) {
            return a != 0 && a != Integer.MIN_VALUE;
        }
    }

    public abstract static class LessThan extends Binary {
        @Specialization
        protected boolean doInt(int a, int b) {
            return a < b;
        }

        @Specialization
        protected boolean doDouble(double a, double b) {
            return a < b;
        }

        @Fallback
        protected boolean doGeneric(Object a, Object b) {
            return JsOps.lessThan(a, b, false);
        }
    }

    public abstract static class LessEqual extends Binary {
        @Specialization
        protected boolean doInt(int a, int b) {
            return a <= b;
        }

        @Specialization
        protected boolean doDouble(double a, double b) {
            return a <= b;
        }

        @Fallback
        protected boolean doGeneric(Object a, Object b) {
            return JsOps.lessThan(a, b, true);
        }
    }

    public abstract static class GreaterThan extends Binary {
        @Specialization
        protected boolean doInt(int a, int b) {
            return a > b;
        }

        @Specialization
        protected boolean doDouble(double a, double b) {
            return a > b;
        }

        @Fallback
        protected boolean doGeneric(Object a, Object b) {
            return JsOps.lessThan(b, a, false);
        }
    }

    public abstract static class GreaterEqual extends Binary {
        @Specialization
        protected boolean doInt(int a, int b) {
            return a >= b;
        }

        @Specialization
        protected boolean doDouble(double a, double b) {
            return a >= b;
        }

        @Fallback
        protected boolean doGeneric(Object a, Object b) {
            return JsOps.lessThan(b, a, true);
        }
    }

    /** Unary plus. */
    public static final class ToNumber extends JsNode {
        @Child private JsNode operand;

        public ToNumber(JsNode operand) {
            this.operand = operand;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            return JsOps.toNumber(operand.executeGeneric(frame));
        }
    }

    public abstract static class StrictEquals extends Binary {
        @Specialization
        protected boolean doInt(int a, int b) {
            return a == b;
        }

        @Specialization
        protected boolean doDouble(double a, double b) {
            return a == b;
        }

        @Specialization
        protected boolean doBoolean(boolean a, boolean b) {
            return a == b;
        }

        @Fallback
        protected boolean doGeneric(Object a, Object b) {
            return JsOps.strictEquals(a, b);
        }
    }

    public abstract static class LooseEquals extends Binary {
        @Specialization
        protected boolean doInt(int a, int b) {
            return a == b;
        }

        @Specialization
        protected boolean doDouble(double a, double b) {
            return a == b;
        }

        @Fallback
        protected boolean doGeneric(Object a, Object b) {
            return JsOps.looseEquals(a, b);
        }
    }

    /** {@code !x}, and the negated equality operators. */
    public static final class Not extends JsNode {
        @Child private JsNode operand;

        public Not(JsNode operand) {
            this.operand = operand;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            return !operand.executeCondition(frame);
        }
    }

    /** {@code a && b} and {@code a || b}, which return one of their operands. */
    public static final class Logical extends JsNode {
        @Child private JsNode left;
        @Child private JsNode right;
        private final boolean isAnd;
        private final CountingConditionProfile shortCircuit = CountingConditionProfile.create();

        public Logical(JsNode left, JsNode right, boolean isAnd) {
            this.left = left;
            this.right = right;
            this.isAnd = isAnd;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object l = left.executeGeneric(frame);
            boolean truthy = JsOps.toBoolean(l);
            if (shortCircuit.profile(isAnd != truthy)) {
                return l;
            }
            return right.executeGeneric(frame);
        }
    }

    public static final class Conditional extends JsNode {
        @Child private JsNode condition;
        @Child private JsNode whenTrue;
        @Child private JsNode whenFalse;
        private final CountingConditionProfile profile = CountingConditionProfile.create();
        private final ValueKind trueKind = new ValueKind();
        private final ValueKind falseKind = new ValueKind();

        public Conditional(JsNode condition, JsNode whenTrue, JsNode whenFalse) {
            this.condition = condition;
            this.whenTrue = whenTrue;
            this.whenFalse = whenFalse;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            if (profile.profile(condition.executeCondition(frame))) {
                return trueKind.execute(frame, whenTrue);
            }
            return falseKind.execute(frame, whenFalse);
        }

        @Override
        public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
            if (profile.profile(condition.executeCondition(frame))) {
                return whenTrue.executeInt(frame);
            }
            return whenFalse.executeInt(frame);
        }

        @Override
        public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
            if (profile.profile(condition.executeCondition(frame))) {
                return whenTrue.executeDouble(frame);
            }
            return whenFalse.executeDouble(frame);
        }
    }

    public static final class Constant extends JsNode {
        private final Object value;

        public Constant(Object value) {
            this.value = value;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            return value;
        }

        @Override
        public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
            if (value instanceof Integer i) {
                return i;
            }
            throw new UnexpectedResultException(value);
        }

        @Override
        public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
            if (value instanceof Double d) {
                return d;
            }
            if (value instanceof Integer i) {
                return i;
            }
            throw new UnexpectedResultException(value);
        }
    }
}
