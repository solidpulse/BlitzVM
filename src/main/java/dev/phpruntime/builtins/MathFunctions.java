package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArith;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpOps;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Math functions. Return types follow PHP: floor/ceil/round always return float. */
final class MathFunctions {

    private MathFunctions() {
    }

    private static double d(Object[] a, int i) {
        return PhpOps.toDouble(a[i]);
    }

    static void register() {
        Builtins.def("abs", 1, 1, (ctx, a) -> {
            Object n = PhpOps.toNumber(a[0]);
            if (n instanceof Long l) {
                return l == Long.MIN_VALUE ? (Object) (-(double) l) : (Object) Math.abs(l);
            }
            return Math.abs((Double) n);
        });
        Builtins.def("max", 1, -1, (ctx, a) -> extreme(a, 1));
        Builtins.def("min", 1, -1, (ctx, a) -> extreme(a, -1));
        Builtins.def("floor", 1, 1, (ctx, a) -> Math.floor(d(a, 0)));
        Builtins.def("ceil", 1, 1, (ctx, a) -> Math.ceil(d(a, 0)));
        Builtins.def("round", 1, 2, (ctx, a) -> round(d(a, 0), a.length > 1 ? (int) PhpOps.toLong(a[1]) : 0));
        Builtins.def("sqrt", 1, 1, (ctx, a) -> Math.sqrt(d(a, 0)));
        Builtins.def("pow", 2, 2, (ctx, a) -> PhpArith.pow(a[0], a[1]));
        Builtins.def("intdiv", 2, 2, (ctx, a) -> {
            long x = PhpOps.toLong(a[0]);
            long y = PhpOps.toLong(a[1]);
            if (y == 0) {
                throw new PhpError(PhpError.DIVISION_BY_ZERO, "Division by zero", null);
            }
            if (x == Long.MIN_VALUE && y == -1) {
                throw new PhpError("Division of PHP_INT_MIN by -1 is not an integer");
            }
            return x / y;
        });
        Builtins.def("fmod", 2, 2, (ctx, a) -> d(a, 0) % d(a, 1));
        Builtins.def("fdiv", 2, 2, (ctx, a) -> d(a, 0) / d(a, 1));
        Builtins.def("pi", 0, 0, (ctx, a) -> Math.PI);
        Builtins.def("exp", 1, 1, (ctx, a) -> Math.exp(d(a, 0)));
        Builtins.def("log", 1, 2, (ctx, a) -> a.length > 1 ? Math.log(d(a, 0)) / Math.log(d(a, 1)) : Math.log(d(a, 0)));
        Builtins.def("log10", 1, 1, (ctx, a) -> Math.log10(d(a, 0)));
        Builtins.def("log2", 1, 1, (ctx, a) -> Math.log(d(a, 0)) / Math.log(2));
        Builtins.def("sin", 1, 1, (ctx, a) -> Math.sin(d(a, 0)));
        Builtins.def("cos", 1, 1, (ctx, a) -> Math.cos(d(a, 0)));
        Builtins.def("tan", 1, 1, (ctx, a) -> Math.tan(d(a, 0)));
        Builtins.def("atan", 1, 1, (ctx, a) -> Math.atan(d(a, 0)));
        Builtins.def("atan2", 2, 2, (ctx, a) -> Math.atan2(d(a, 0), d(a, 1)));
        Builtins.def("hypot", 2, 2, (ctx, a) -> Math.hypot(d(a, 0), d(a, 1)));
        Builtins.def("deg2rad", 1, 1, (ctx, a) -> Math.toRadians(d(a, 0)));
        Builtins.def("rad2deg", 1, 1, (ctx, a) -> Math.toDegrees(d(a, 0)));
        Builtins.def("is_nan", 1, 1, (ctx, a) -> Double.isNaN(d(a, 0)));
        Builtins.def("is_infinite", 1, 1, (ctx, a) -> Double.isInfinite(d(a, 0)));
        Builtins.def("is_finite", 1, 1, (ctx, a) -> Double.isFinite(d(a, 0)));
        Builtins.def("decbin", 1, 1, (ctx, a) -> Long.toBinaryString(PhpOps.toLong(a[0])));
        Builtins.def("bindec", 1, 1, (ctx, a) -> parseBase(PhpOps.toStr(a[0]), 2));
        Builtins.def("decoct", 1, 1, (ctx, a) -> Long.toOctalString(PhpOps.toLong(a[0])));
        Builtins.def("octdec", 1, 1, (ctx, a) -> parseBase(PhpOps.toStr(a[0]), 8));
        Builtins.def("base_convert", 3, 3, (ctx, a) -> {
            long v = parseBase(PhpOps.toStr(a[0]).toLowerCase(), (int) PhpOps.toLong(a[1]));
            return Long.toString(v, (int) PhpOps.toLong(a[2]));
        });
        Builtins.def("rand", 0, 2, MathFunctions::random);
        Builtins.def("mt_rand", 0, 2, MathFunctions::random);
        Builtins.def("random_int", 2, 2, MathFunctions::random);
        Builtins.def("mt_getrandmax", 0, 0, (ctx, a) -> 2147483647L);
        Builtins.def("getrandmax", 0, 0, (ctx, a) -> 2147483647L);
        Builtins.def("lcg_value", 0, 0, (ctx, a) -> ThreadLocalRandom.current().nextDouble());
    }

    private static Object random(dev.phpruntime.PhpContext ctx, Object[] a) {
        if (a.length < 2) {
            return (long) ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        }
        long lo = PhpOps.toLong(a[0]);
        long hi = PhpOps.toLong(a[1]);
        if (hi < lo) {
            throw new PhpError("Argument #2 ($max) must be greater than or equal to argument #1 ($min)");
        }
        return lo + ThreadLocalRandom.current().nextLong(hi - lo + 1);
    }

    /** max()/min() over either a single array or the argument list. */
    private static Object extreme(Object[] a, int sign) {
        List<Object> values;
        if (a.length == 1) {
            if (!(a[0] instanceof PhpArray arr)) {
                throw new PhpError((sign > 0 ? "max" : "min") + "(): Argument #1 ($value) must be of type array, " + PhpOps.typeName(a[0]) + " given");
            }
            values = arr.values();
            if (values.isEmpty()) {
                throw new PhpError((sign > 0 ? "max" : "min") + "(): Argument #1 ($value) must contain at least one element");
            }
        } else {
            values = List.of(a);
        }
        Object best = values.get(0);
        for (Object v : values) {
            if (PhpOps.compare(v, best) * sign > 0) {
                best = v;
            }
        }
        return best;
    }

    /** PHP's round(): half away from zero, with pre-rounding to avoid 1.005 surprises. */
    static double round(double v, int precision) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return v;
        }
        BigDecimal bd = new BigDecimal(Double.toString(v));
        return bd.setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }

    private static long parseBase(String s, int base) {
        StringBuilder digits = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.digit(c, base) >= 0) {
                digits.append(c);
            }
        }
        return digits.isEmpty() ? 0 : Long.parseLong(digits.toString(), base);
    }
}
