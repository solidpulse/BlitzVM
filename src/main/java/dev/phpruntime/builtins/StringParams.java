package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.CallMode;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;

/**
 * Coercion for the {@code string} parameters of internal functions.
 *
 * <p>php's weak mode accepts a string, converts int, float and bool, converts null with a
 * deprecation and converts an object with {@code __toString}; everything else raises
 * {@code TypeError} with the declared parameter position and name. Under
 * {@code strict_types=1} only a string passes.
 */
final class StringParams {

    private StringParams() {
    }

    /** The value of the string parameter at position {@code i} of the running builtin. */
    static String of(PhpContext ctx, Object[] a, int i) {
        Object value = a[i];
        if (value instanceof String s) {
            return s;
        }
        String fn = ctx == null ? null : ctx.currentBuiltinName();
        String name = paramName(fn, i);
        if (ctx != null && CallMode.strictOfCaller(ctx.currentBuiltinCallSite())) {
            throw typeError(fn, i, name, given(value));
        }
        if (value == PhpNull.INSTANCE) {
            if (ctx != null) {
                ctx.deprecated((fn == null ? "" : fn + "(): ") + "Passing null to parameter #" + (i + 1)
                        + (name == null ? "" : " ($" + name + ")")
                        + " of type string is deprecated", 0);
            }
            return "";
        }
        if (value instanceof Long || value instanceof Double || value instanceof Boolean
                || value instanceof PhpObject o && o.phpClass().toStringMethod() != null) {
            return PhpOps.toStr(value);
        }
        throw typeError(fn, i, name, given(value));
    }

    private static String paramName(String fn, int i) {
        if (fn == null) {
            return null;
        }
        BuiltinParams.Param[] params = BuiltinParams.forName(fn);
        return params != null && i < params.length ? params[i].name() : null;
    }

    /** php's type description for an argument that failed the check. */
    private static String given(Object value) {
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        return PhpOps.typeDescription(value);
    }

    private static PhpError typeError(String fn, int i, String name, String given) {
        return new PhpError(PhpError.TYPE_ERROR, (fn == null ? "" : fn + "(): ")
                + "Argument #" + (i + 1) + (name == null ? "" : " ($" + name + ")")
                + " must be of type string, " + given + " given", null);
    }
}
