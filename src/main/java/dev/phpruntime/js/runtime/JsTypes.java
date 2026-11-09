package dev.phpruntime.js.runtime;

import com.oracle.truffle.api.dsl.ImplicitCast;
import com.oracle.truffle.api.dsl.TypeSystem;

/**
 * JavaScript has one number type (a double). Integral values that fit in an int are
 * carried as {@code int} so integer code stays on machine integer arithmetic; any
 * overflow or inexact division moves to {@code double}, which is what JS would have
 * computed anyway.
 */
@TypeSystem({int.class, double.class, boolean.class, String.class})
public abstract class JsTypes {

    @ImplicitCast
    public static double castDouble(int value) {
        return value;
    }
}
