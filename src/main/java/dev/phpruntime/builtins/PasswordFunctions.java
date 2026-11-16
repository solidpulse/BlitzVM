package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;

/** password_hash and friends. Only bcrypt is available; Argon2 is not. */
final class PasswordFunctions {

    private static final int DEFAULT_COST = 12;

    private PasswordFunctions() {
    }

    static void register() {
        Builtins.def("password_hash", 2, 3, (ctx, a) -> {
            String algo = algo(a[1]);
            if (!algo.equals("2y")) {
                throw new PhpError("ValueError", "password_hash(): Argument #2 ($algo) must be a valid password hashing algorithm", null);
            }
            byte[] password = PhpStrings.bytes(PhpOps.toStr(a[0]));
            for (byte b : password) {
                if (b == 0) {
                    throw new PhpError("ValueError", "Bcrypt password must not contain null character", null);
                }
            }
            int cost = cost(a.length > 2 ? a[2] : null);
            return Bcrypt.hash(password, cost);
        });
        Builtins.def("password_verify", 2, 2, (ctx, a) ->
                Bcrypt.verify(PhpStrings.bytes(PhpOps.toStr(a[0])), PhpOps.toStr(a[1])));
        Builtins.def("password_get_info", 1, 1, (ctx, a) -> {
            Bcrypt.Info info = Bcrypt.parse(PhpOps.toStr(a[0]));
            PhpArray out = new PhpArray();
            PhpArray options = new PhpArray();
            if (info == null) {
                out.put("algo", PhpNull.INSTANCE);
                out.put("algoName", "unknown");
            } else {
                out.put("algo", "2y");
                out.put("algoName", "bcrypt");
                options.put("cost", (long) info.cost());
            }
            out.put("options", options);
            return out;
        });
        Builtins.def("password_needs_rehash", 2, 3, (ctx, a) -> {
            Bcrypt.Info info = Bcrypt.parse(PhpOps.toStr(a[0]));
            String algo = algo(a[1]);
            if (info == null) {
                return true;
            }
            return !algo.equals("2y") || info.cost() != cost(a.length > 2 ? a[2] : null);
        });
        Builtins.def("password_algos", 0, 0, (ctx, a) -> PhpArray.ofList(java.util.List.of("2y")));
    }

    /** PASSWORD_DEFAULT and PASSWORD_BCRYPT are "2y"; null (as PHP 7.4+ allows) means the default. */
    private static String algo(Object v) {
        if (v == PhpNull.INSTANCE) {
            return "2y";
        }
        if (v instanceof Long l) {
            // Legacy integer constants: 0 = default, 1 = bcrypt.
            return l == 0 || l == 1 ? "2y" : "";
        }
        return PhpOps.toStr(v);
    }

    private static int cost(Object options) {
        if (options instanceof PhpArray opts && opts.get("cost") != null) {
            long c = PhpOps.toLong(opts.get("cost"));
            if (c < 4 || c > 31) {
                throw new PhpError("ValueError", "password_hash(): Argument #3 ($options) must contain a \"cost\" value between 4 and 31", null);
            }
            return (int) c;
        }
        return DEFAULT_COST;
    }
}
