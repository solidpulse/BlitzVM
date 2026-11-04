package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;

/**
 * A function implemented in Java. Arguments arrive already evaluated. For by-reference
 * parameters (see {@link Builtins#defRef}) the implementation stores the new value back
 * into {@code args[i]} and the call node writes it to the caller's variable.
 */
@FunctionalInterface
public interface Builtin {
    Object call(PhpContext ctx, Object[] args);
}
