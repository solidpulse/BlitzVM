package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpStrings;

import java.util.HashMap;
import java.util.Map;

/** Registry of Java-implemented PHP functions, looked up case-insensitively. */
public final class Builtins {

    /**
     * @param minArgs fewest arguments accepted
     * @param maxArgs most arguments accepted, or -1 for variadic
     * @param refMask bit i set means parameter i is passed by reference
     */
    public record Def(String name, int minArgs, int maxArgs, int refMask, Builtin impl) {
        public boolean isRef(int i) {
            return i < 31 && (refMask & (1 << i)) != 0;
        }

        /** All builtin dispatch paths use this boundary, including callbacks and unpacked calls. */
        public Object call(PhpContext ctx, Object[] args) {
            if (!PhpStrings.CHECK) {
                return impl.call(ctx, args);
            }
            String previous = ctx == null ? null : ctx.byteStringOrigin();
            if (ctx != null) {
                ctx.setByteStringOrigin(name);
            }
            try {
                Object value = impl.call(ctx, args);
                PhpStrings.checkValue(value, name + "()");
                for (int i = 0; i < args.length; i++) {
                    if (isRef(i)) {
                        PhpStrings.checkValue(args[i], name + "() reference argument");
                    }
                }
                return value;
            } finally {
                if (ctx != null) {
                    ctx.setByteStringOrigin(previous);
                }
            }
        }
    }

    private static final Map<String, Def> TABLE = new HashMap<>();

    static {
        CoreFunctions.register();
        ClassFunctions.register();
        CallableFunctions.register();
        OutputFunctions.register();
        StringFunctions.register();
        ArrayFunctions.register();
        MathFunctions.register();
        PregFunctions.register();
        JsonFunctions.register();
        FileFunctions.register();
        UrlFunctions.register();
        HashFunctions.register();
        DateFunctions.register();
        CtypeFunctions.register();
        SerializeFunctions.register();
        EnvFunctions.register();
        MysqliFunctions.register();
        OutputBufferFunctions.register();
        PointerFunctions.register();
        StreamFunctions.register();
        PasswordFunctions.register();
        ZlibFunctions.register();
        HttpFunctions.register();
        MbConvertFunctions.register();
        MiscFunctions.register();
        SplFunctions.register();
        BinaryFunctions.register();
        RuntimeFunctions.register();
        TextFunctions.register();
        CryptFunctions.register();
        FilterFunctions.register();
    }

    private Builtins() {
    }

    public static void def(String name, int minArgs, int maxArgs, Builtin impl) {
        defRef(name, minArgs, maxArgs, 0, impl);
    }

    public static void defRef(String name, int minArgs, int maxArgs, int refMask, Builtin impl) {
        TABLE.put(PhpStrings.lower(name), new Def(name, minArgs, maxArgs, refMask, impl));
    }

    public static Def lookup(String name) {
        return TABLE.get(PhpStrings.lower(name));
    }

    /** Every registered builtin's declared name, for get_defined_functions(). */
    public static java.util.List<String> names() {
        java.util.List<String> out = new java.util.ArrayList<>(TABLE.size());
        for (Def def : TABLE.values()) {
            out.add(def.name());
        }
        return out;
    }
}
