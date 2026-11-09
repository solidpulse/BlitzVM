package dev.phpruntime.js.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.js.runtime.JsValues.JsBuiltin;
import dev.phpruntime.js.runtime.JsValues.JsFunction;
import dev.phpruntime.js.runtime.JsValues.JsObject;
import dev.phpruntime.js.runtime.JsValues.Null;
import dev.phpruntime.js.runtime.JsValues.Undefined;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** JavaScript conversions and generic (slow-path) operators. */
public final class JsOps {

    private JsOps() {
    }

    /** Integral doubles that fit an int travel as int; everything else stays double. */
    public static Object normalize(double d) {
        int i = (int) d;
        if (i == d && !(d == 0 && Double.doubleToRawLongBits(d) != 0)) {
            return i;
        }
        return d;
    }

    public static boolean isNumber(Object v) {
        return v instanceof Integer || v instanceof Double;
    }

    public static double toDouble(Object v) {
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof Double d) {
            return d;
        }
        return toDoubleSlow(v);
    }

    @TruffleBoundary
    private static double toDoubleSlow(Object v) {
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof String s) {
            return stringToNumber(s);
        }
        if (v == Null.INSTANCE) {
            return 0;
        }
        return Double.NaN;
    }

    public static Object toNumber(Object v) {
        if (isNumber(v)) {
            return v;
        }
        return normalize(toDoubleSlow(v));
    }

    @TruffleBoundary
    static double stringToNumber(String raw) {
        String s = raw.strip();
        if (s.isEmpty()) {
            return 0;
        }
        switch (s) {
            case "Infinity", "+Infinity":
                return Double.POSITIVE_INFINITY;
            case "-Infinity":
                return Double.NEGATIVE_INFINITY;
            default:
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9' || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-')) {
                return Double.NaN;
            }
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    public static boolean toBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Integer i) {
            return i != 0;
        }
        if (v instanceof Double d) {
            return d != 0 && !Double.isNaN(d);
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        return v != Undefined.INSTANCE && v != Null.INSTANCE;
    }

    @TruffleBoundary
    public static String toStr(Object v) {
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Integer i) {
            return Integer.toString(i);
        }
        if (v instanceof Double d) {
            return numberToString(d);
        }
        if (v instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (v instanceof JsObject) {
            return "[object Object]";
        }
        if (v instanceof JsFunction f) {
            return "function " + f.name + "() { [code] }";
        }
        if (v instanceof JsBuiltin b) {
            return "function " + b.name + "() { [native code] }";
        }
        return String.valueOf(v);
    }

    /** Number::toString from the ECMAScript spec, using Java's shortest round-trip digits. */
    @TruffleBoundary
    public static String numberToString(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        if (d == 0) {
            return "0";
        }
        String sign = d < 0 ? "-" : "";
        String java = Double.toString(Math.abs(d));
        String digits;
        int n; // value = 0.digits * 10^n
        int e = java.indexOf('E');
        if (e >= 0) {
            digits = java.substring(0, e).replace(".", "");
            n = Integer.parseInt(java.substring(e + 1)) + 1;
        } else {
            int dot = java.indexOf('.');
            String intPart = java.substring(0, dot);
            String frac = java.substring(dot + 1);
            if (!intPart.equals("0")) {
                digits = intPart + frac;
                n = intPart.length();
            } else {
                int z = 0;
                while (z < frac.length() && frac.charAt(z) == '0') {
                    z++;
                }
                digits = frac.substring(z);
                n = -z;
            }
        }
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') {
            end--;
        }
        digits = digits.substring(0, end);
        int k = digits.length();
        StringBuilder sb = new StringBuilder(sign);
        if (k <= n && n <= 21) {
            sb.append(digits).append("0".repeat(n - k));
        } else if (0 < n && n <= 21) {
            sb.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            sb.append("0.").append("0".repeat(-n)).append(digits);
        } else {
            int exp = n - 1;
            sb.append(digits.charAt(0));
            if (k > 1) {
                sb.append('.').append(digits, 1, k);
            }
            sb.append('e').append(exp < 0 ? '-' : '+').append(Math.abs(exp));
        }
        return sb.toString();
    }

    @TruffleBoundary
    public static String toFixed(double x, int fractionDigits) {
        if (Double.isNaN(x)) {
            return "NaN";
        }
        if (Math.abs(x) >= 1e21 || Double.isInfinite(x)) {
            return numberToString(x);
        }
        String s = new BigDecimal(x).setScale(fractionDigits, RoundingMode.HALF_UP).toPlainString();
        if (x < 0 && !s.startsWith("-")) {
            s = "-" + s;
        }
        return s;
    }

    @TruffleBoundary
    public static Object add(Object a, Object b) {
        if (a instanceof String || b instanceof String || !isPrimitive(a) || !isPrimitive(b)) {
            return toStr(a).concat(toStr(b));
        }
        return normalize(toDouble(a) + toDouble(b));
    }

    private static boolean isPrimitive(Object v) {
        return isNumber(v) || v instanceof Boolean || v instanceof String || v == Undefined.INSTANCE || v == Null.INSTANCE;
    }

    /** Relational comparison; returns false whenever a NaN is involved. */
    @TruffleBoundary
    public static boolean lessThan(Object a, Object b, boolean orEqual) {
        if (a instanceof String sa && b instanceof String sb) {
            int c = sa.compareTo(sb);
            return orEqual ? c <= 0 : c < 0;
        }
        double x = toDouble(a);
        double y = toDouble(b);
        return orEqual ? x <= y : x < y;
    }

    @TruffleBoundary
    public static boolean strictEquals(Object a, Object b) {
        if (isNumber(a) && isNumber(b)) {
            return toDouble(a) == toDouble(b);
        }
        if (a instanceof String sa && b instanceof String sb) {
            return sa.equals(sb);
        }
        if (a instanceof Boolean ba && b instanceof Boolean bb) {
            return ba.booleanValue() == bb.booleanValue();
        }
        return a == b;
    }

    @TruffleBoundary
    public static boolean looseEquals(Object a, Object b) {
        boolean aNullish = a == Null.INSTANCE || a == Undefined.INSTANCE;
        boolean bNullish = b == Null.INSTANCE || b == Undefined.INSTANCE;
        if (aNullish || bNullish) {
            return aNullish && bNullish;
        }
        if (a instanceof Boolean || b instanceof Boolean
                || isNumber(a) && b instanceof String || a instanceof String && isNumber(b)) {
            if (!(a instanceof String && b instanceof String)) {
                return toDouble(a) == toDouble(b);
            }
        }
        return strictEquals(a, b);
    }

    public static String typeName(Object v) {
        if (v == Undefined.INSTANCE) {
            return "undefined";
        }
        if (v == Null.INSTANCE) {
            return "null";
        }
        return v.getClass().getSimpleName();
    }

    /** {@code obj.name} for the receivers this subset supports. */
    @TruffleBoundary
    public static Object getProperty(Object obj, String name, Node location) {
        if (obj instanceof JsObject o) {
            return o.get(name);
        }
        if (obj instanceof String s) {
            return name.equals("length") ? (Object) s.length() : Undefined.INSTANCE;
        }
        if (isNumber(obj)) {
            return name.equals("toFixed") ? TO_FIXED : Undefined.INSTANCE;
        }
        if (obj == Undefined.INSTANCE || obj == Null.INSTANCE) {
            throw JsError.type("Cannot read properties of " + obj + " (reading '" + name + "')", location);
        }
        return Undefined.INSTANCE;
    }

    @TruffleBoundary
    public static void setProperty(Object obj, String name, Object value, Node location) {
        if (obj instanceof JsObject o) {
            o.put(name, value);
            return;
        }
        if (obj == Undefined.INSTANCE || obj == Null.INSTANCE) {
            throw JsError.type("Cannot set properties of " + obj + " (setting '" + name + "')", location);
        }
        // writes to primitives are silently ignored, as in sloppy-mode JavaScript
    }

    public static final JsBuiltin TO_FIXED = new JsBuiltin("toFixed", (self, args) -> {
        Object d = JsBuiltin.arg(args, 0);
        int digits = d == Undefined.INSTANCE ? 0 : (int) toDouble(d);
        return toFixed(toDouble(self), digits);
    });

    /** console.log formatting, close to Node's util.inspect for the values this subset has. */
    @TruffleBoundary
    public static String inspect(Object v, boolean nested) {
        if (v instanceof String s) {
            return nested ? "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'" : s;
        }
        if (v instanceof Double d && d == 0 && Double.doubleToRawLongBits(d) != 0) {
            return "-0";
        }
        if (v instanceof JsObject o) {
            Map<String, Object> m = o.entries();
            if (m.isEmpty()) {
                return "{}";
            }
            StringBuilder sb = new StringBuilder("{ ");
            boolean first = true;
            for (Map.Entry<String, Object> en : m.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(en.getKey()).append(": ").append(inspect(en.getValue(), true));
            }
            return sb.append(" }").toString();
        }
        if (v instanceof JsFunction f) {
            return f.name.isEmpty() ? "[Function (anonymous)]" : "[Function: " + f.name + "]";
        }
        if (v instanceof JsBuiltin b) {
            return "[Function: " + b.name + "]";
        }
        return toStr(v);
    }
}
