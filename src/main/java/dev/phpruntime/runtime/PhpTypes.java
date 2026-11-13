package dev.phpruntime.runtime;

import com.oracle.truffle.api.dsl.TypeSystem;

/**
 * Truffle DSL type system. Specialisations on {@code long}/{@code double} avoid
 * boxing.
 *
 * <p>There is deliberately no implicit long to double cast. With one, a double
 * specialization also accepts two ints, so once a node had seen a float, {@code 1 + 2}
 * or {@code 2 / 2} at that node came out as a float. Operators that mix ints and
 * floats have explicit (long, double) and (double, long) specializations instead,
 * which is also exactly when PHP converts the int.
 */
@TypeSystem({long.class, double.class, boolean.class, String.class, PhpArray.class})
public abstract class PhpTypes {
}
