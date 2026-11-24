package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;

/**
 * The methods every enum gets from its backing interfaces: {@code cases()} on a unit enum and
 * {@code from()} / {@code tryFrom()} on a backed enum. They are installed as ordinary method
 * entries when the enum class is linked, so static calls, first-class callables and
 * {@code is_callable} all see them.
 */
public final class EnumBuiltins {

    private EnumBuiltins() {
    }

    /** The call target of one of the three enum methods for a particular enum class. */
    public static RootCallTarget callTarget(PhpLanguage language, PhpClass enumClass, String name) {
        return new EnumMethodRootNode(language, enumClass, name).getCallTarget();
    }

    /** A built-in method root: arguments are {@code this}, the called class, then the call's own. */
    private static final class EnumMethodRootNode extends RootNode {
        private final PhpClass enumClass;
        private final String name;
        /**
         * from()/tryFrom(): backing value to case name, built on first use when every case value
         * is a literal (the same in every request). {@link #NO_INDEX} when some case value is an
         * expression; those enums scan their cases.
         */
        private volatile java.util.Map<Object, String> backingIndex;
        private static final java.util.Map<Object, String> NO_INDEX = java.util.Map.of();

        private final boolean intBacked;
        private final boolean stringBacked;

        EnumMethodRootNode(PhpLanguage language, PhpClass enumClass, String name) {
            super(language);
            this.enumClass = enumClass;
            this.name = name;
            PhpType backing = enumClass.template().enumBackingType();
            this.intBacked = backing instanceof PhpType.Named named && "int".equalsIgnoreCase(named.name());
            this.stringBacked = backing != null && !intBacked;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] raw = frame.getArguments();
            PhpContext ctx = PhpContext.get(this);
            // The common calls: a backing value of the enum's own type, one argument.
            if (raw.length == 3 && (name.equals("tryFrom") || name.equals("from"))) {
                Object arg = raw[2];
                if (stringBacked && arg instanceof String s) {
                    Object found = caseFor(ctx, s);
                    if (found == null && name.equals("tryFrom")) {
                        return PhpNull.INSTANCE;
                    }
                    if (found != null && found != SCAN) {
                        return found;
                    }
                } else if (intBacked && arg instanceof Long l) {
                    Object found = caseFor(ctx, l);
                    if (found == null && name.equals("tryFrom")) {
                        return PhpNull.INSTANCE;
                    }
                    if (found != null && found != SCAN) {
                        return found;
                    }
                }
            }
            Object[] args = raw.length > 2 ? java.util.Arrays.copyOfRange(raw, 2, raw.length) : new Object[0];
            return invoke(ctx, args);
        }

        /** Marks an enum whose case values are expressions and need the scanning path. */
        private static final Object SCAN = new Object();

        /** The case object for a backing value from the literal-case index, or null. */
        private Object caseFor(PhpContext ctx, Object value) {
            java.util.Map<Object, String> index = backingIndex;
            if (index == null) {
                index = backingIndex();
            }
            if (index == NO_INDEX) {
                return SCAN;
            }
            String caseName = index.get(value);
            if (caseName == null) {
                return null;
            }
            int position = enumClass.template().enumCaseIndex(caseName);
            return position < 0 ? null : enumClass.enumCaseAt(ctx, position);
        }

        @TruffleBoundary
        private Object invoke(PhpContext ctx, Object[] args) {
            return switch (name) {
                case "cases" -> cases(ctx, args);
                case "from" -> from(ctx, args, false);
                case "tryFrom" -> from(ctx, args, true);
                default -> throw new IllegalStateException(name);
            };
        }

        private Object cases(PhpContext ctx, Object[] args) {
            if (args.length != 0) {
                throw new PhpError(PhpError.ARGUMENT_COUNT, enumClass.name()
                        + "::cases() expects exactly 0 arguments, " + args.length + " given", this);
            }
            PhpArray out = new PhpArray();
            for (PhpClassTemplate.EnumCaseDecl decl : enumClass.template().enumCases()) {
                Object value = enumClass.enumCaseValue(ctx, decl.name());
                out.append(value == null ? PhpNull.INSTANCE : value);
            }
            return out;
        }

        private Object from(PhpContext ctx, Object[] args, boolean nullable) {
            String method = nullable ? "tryFrom" : "from";
            if (args.length != 1) {
                throw new PhpError(PhpError.ARGUMENT_COUNT, enumClass.name() + "::" + method
                        + "() expects exactly 1 argument, " + args.length + " given", this);
            }
            PhpType backingType = enumClass.template().enumBackingType();
            if (backingType == null) {
                throw new PhpError(PhpError.ERROR,
                        "Call to undefined method " + enumClass.name() + "::" + method + "()", this);
            }
            boolean expectsInt = isIntBacking(backingType);
            Object value = coerceArgument(ctx, args[0], expectsInt, method);
            java.util.Map<Object, String> index = backingIndex();
            if (index != NO_INDEX) {
                String caseName = index.get(value);
                if (caseName != null) {
                    Object caseObject = enumClass.enumCaseValue(ctx, caseName);
                    return caseObject == null ? PhpNull.INSTANCE : caseObject;
                }
            } else for (PhpClassTemplate.EnumCaseDecl decl : enumClass.template().enumCases()) {
                Object caseValue = caseBackingValue(ctx, enumClass, decl);
                if (sameBacking(value, caseValue)) {
                    Object caseObject = enumClass.enumCaseValue(ctx, decl.name());
                    return caseObject == null ? PhpNull.INSTANCE : caseObject;
                }
            }
            if (nullable) {
                return PhpNull.INSTANCE;
            }
            String rendered = expectsInt
                    ? Long.toString((Long) value)
                    : "\"" + value + "\"";
            throw new PhpError(PhpError.VALUE_ERROR,
                    rendered + " is not a valid backing value for enum " + enumClass.name(), this);
        }

        private java.util.Map<Object, String> backingIndex() {
            java.util.Map<Object, String> index = backingIndex;
            if (index == null) {
                java.util.HashMap<Object, String> built = new java.util.HashMap<>();
                for (PhpClassTemplate.EnumCaseDecl decl : enumClass.template().enumCases()) {
                    Object v = decl.constValue();
                    if (decl.valueTarget() != null || !(v instanceof Long || v instanceof String)) {
                        built = null;
                        break;
                    }
                    built.putIfAbsent(v, decl.name());
                }
                index = built == null ? NO_INDEX : built;
                backingIndex = index;
            }
            return index;
        }

        private boolean isIntBacking(PhpType type) {
            if (type instanceof PhpType.Named named) {
                return "int".equalsIgnoreCase(named.name());
            }
            return false;
        }

        private Object caseBackingValue(PhpContext ctx, PhpClass cls, PhpClassTemplate.EnumCaseDecl decl) {
            Object caseObject = cls.enumCaseValue(ctx, decl.name());
            if (caseObject instanceof PhpObject obj && obj.nativeState() instanceof EnumCaseState state) {
                return state.value();
            }
            return decl.constValue();
        }

        private Object coerceArgument(PhpContext ctx, Object raw, boolean expectsInt, String method) {
            Object value = raw == null ? PhpNull.INSTANCE : raw;
            // The backing type itself passes the same way in strict and weak mode.
            if (expectsInt ? value instanceof Long : value instanceof String) {
                return value;
            }
            String fn = enumClass.name() + "::" + method;
            String expected = expectsInt ? "int" : "string";
            if (!CallMode.strictOfCaller(this)) {
                if (value instanceof String s) {
                    if (expectsInt) {
                        if (!PhpOps.isNumericString(s)) {
                            throw typeError(fn, expected, value);
                        }
                        Object number = PhpOps.parseNumericPrefix(s);
                        if (number instanceof Long l) {
                            return l;
                        }
                        double d = ((Double) number).doubleValue();
                        long l = (long) d;
                        if (l != d) {
                            ctx.deprecated("Implicit conversion from float-string \"" + s
                                    + "\" to int loses precision", 0);
                        }
                        return l;
                    }
                    return s;
                }
                if (value instanceof Double d) {
                    if (Double.isNaN(d) || Double.isInfinite(d)
                            || d < Long.MIN_VALUE || d > Long.MAX_VALUE) {
                        throw typeError(fn, expected, value);
                    }
                    long l = (long) d.doubleValue();
                    if (l != d) {
                        ctx.deprecated("Implicit conversion from float " + PhpOps.formatDouble(d, 14)
                                + " to int loses precision", 0);
                    }
                    return expectsInt ? (Object) l : Long.toString(l);
                }
                if (value instanceof Long l) {
                    return expectsInt ? l : Long.toString(l);
                }
                if (value instanceof Boolean b) {
                    long l = b ? 1L : 0L;
                    return expectsInt ? (Object) l : Long.toString(l);
                }
                if (value == PhpNull.INSTANCE) {
                    ctx.deprecated(fn + "(): Passing null to parameter #1 ($value) of type string|int is deprecated", 0);
                    return expectsInt ? (Object) 0L : "0";
                }
                if (value instanceof PhpObject obj && !expectsInt && obj.phpClass().toStringMethod() != null) {
                    return PhpOps.toStr(obj);
                }
                // php reports the declared int|string for a string-backed enum in weak mode.
                throw typeError(fn, expectsInt ? "int" : "string|int", value);
            }
            if (expectsInt) {
                if (value instanceof Long l) {
                    return l;
                }
                throw typeError(fn, expected, value);
            }
            if (value instanceof String s) {
                return s;
            }
            throw typeError(fn, expected, value);
        }

        private PhpError typeError(String fn, String expected, Object value) {
            return new PhpError(PhpError.TYPE_ERROR, fn + "(): Argument #1 ($value) must be of type "
                    + expected + ", " + givenType(value) + " given", this);
        }

        private boolean sameBacking(Object a, Object b) {
            if (a instanceof Long x && b instanceof Long y) {
                return x.longValue() == y.longValue();
            }
            return a != null && a.equals(b);
        }

        private String givenType(Object value) {
            if (value == Boolean.TRUE) {
                return "true";
            }
            if (value == Boolean.FALSE) {
                return "false";
            }
            if (value == null || value == PhpNull.INSTANCE) {
                return "null";
            }
            return PhpOps.typeDescription(value);
        }

        @Override
        public String getName() {
            return enumClass.name() + "::" + name;
        }

        @Override
        public boolean isInternal() {
            return true;
        }
    }
}
