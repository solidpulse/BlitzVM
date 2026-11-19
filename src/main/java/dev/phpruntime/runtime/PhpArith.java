package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/** Generic (slow-path) arithmetic used by DSL fallbacks and compound assignment. */
public final class PhpArith {

    private PhpArith() {
    }

    public enum Op {
        ADD, SUB, MUL, DIV, MOD, POW, CONCAT, BIT_AND, BIT_OR, BIT_XOR, SHL, SHR, COALESCE
    }

    @TruffleBoundary
    public static Object apply(Op op, Object a, Object b) {
        return switch (op) {
            case ADD -> add(a, b);
            case SUB -> sub(a, b);
            case MUL -> mul(a, b);
            case DIV -> div(a, b);
            case MOD -> mod(a, b);
            case POW -> pow(a, b);
            case CONCAT -> PhpOps.toStr(a).concat(PhpOps.toStr(b));
            case BIT_AND -> a instanceof String x && b instanceof String y ? bitwise(x, y, op) : PhpOps.toLong(a) & PhpOps.toLong(b);
            case BIT_OR -> a instanceof String x && b instanceof String y ? bitwise(x, y, op) : PhpOps.toLong(a) | PhpOps.toLong(b);
            case BIT_XOR -> a instanceof String x && b instanceof String y ? bitwise(x, y, op) : PhpOps.toLong(a) ^ PhpOps.toLong(b);
            case SHL -> PhpOps.toLong(a) << PhpOps.toLong(b);
            case SHR -> PhpOps.toLong(a) >> PhpOps.toLong(b);
            case COALESCE -> a == null || a == PhpNull.INSTANCE ? b : a;
        };
    }

    private static String bitwise(String a, String b, Op op) {
        int common = Math.min(a.length(), b.length());
        int length = op == Op.BIT_OR ? Math.max(a.length(), b.length()) : common;
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < common; i++) {
            int x = a.charAt(i);
            int y = b.charAt(i);
            out.append((char) (op == Op.BIT_AND ? x & y : op == Op.BIT_OR ? x | y : x ^ y));
        }
        if (op == Op.BIT_OR) {
            out.append(a.length() > common ? a.substring(common) : b.substring(common));
        }
        return out.toString();
    }

    @TruffleBoundary
    public static Object add(Object a, Object b) {
        if (a instanceof PhpArray x && b instanceof PhpArray y) {
            // Array union: keys from the left win.
            PhpArray r = x.share().forWrite(); // always a fresh copy of x
            y.forEachEntry((key, value) -> {
                if (!r.containsKey(key)) {
                    r.put(key, PhpOps.copyOnAssign(value));
                }
            });
            return r;
        }
        Object x = PhpOps.toNumber(a);
        Object y = PhpOps.toNumber(b);
        if (x instanceof Long l && y instanceof Long m) {
            long r = l + m;
            if (((l ^ r) & (m ^ r)) < 0) {
                return (double) l + (double) m;
            }
            return r;
        }
        return PhpOps.toDouble(x) + PhpOps.toDouble(y);
    }

    @TruffleBoundary
    public static Object sub(Object a, Object b) {
        Object x = PhpOps.toNumber(a);
        Object y = PhpOps.toNumber(b);
        if (x instanceof Long l && y instanceof Long m) {
            long r = l - m;
            if (((l ^ m) & (l ^ r)) < 0) {
                return (double) l - (double) m;
            }
            return r;
        }
        return PhpOps.toDouble(x) - PhpOps.toDouble(y);
    }

    @TruffleBoundary
    public static Object mul(Object a, Object b) {
        Object x = PhpOps.toNumber(a);
        Object y = PhpOps.toNumber(b);
        if (x instanceof Long l && y instanceof Long m) {
            long hi = Math.multiplyHigh(l, m);
            long lo = l * m;
            if ((hi == 0 && lo >= 0) || (hi == -1 && lo < 0)) {
                return lo;
            }
            return (double) l * (double) m;
        }
        return PhpOps.toDouble(x) * PhpOps.toDouble(y);
    }

    @TruffleBoundary
    public static Object div(Object a, Object b) {
        Object x = PhpOps.toNumber(a);
        Object y = PhpOps.toNumber(b);
        if (PhpOps.toDouble(y) == 0) {
            throw new PhpError(PhpError.DIVISION_BY_ZERO, "Division by zero", null);
        }
        if (x instanceof Long l && y instanceof Long m && m != 0 && l % m == 0 && !(l == Long.MIN_VALUE && m == -1)) {
            return l / m;
        }
        return PhpOps.toDouble(x) / PhpOps.toDouble(y);
    }

    @TruffleBoundary
    public static Object mod(Object a, Object b) {
        long x = PhpOps.toLong(a);
        long y = PhpOps.toLong(b);
        if (y == 0) {
            throw new PhpError(PhpError.DIVISION_BY_ZERO, "Modulo by zero", null);
        }
        if (y == -1) {
            return 0L;
        }
        return x % y;
    }

    @TruffleBoundary
    public static Object pow(Object a, Object b) {
        Object x = PhpOps.toNumber(a);
        Object y = PhpOps.toNumber(b);
        if (x instanceof Long base && y instanceof Long exp && exp >= 0) {
            long result = 1;
            long bb = base;
            long e = exp;
            try {
                while (e > 0) {
                    if ((e & 1) == 1) {
                        result = Math.multiplyExact(result, bb);
                    }
                    e >>= 1;
                    if (e > 0) {
                        bb = Math.multiplyExact(bb, bb);
                    }
                }
                return result;
            } catch (ArithmeticException overflow) {
                return Math.pow(base, exp);
            }
        }
        return Math.pow(PhpOps.toDouble(x), PhpOps.toDouble(y));
    }

    @TruffleBoundary
    public static Object negate(Object v) {
        Object n = PhpOps.toNumber(v);
        if (n instanceof Long l) {
            // Not a ?: here: Java would promote both arms to double and return a float.
            if (l == Long.MIN_VALUE) {
                return -(double) l;
            }
            return -l;
        }
        return -(Double) n;
    }
}
