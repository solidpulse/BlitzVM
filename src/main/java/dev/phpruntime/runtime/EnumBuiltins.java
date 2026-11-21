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

        EnumMethodRootNode(PhpLanguage language, PhpClass enumClass, String name) {
            super(language);
            this.enumClass = enumClass;
            this.name = name;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] raw = frame.getArguments();
            Object[] args = raw.length > 2 ? java.util.Arrays.copyOfRange(raw, 2, raw.length) : new Object[0];
            return invoke(PhpContext.get(this), args);
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
            for (PhpClassTemplate.EnumCaseDecl decl : enumClass.template().enumCases()) {
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
                if (value instanceof PhpObject obj) {
                    if (!expectsInt && obj.phpClass().toStringMethod() != null) {
                        return PhpOps.toStr(obj);
                    }
                    throw typeError(fn, expected, value);
                }
                throw typeError(fn, expected, value);
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
