package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpStrings;

/** Compatibility shim; all runtime callers now use {@link PhpStrings} directly. */
@Deprecated
final class BinaryStrings {

    private BinaryStrings() {
    }

    static byte[] bytes(String s) {
        return PhpStrings.bytes(s);
    }
}
