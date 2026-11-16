package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

/** token_get_all and token_name. */
final class TokenizerFunctions {

    private TokenizerFunctions() {
    }

    static void register() {
        Builtins.def("token_get_all", 1, 2, (ctx, a) -> Tokenizer.tokenize(PhpOps.toStr(a[0])));
        Builtins.def("token_name", 1, 1, (ctx, a) -> Tokenizer.tokenName(PhpOps.toLong(a[0])));
    }
}
