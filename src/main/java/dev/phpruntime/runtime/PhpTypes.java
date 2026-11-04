package dev.phpruntime.runtime;

import com.oracle.truffle.api.dsl.ImplicitCast;
import com.oracle.truffle.api.dsl.TypeSystem;

/**
 * Truffle DSL type system. Specialisations on {@code long}/{@code double} avoid
 * boxing; an int operand meeting a float operand is widened implicitly, matching PHP.
 */
@TypeSystem({long.class, double.class, boolean.class, String.class, PhpArray.class})
public abstract class PhpTypes {

    @ImplicitCast
    public static double longToDouble(long v) {
        return v;
    }
}
