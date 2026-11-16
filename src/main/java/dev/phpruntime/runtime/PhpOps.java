package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.object.DynamicObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * PHP 8 type-juggling rules: conversions, loose/strict comparison and formatting.
 * Values are represented as long, double, boolean, String, PhpArray, PhpNull.
 */
public final class PhpOps {
    public static String typeDescription(Object value) {
        return value instanceof PhpObject obj ? obj.phpClass().name() : typeName(value);
    }

    private PhpOps() {
    }

    // ---------------------------------------------------------------- to bool

    @TruffleBoundary
    public static boolean toBool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Long l) {
            return l != 0;
        }
        if (v instanceof Double d) {
            return d != 0.0;
        }
        if (v instanceof String s) {
            return !(s.isEmpty() || s.equals("0"));
        }
        if (v instanceof PhpArray a) {
            return a.size() > 0;
        }
        return v != PhpNull.INSTANCE;
    }

    // ---------------------------------------------------------------- to number

    /** Converts to long or double, as PHP does for arithmetic operands. */
    @TruffleBoundary
    public static Object toNumber(Object v) {
        if (v instanceof Long || v instanceof Double) {
            return v;
        }
        if (v instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        if (v == PhpNull.INSTANCE) {
            return 0L;
        }
        if (v instanceof String s) {
            Object n = parseNumericPrefix(s);
            if (n == null) {
                throw new PhpError(PhpError.TYPE_ERROR, "Unsupported operand types: non-numeric string \"" + s + "\"", null);
            }
            return n;
        }
        if (v instanceof PhpArray) {
            throw new PhpError(PhpError.TYPE_ERROR, "Unsupported operand types: array", null);
        }
        throw new PhpError(PhpError.TYPE_ERROR, "Unsupported operand type " + typeName(v), null);
    }

    @TruffleBoundary
    public static long toLong(Object v) {
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof Double d) {
            return doubleToLong(d);
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof String s) {
            Object n = parseNumericPrefix(s);
            if (n == null) {
                return 0;
            }
            return n instanceof Long l ? l : doubleToLong((Double) n);
        }
        if (v instanceof PhpArray a) {
            return a.size() > 0 ? 1 : 0;
        }
        if (v instanceof PhpResource r) {
            return r.id();
        }
        return 0;
    }

    public static long doubleToLong(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return 0;
        }
        return (long) d;
    }

    @TruffleBoundary
    public static double toDouble(Object v) {
        if (v instanceof Double d) {
            return d;
        }
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof String s) {
            Object n = parseNumericPrefix(s);
            if (n == null) {
                return 0;
            }
            return n instanceof Long l ? l : (Double) n;
        }
        return toLong(v);
    }

    /**
     * Parses the leading numeric part of a string ("12abc" gives 12, " 3.5" gives 3.5).
     * Returns null when the string does not start with a number.
     */
    @TruffleBoundary
    public static Object parseNumericPrefix(String s) {
        int i = 0;
        int n = s.length();
        while (i < n && isWs(s.charAt(i))) {
            i++;
        }
        int start = i;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            i++;
        }
        int digitsStart = i;
        while (i < n && Character.isDigit(s.charAt(i))) {
            i++;
        }
        boolean isFloat = false;
        if (i < n && s.charAt(i) == '.') {
            int j = i + 1;
            while (j < n && Character.isDigit(s.charAt(j))) {
                j++;
            }
            if (j > i + 1 || i > digitsStart) {
                isFloat = true;
                i = j;
            }
        }
        if (i == digitsStart || (i == digitsStart + 1 && s.charAt(digitsStart) == '.')) {
            return null;
        }
        if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            int j = i + 1;
            if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) {
                j++;
            }
            int expDigits = j;
            while (j < n && Character.isDigit(s.charAt(j))) {
                j++;
            }
            if (j > expDigits) {
                isFloat = true;
                i = j;
            }
        }
        String num = s.substring(start, i);
        if (!isFloat) {
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                return Double.parseDouble(num); // integer overflow becomes float
            }
        }
        return Double.parseDouble(num);
    }

    /** True if the whole string is numeric (leading/trailing whitespace allowed). */
    @TruffleBoundary
    public static boolean isNumericString(String s) {
        Object n = parseNumericPrefix(s);
        if (n == null) {
            return false;
        }
        String t = s.strip();
        // Re-parse the trimmed string and require that the prefix covers it fully.
        int i = 0;
        if (i < t.length() && (t.charAt(i) == '+' || t.charAt(i) == '-')) {
            i++;
        }
        boolean seenDigit = false;
        boolean seenDot = false;
        boolean seenExp = false;
        for (; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isDigit(c)) {
                seenDigit = true;
            } else if (c == '.' && !seenDot && !seenExp) {
                seenDot = true;
            } else if ((c == 'e' || c == 'E') && seenDigit && !seenExp) {
                seenExp = true;
                if (i + 1 < t.length() && (t.charAt(i + 1) == '+' || t.charAt(i + 1) == '-')) {
                    i++;
                }
                if (i + 1 >= t.length()) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return seenDigit;
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\f';
    }

    // ---------------------------------------------------------------- to string

    @TruffleBoundary
    public static String toStr(Object v) {
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Long l) {
            return Long.toString(l);
        }
        if (v instanceof Double d) {
            return formatDouble(d, 14);
        }
        if (v instanceof Boolean b) {
            return b ? "1" : "";
        }
        if (v == PhpNull.INSTANCE) {
            return "";
        }
        if (v instanceof PhpArray) {
            return "Array";
        }
        if (v instanceof PhpResource r) {
            return r.toString();
        }
        if (v instanceof PhpObject o) {
            PhpClass.MethodEntry ts = o.phpClass().toStringMethod();
            if (ts != null && ts.callTarget() != null) {
                Object res = ts.fromTrait()
                        ? ts.callTarget().call(o, o.phpClass(), ts.declaringClass())
                        : ts.callTarget().call(o, o.phpClass());
                if (!(res instanceof String s)) {
                    throw new PhpError(o.phpClass().name() + "::__toString() must return a string value");
                }
                return s;
            }
            throw new PhpError("Object of class " + o.phpClass().name() + " could not be converted to string");
        }
        return String.valueOf(v);
    }

    /**
     * Formats a double like PHP: {@code precision} 14 for echo/string conversion,
     * 17 (shortest round-trip, serialize_precision=-1) for var_dump/json_encode.
     */
    @TruffleBoundary
    public static String formatDouble(double d, int precision) {
        if (Double.isNaN(d)) {
            return "NAN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "INF" : "-INF";
        }
        if (d == 0) {
            return (1 / d < 0) ? "-0" : "0";
        }
        BigDecimal bd;
        if (precision >= 17) {
            bd = new BigDecimal(Double.toString(d)); // shortest repr that round-trips
        } else {
            bd = new BigDecimal(d).round(new MathContext(precision, RoundingMode.HALF_EVEN));
        }
        bd = bd.stripTrailingZeros();
        String digits = bd.unscaledValue().abs().toString();
        int exp = digits.length() - 1 - bd.scale();
        String sign = d < 0 ? "-" : "";
        // Same rule as zend_gcvt: exponent form when decpt < -3 or decpt > precision.
        if (exp < -4 || exp >= precision) {
            String mantissa = digits.length() == 1 ? digits + ".0" : digits.charAt(0) + "." + digits.substring(1);
            return sign + mantissa + "E" + (exp < 0 ? "-" : "+") + Math.abs(exp);
        }
        return sign + bd.abs().toPlainString();
    }

    // ---------------------------------------------------------------- comparison

    @TruffleBoundary
    public static boolean strictEquals(Object a, Object b) {
        if (a instanceof PhpArray x && b instanceof PhpArray y) {
            if (x.size() != y.size()) {
                return false;
            }
            List<Map.Entry<Object, Object>> ex = x.entries();
            List<Map.Entry<Object, Object>> ey = y.entries();
            for (int i = 0; i < ex.size(); i++) {
                if (!ex.get(i).getKey().equals(ey.get(i).getKey()) || !strictEquals(ex.get(i).getValue(), ey.get(i).getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Double x && b instanceof Double y) {
            return x.doubleValue() == y.doubleValue();
        }
        return a.getClass() == b.getClass() && a.equals(b);
    }

    @TruffleBoundary
    public static boolean looseEquals(Object a, Object b) {
        return looseEquals(a, b, null);
    }

    private static boolean looseEquals(Object a, Object b, Map<PhpObject, PhpObject> seen) {
        if (a instanceof PhpArray x && b instanceof PhpArray y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (Map.Entry<Object, Object> e : x.entries()) {
                if (!y.containsKey(e.getKey()) || !looseEquals(e.getValue(), y.get(e.getKey()), seen)) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof PhpObject x && b instanceof PhpObject y) {
            if (x == y) {
                return true;
            }
            if (x.nativeState() != null && x.nativeState().supportsCompare()
                    && y.nativeState() != null && y.nativeState().supportsCompare()) {
                return x.nativeState().compareTo(y) == 0;
            }
            if (x.phpClass() != y.phpClass()) {
                return false;
            }
            if (seen == null) {
                seen = new IdentityHashMap<>();
            } else if (seen.get(x) == y) {
                return true;
            }
            seen.put(x, y);
            Object[] xKeys = DynamicObject.GetKeyArrayNode.getUncached().execute(x);
            Object[] yKeys = DynamicObject.GetKeyArrayNode.getUncached().execute(y);
            if (xKeys.length != yKeys.length) {
                return false;
            }
            DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
            DynamicObject.ContainsKeyNode contains = DynamicObject.ContainsKeyNode.getUncached();
            for (Object k : xKeys) {
                if (!contains.execute(y, k)) {
                    return false;
                }
                if (!looseEquals(PhpClass.unwrapProp(get.execute(x, k, null)), PhpClass.unwrapProp(get.execute(y, k, null)), seen)) {
                    return false;
                }
            }
            return true;
        }
        return compare(a, b) == 0;
    }

    /** PHP 8 spaceship comparison. */
    @TruffleBoundary
    public static int compare(Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) {
            return Long.compare(x, y);
        }
        if ((a instanceof Long || a instanceof Double) && (b instanceof Long || b instanceof Double)) {
            return Double.compare(toDouble(a), toDouble(b)) == 0 ? 0 : (toDouble(a) < toDouble(b) ? -1 : 1);
        }
        if (a instanceof String x && b instanceof String y) {
            if (isNumericString(x) && isNumericString(y)) {
                return compare(toNumber(x), toNumber(y));
            }
            return Integer.signum(x.compareTo(y));
        }
        if (a instanceof Boolean || b instanceof Boolean || a == PhpNull.INSTANCE || b == PhpNull.INSTANCE) {
            if (a == PhpNull.INSTANCE && b instanceof String s) {
                return s.isEmpty() ? 0 : -1;
            }
            if (b == PhpNull.INSTANCE && a instanceof String s) {
                return s.isEmpty() ? 0 : 1;
            }
            return Boolean.compare(toBool(a), toBool(b));
        }
        if (a instanceof String s && (b instanceof Long || b instanceof Double)) {
            return isNumericString(s) ? compare(toNumber(s), b) : Integer.signum(s.compareTo(toStr(b)));
        }
        if (b instanceof String s && (a instanceof Long || a instanceof Double)) {
            return isNumericString(s) ? compare(a, toNumber(s)) : Integer.signum(toStr(a).compareTo(s));
        }
        if (a instanceof PhpArray x && b instanceof PhpArray y) {
            return Integer.compare(x.size(), y.size());
        }
        if (a instanceof PhpArray) {
            return 1;
        }
        if (b instanceof PhpArray) {
            return -1;
        }
        if (a instanceof PhpObject x && b instanceof PhpObject y) {
            if (x.nativeState() != null && x.nativeState().supportsCompare()
                    && y.nativeState() != null && y.nativeState().supportsCompare()) {
                return x.nativeState().compareTo(y);
            }
        }
        if (a instanceof PhpObject o) {
            if (b instanceof String s && o.phpClass().toStringMethod() != null) {
                return compare(toStr(o), s);
            }
            return 1;
        }
        if (b instanceof PhpObject o) {
            if (a instanceof String s && o.phpClass().toStringMethod() != null) {
                return compare(s, toStr(o));
            }
            return -1;
        }
        return 0;
    }

    // ---------------------------------------------------------------- misc

    @TruffleBoundary
    public static String typeName(Object v) {
        if (v instanceof Long) {
            return "int";
        }
        if (v instanceof Double) {
            return "float";
        }
        if (v instanceof Boolean) {
            return "bool";
        }
        if (v instanceof String) {
            return "string";
        }
        if (v instanceof PhpArray) {
            return "array";
        }
        if (v instanceof PhpObject) {
            return "object";
        }
        if (v instanceof PhpResource) {
            return "resource";
        }
        if (v == PhpNull.INSTANCE) {
            return "null";
        }
        return "unknown";
    }

    /** Value semantics on assignment: arrays are shared copy-on-write. */
    public static Object copyOnAssign(Object v) {
        if (v instanceof PhpArray a) {
            return a.share();
        }
        return v;
    }
}
