package dev.phpruntime.builtins;

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
    }

    private static final Map<String, Def> TABLE = new HashMap<>();

    static {
        CoreFunctions.register();
        OutputFunctions.register();
        StringFunctions.register();
        ArrayFunctions.register();
        MathFunctions.register();
        FileFunctions.register();
        UrlFunctions.register();
        HashFunctions.register();
        DateFunctions.register();
        CtypeFunctions.register();
        SerializeFunctions.register();
        EnvFunctions.register();
    }

    private Builtins() {
    }

    public static void def(String name, int minArgs, int maxArgs, Builtin impl) {
        defRef(name, minArgs, maxArgs, 0, impl);
    }

    public static void defRef(String name, int minArgs, int maxArgs, int refMask, Builtin impl) {
        TABLE.put(name.toLowerCase(), new Def(name, minArgs, maxArgs, refMask, impl));
    }

    public static Def lookup(String name) {
        return TABLE.get(name.toLowerCase());
    }
}
