package dev.phpruntime.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpStringBuffer;
import dev.phpruntime.runtime.PhpStrings;

/**
 * The common argument shapes of hot builtins, answered at the call site instead of through
 * {@link Builtins.Def#call} and the builtin boundary. One small class per builtin; every
 * handler returns {@link #UNHANDLED} for a shape whose processing needs a conversion with a
 * side effect, a diagnostic or an autoloader, so the builtin itself runs exactly as before.
 */
public abstract class FastBuiltin {

    /** Marks a call this fast path does not answer, so the builtin's own implementation runs. */
    public static final Object UNHANDLED = new Object();

    private final String name;
    private final Builtins.Def def;

    FastBuiltin(String name) {
        this.name = name;
        this.def = Builtins.lookup(name);
    }

    /** The builtin this fast path stands for; a resolved callee is compared to it by identity. */
    public final Builtins.Def def() {
        return def;
    }

    public final Object call(Node node, Object[] args) {
        return call(node, args, PhpContext.get(node));
    }

    public final Object call(Node node, Object[] args, PhpContext ctx) {
        if (PhpStrings.CHECK) {
            PhpStrings.checkArguments(args, name);
            Object value = callImplWith(node, args, ctx);
            return value == UNHANDLED ? UNHANDLED : PhpStrings.checkReturn(value, name);
        }
        return callImplWith(node, args, ctx);
    }

    abstract Object callImpl(Node node, Object[] args);

    /** {@link #callImpl} for a fast path that needs the request context. */
    Object callImplWith(Node node, Object[] args, PhpContext ctx) {
        return callImpl(node, args);
    }

    /** The fast path registered for a builtin name, or null. Called once per call site. */
    public static FastBuiltin lookup(String name) {
        return switch (PhpStrings.lower(name)) {
            case "str_replace" -> StrReplace.INSTANCE;
            case "str_starts_with" -> StrStartsWith.INSTANCE;
            case "str_ends_with" -> StrEndsWith.INSTANCE;
            case "str_contains" -> StrContains.INSTANCE;
            case "trim" -> Trim.TRIM;
            case "ltrim" -> Trim.LTRIM;
            case "rtrim" -> Trim.RTRIM;
            case "strpos" -> StrPos.INSTANCE;
            case "substr" -> Substr.INSTANCE;
            case "in_array" -> InArray.INSTANCE;
            case "strtolower" -> StrToLower.INSTANCE;
            case "explode" -> Explode.INSTANCE;
            case "defined" -> Defined.INSTANCE;
            case "extension_loaded" -> ExtensionLoaded.INSTANCE;
            case "is_numeric" -> IsNumeric.INSTANCE;
            case "array_key_last" -> ArrayKeyLast.INSTANCE;
            case "implode" -> Implode.INSTANCE;
            case "is_a" -> IsA.INSTANCE;
            case "class_exists" -> ClassExists.INSTANCE;
            case "interface_exists" -> InterfaceExists.INSTANCE;
            case "error_clear_last" -> ErrorClearLast.INSTANCE;
            case "preg_last_error" -> PregLastError.INSTANCE;
            case "error_reporting" -> ErrorReporting.INSTANCE;
            case "date_default_timezone_get" -> DefaultTimezoneGet.INSTANCE;
            case "time" -> Time.INSTANCE;
            case "current" -> Current.CURRENT;
            case "pos" -> Current.POS;
            case "key" -> ArrayKey.INSTANCE;
            case "reset" -> PointerMove.RESET;
            case "end" -> PointerMove.END;
            case "next" -> PointerMove.NEXT;
            case "prev" -> PointerMove.PREV;
            case "is_array" -> IsType.ARRAY;
            case "is_string" -> IsType.STRING;
            case "is_int" -> IsType.INT;
            case "is_integer" -> IsType.INTEGER;
            case "is_long" -> IsType.LONG;
            case "is_float" -> IsType.FLOAT;
            case "is_double" -> IsType.DOUBLE;
            case "is_bool" -> IsType.BOOL;
            case "is_null" -> IsType.NULL;
            case "is_object" -> IsType.OBJECT;
            case "is_scalar" -> IsType.SCALAR;
            default -> null;
        };
    }

    /** str_replace($search, $replacement, $subject) without the by-reference $count. */
    private static final class StrReplace extends FastBuiltin {
        static final StrReplace INSTANCE = new StrReplace();

        StrReplace() {
            super("str_replace");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if (args.length == 3 && args[0] instanceof String search && args[1] instanceof String replacement
                    && args[2] instanceof String subject) {
                return replace(search, replacement, subject);
            }
            return UNHANDLED;
        }

        @TruffleBoundary
        private static String replace(String search, String replacement, String subject) {
            // An empty search matches nothing in php; Java would insert the replacement
            // between every character.
            return search.isEmpty() ? subject : subject.replace(search, replacement);
        }
    }

    private static final class StrStartsWith extends FastBuiltin {
        static final StrStartsWith INSTANCE = new StrStartsWith();

        StrStartsWith() {
            super("str_starts_with");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if (args.length == 2 && args[0] instanceof String subject && args[1] instanceof String prefix) {
                return subject.startsWith(prefix);
            }
            return UNHANDLED;
        }
    }

    private static final class StrEndsWith extends FastBuiltin {
        static final StrEndsWith INSTANCE = new StrEndsWith();

        StrEndsWith() {
            super("str_ends_with");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if (args.length == 2 && args[0] instanceof String subject && args[1] instanceof String suffix) {
                return subject.endsWith(suffix);
            }
            return UNHANDLED;
        }
    }

    private static final class StrContains extends FastBuiltin {
        static final StrContains INSTANCE = new StrContains();

        StrContains() {
            super("str_contains");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if (args.length == 2 && args[0] instanceof String subject && args[1] instanceof String needle) {
                return subject.contains(needle);
            }
            return UNHANDLED;
        }
    }

    /** trim/ltrim/rtrim, with php's default character list or an explicit one. */
    private static final class Trim extends FastBuiltin {
        static final Trim TRIM = new Trim("trim", true, true);
        static final Trim LTRIM = new Trim("ltrim", true, false);
        static final Trim RTRIM = new Trim("rtrim", false, true);

        private final boolean left;
        private final boolean right;

        Trim(String name, boolean left, boolean right) {
            super(name);
            this.left = left;
            this.right = right;
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if (args.length == 1 && args[0] instanceof String subject) {
                return trimDefault(subject, left, right);
            }
            if (args.length == 2 && args[0] instanceof String subject && args[1] instanceof String chars) {
                return trimChars(subject, chars, left, right);
            }
            return UNHANDLED;
        }

        @TruffleBoundary
        static String trimDefault(String subject, boolean left, boolean right) {
            int begin = 0;
            int end = subject.length();
            while (left && begin < end && isDefaultTrimChar(subject.charAt(begin))) {
                begin++;
            }
            while (right && end > begin && isDefaultTrimChar(subject.charAt(end - 1))) {
                end--;
            }
            return begin == 0 && end == subject.length() ? subject : subject.substring(begin, end);
        }

        static boolean isDefaultTrimChar(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\0' || c == '\u000B';
        }

        @TruffleBoundary
        static String trimChars(String subject, String chars, boolean left, boolean right) {
            return StringFunctions.trim(subject, chars, left, right);
        }
    }

    /** strpos($haystack, $needle) or with a non-negative offset. */
    private static final class StrPos extends FastBuiltin {
        static final StrPos INSTANCE = new StrPos();

        StrPos() {
            super("strpos");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if (args.length == 2 && args[0] instanceof String haystack && args[1] instanceof String needle) {
                return position(haystack, needle, 0);
            }
            if (args.length == 3 && args[0] instanceof String haystack && args[1] instanceof String needle
                    && args[2] instanceof Long offset) {
                if (offset < 0 || offset > haystack.length()) {
                    // A negative offset counts from the end and an out-of-range one is an error.
                    return UNHANDLED;
                }
                return position(haystack, needle, offset.intValue());
            }
            return UNHANDLED;
        }

        @TruffleBoundary
        static Object position(String haystack, String needle, int offset) {
            int at = haystack.indexOf(needle, offset);
            return at < 0 ? Boolean.FALSE : (Object) (long) at;
        }
    }

    private static final class Substr extends FastBuiltin {
        static final Substr INSTANCE = new Substr();

        Substr() {
            super("substr");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if (args.length == 2 && args[0] instanceof String subject && args[1] instanceof Long start) {
                return substr(subject, start, null);
            }
            if (args.length == 3 && args[0] instanceof String subject && args[1] instanceof Long start
                    && args[2] instanceof Long length) {
                return substr(subject, start, length);
            }
            return UNHANDLED;
        }

        @TruffleBoundary
        static String substr(String subject, long start, Long length) {
            return StringFunctions.substr(subject, start, length);
        }
    }

    /**
     * in_array with a string or int needle and an array whose values are scalars, strict or
     * loose. Object or array elements would compare through user code, so they go to the
     * builtin.
     */
    private static final class InArray extends FastBuiltin {
        static final InArray INSTANCE = new InArray();

        InArray() {
            super("in_array");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if ((args.length == 2 || args.length == 3) && args[1] instanceof PhpArray haystack
                    && (args[0] instanceof String || args[0] instanceof Long)
                    && (args.length == 2 || args[2] instanceof Boolean)) {
                return contains(args[0], haystack, args.length == 3 && (Boolean) args[2]);
            }
            return UNHANDLED;
        }

        @TruffleBoundary
        static Object contains(Object needle, PhpArray haystack, boolean strict) {
            for (int pos = haystack.firstPos(); pos >= 0; pos = haystack.nextPos(pos)) {
                Object value = haystack.rawValueAt(pos);
                if (value instanceof PhpReference ref) {
                    value = ref.getValue();
                }
                if (value == null || (!strict && value instanceof PhpObject)) {
                    // An object compared with a string or int can run __toString; let the
                    // builtin do it.
                    return UNHANDLED;
                }
                if (strict ? PhpOps.strictEquals(needle, value) : PhpOps.looseEquals(needle, value)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        }
    }

    private static final class StrToLower extends FastBuiltin {
        static final StrToLower INSTANCE = new StrToLower();

        StrToLower() {
            super("strtolower");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if (args.length == 1 && args[0] instanceof String subject) {
                return PhpStrings.lower(subject);
            }
            return UNHANDLED;
        }
    }

    private static final class Explode extends FastBuiltin {
        static final Explode INSTANCE = new Explode();

        Explode() {
            super("explode");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if ((args.length == 2 || args.length == 3) && args[0] instanceof String separator
                    && args[1] instanceof String subject && (args.length == 2 || args[2] instanceof Long)) {
                if (separator.isEmpty()) {
                    // ValueError: the separator must not be empty.
                    return UNHANDLED;
                }
                long limit = args.length == 3 ? (Long) args[2] : Long.MAX_VALUE;
                return explode(separator, subject, limit);
            }
            return UNHANDLED;
        }

        @TruffleBoundary
        static PhpArray explode(String separator, String subject, long limit) {
            return StringFunctions.explode(separator, subject, limit);
        }
    }

    private static final class Defined extends FastBuiltin {
        static final Defined INSTANCE = new Defined();

        Defined() {
            super("defined");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            return callImplWith(node, args, PhpContext.get(node));
        }

        @Override
        Object callImplWith(Node node, Object[] args, PhpContext ctx) {
            if (args.length == 1 && args[0] instanceof String name) {
                return hasConstant(ctx, name);
            }
            return UNHANDLED;
        }

        @TruffleBoundary
        static boolean hasConstant(PhpContext ctx, String name) {
            return ctx.hasConstant(name);
        }
    }

    private static final class ExtensionLoaded extends FastBuiltin {
        static final ExtensionLoaded INSTANCE = new ExtensionLoaded();

        ExtensionLoaded() {
            super("extension_loaded");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if (args.length == 1 && args[0] instanceof String name) {
                return EnvFunctions.extensionLoaded(name);
            }
            return UNHANDLED;
        }
    }

    private static final class IsNumeric extends FastBuiltin {
        static final IsNumeric INSTANCE = new IsNumeric();

        IsNumeric() {
            super("is_numeric");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if (args.length == 1) {
                Object value = args[0];
                if (value instanceof Long || value instanceof Double) {
                    return Boolean.TRUE;
                }
                if (value instanceof String s) {
                    return PhpOps.isNumericString(s);
                }
                if (value instanceof PhpStringBuffer) {
                    // A buffer is a string under construction; let the builtin materialize it.
                    return UNHANDLED;
                }
                return Boolean.FALSE;
            }
            return UNHANDLED;
        }
    }

    private static final class ArrayKeyLast extends FastBuiltin {
        static final ArrayKeyLast INSTANCE = new ArrayKeyLast();

        ArrayKeyLast() {
            super("array_key_last");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if (args.length == 1 && args[0] instanceof PhpArray array) {
                int pos = array.lastPos();
                return pos < 0 ? PhpNull.INSTANCE : array.keyAt(pos);
            }
            return UNHANDLED;
        }
    }

    /** implode of an array whose values are all strings; any other value goes to the builtin. */
    private static final class Implode extends FastBuiltin {
        static final Implode INSTANCE = new Implode();

        Implode() {
            super("implode");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if (args.length == 1 && args[0] instanceof PhpArray array) {
                return join("", array);
            }
            if (args.length == 2 && args[0] instanceof String glue && args[1] instanceof PhpArray array) {
                return join(glue, array);
            }
            return UNHANDLED;
        }

        @TruffleBoundary
        static Object join(String glue, PhpArray array) {
            StringBuilder out = new StringBuilder();
            boolean first = true;
            for (int pos = array.firstPos(); pos >= 0; pos = array.nextPos(pos)) {
                Object value = array.rawValueAt(pos);
                if (value instanceof PhpReference ref) {
                    value = ref.getValue();
                }
                if (!(value instanceof String text)) {
                    return UNHANDLED;
                }
                if (!first) {
                    out.append(glue);
                }
                first = false;
                out.append(text);
            }
            return out.toString();
        }
    }

    /**
     * is_a with an object subject, or a string subject with $allow_string, when the named
     * classes are already loaded. A class that is not loaded goes to the builtin, which
     * autoloads the names it autoloads.
     */
    private static final class IsA extends FastBuiltin {
        static final IsA INSTANCE = new IsA();

        IsA() {
            super("is_a");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            return callImplWith(node, args, PhpContext.get(node));
        }

        @Override
        Object callImplWith(Node node, Object[] args, PhpContext ctx) {
            if (args.length < 2 || args.length > 3 || !(args[1] instanceof String targetName)
                    || (args.length == 3 && !(args[2] instanceof Boolean))) {
                return UNHANDLED;
            }
            if (args[0] instanceof PhpObject subject) {
                PhpClass target = ctx.lookupClass(targetName);
                return target != null && subject.phpClass().isInstanceOf(target);
            }
            if (args[0] instanceof String subjectName && args.length == 3 && (Boolean) args[2]) {
                PhpClass subject = ctx.lookupClass(subjectName);
                if (subject == null) {
                    return UNHANDLED;
                }
                PhpClass target = ctx.lookupClass(targetName);
                return target != null && subject.isInstanceOf(target);
            }
            if (args[0] instanceof String && args.length == 2) {
                // A string subject without $allow_string is deprecated and always false; the
                // builtin's wording and error level are kept. node is the call site, so the
                // diagnostic names the calling line as the builtin's does.
                ctx.deprecated("Calling is_a() with a string when $allow_string is false",
                        dev.phpruntime.runtime.PhpBacktrace.callSiteLine(node));
                return Boolean.FALSE;
            }
            return UNHANDLED;
        }
    }

    private static final class ClassExists extends FastBuiltin {
        static final ClassExists INSTANCE = new ClassExists();

        ClassExists() {
            super("class_exists");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            return callImplWith(node, args, PhpContext.get(node));
        }

        @Override
        Object callImplWith(Node node, Object[] args, PhpContext ctx) {
            if ((args.length == 1 || (args.length == 2 && args[1] instanceof Boolean))
                    && args[0] instanceof String name) {
                PhpClass cls = ctx.lookupClass(name);
                if (cls != null) {
                    return !cls.isInterface() && !cls.isTrait();
                }
                if (args.length == 2 && !(Boolean) args[1]) {
                    return Boolean.FALSE;
                }
                // A miss with autoload on goes to the builtin, which runs the autoloaders.
            }
            return UNHANDLED;
        }
    }

    /** error_clear_last: the runtime keeps no last error to clear, as the builtin already does. */
    private static final class ErrorClearLast extends FastBuiltin {
        static final ErrorClearLast INSTANCE = new ErrorClearLast();

        ErrorClearLast() {
            super("error_clear_last");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            return args.length == 0 ? PhpNull.INSTANCE : UNHANDLED;
        }
    }

    /** preg_last_error: the request's last PCRE error code, without arguments. */
    private static final class PregLastError extends FastBuiltin {
        static final PregLastError INSTANCE = new PregLastError();

        PregLastError() {
            super("preg_last_error");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            return callImplWith(node, args, PhpContext.get(node));
        }

        @Override
        Object callImplWith(Node node, Object[] args, PhpContext ctx) {
            return args.length == 0 ? (long) ContextState.of(ctx).pregLastError : UNHANDLED;
        }
    }

    /** error_reporting() with no argument: the current level, read-only. */
    private static final class ErrorReporting extends FastBuiltin {
        static final ErrorReporting INSTANCE = new ErrorReporting();

        ErrorReporting() {
            super("error_reporting");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            return callImplWith(node, args, PhpContext.get(node));
        }

        @Override
        Object callImplWith(Node node, Object[] args, PhpContext ctx) {
            return args.length == 0 ? ctx.errorReporting() : UNHANDLED;
        }
    }

    /** date_default_timezone_get: the request's current default timezone name. */
    private static final class DefaultTimezoneGet extends FastBuiltin {
        static final DefaultTimezoneGet INSTANCE = new DefaultTimezoneGet();

        DefaultTimezoneGet() {
            super("date_default_timezone_get");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            return callImplWith(node, args, PhpContext.get(node));
        }

        @Override
        Object callImplWith(Node node, Object[] args, PhpContext ctx) {
            return args.length == 0 ? ContextState.of(ctx).timezoneName : UNHANDLED;
        }
    }

    /** time(): the current Unix time in seconds. */
    private static final class Time extends FastBuiltin {
        static final Time INSTANCE = new Time();

        Time() {
            super("time");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            return args.length == 0 ? System.currentTimeMillis() / 1000 : UNHANDLED;
        }
    }

    /** current/pos: the value at the array's internal pointer, or false past either end. */
    private static final class Current extends FastBuiltin {
        static final Current CURRENT = new Current("current");
        static final Current POS = new Current("pos");

        Current(String name) {
            super(name);
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if (args.length == 1 && args[0] instanceof PhpArray array) {
                Object value = array.pointerValue();
                return value == null ? Boolean.FALSE : value;
            }
            return UNHANDLED;
        }
    }

    /** key: the key at the array's internal pointer, or null past either end. */
    private static final class ArrayKey extends FastBuiltin {
        static final ArrayKey INSTANCE = new ArrayKey();

        ArrayKey() {
            super("key");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if (args.length == 1 && args[0] instanceof PhpArray array) {
                Object key = array.pointerKey();
                return key == null ? PhpNull.INSTANCE : key;
            }
            return UNHANDLED;
        }
    }

    /**
     * reset/end/next/prev: move the array's internal pointer. The argument is a reference
     * cell, and moving separates a shared array first, so the cell must receive the separated
     * array exactly as the builtin's writeback does.
     */
    private static final class PointerMove extends FastBuiltin {
        static final PointerMove RESET = new PointerMove("reset", 0);
        static final PointerMove END = new PointerMove("end", 1);
        static final PointerMove NEXT = new PointerMove("next", 2);
        static final PointerMove PREV = new PointerMove("prev", 3);

        private final int move;

        PointerMove(String name, int move) {
            super(name);
            this.move = move;
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if (args.length != 1 || !(args[0] instanceof PhpReference cell)
                    || !(cell.getValue() instanceof PhpArray array)) {
                return UNHANDLED;
            }
            PhpArray writable = array.forWrite();
            if (writable != array) {
                cell.setValue(writable);
            }
            switch (move) {
                case 0 -> writable.pointerReset();
                case 1 -> writable.pointerEnd();
                case 2 -> writable.pointerNext();
                default -> writable.pointerPrev();
            }
            Object value = writable.pointerValue();
            return value == null ? Boolean.FALSE : value;
        }
    }

    /** The is_* type tests: a pure type check that agrees with the builtin's implementation. */
    private static final class IsType extends FastBuiltin {
        static final IsType ARRAY = new IsType("is_array", 0);
        static final IsType STRING = new IsType("is_string", 1);
        static final IsType INT = new IsType("is_int", 2);
        static final IsType INTEGER = new IsType("is_integer", 2);
        static final IsType LONG = new IsType("is_long", 2);
        static final IsType FLOAT = new IsType("is_float", 3);
        static final IsType DOUBLE = new IsType("is_double", 3);
        static final IsType BOOL = new IsType("is_bool", 4);
        static final IsType NULL = new IsType("is_null", 5);
        static final IsType OBJECT = new IsType("is_object", 6);
        static final IsType SCALAR = new IsType("is_scalar", 7);

        private final int test;

        IsType(String name, int test) {
            super(name);
            this.test = test;
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            if (args.length != 1) {
                return UNHANDLED;
            }
            Object value = args[0];
            return switch (test) {
                case 0 -> value instanceof PhpArray;
                case 1 -> value instanceof String;
                case 2 -> value instanceof Long;
                case 3 -> value instanceof Double;
                case 4 -> value instanceof Boolean;
                case 5 -> value == PhpNull.INSTANCE;
                case 6 -> value instanceof PhpObject;
                default -> value instanceof Long || value instanceof Double
                        || value instanceof String || value instanceof Boolean;
            };
        }
    }

    private static final class InterfaceExists extends FastBuiltin {
        static final InterfaceExists INSTANCE = new InterfaceExists();

        InterfaceExists() {
            super("interface_exists");
        }

        @Override
        Object callImpl(Node node, Object[] args) {
            return callImplWith(node, args, PhpContext.get(node));
        }

        @Override
        Object callImplWith(Node node, Object[] args, PhpContext ctx) {
            if ((args.length == 1 || (args.length == 2 && args[1] instanceof Boolean))
                    && args[0] instanceof String name) {
                PhpClass cls = ctx.lookupClass(name);
                if (cls != null) {
                    return cls.isInterface();
                }
                if (args.length == 2 && !(Boolean) args[1]) {
                    return Boolean.FALSE;
                }
            }
            return UNHANDLED;
        }
    }
}
