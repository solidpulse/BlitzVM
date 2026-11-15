package dev.phpruntime.runtime;

import java.util.Map;

/** Built-in compile-time PHP constants and true/false/null lookup. */
public final class PhpConstants {

    private static final Map<String, Object> COMPILE_TIME = Map.ofEntries(
            Map.entry("DIRECTORY_SEPARATOR", "/"),
            Map.entry("PATH_SEPARATOR", ":"),
            Map.entry("PHP_EOL", "\n"),
            Map.entry("PHP_INT_MAX", Long.MAX_VALUE),
            Map.entry("PHP_INT_MIN", Long.MIN_VALUE),
            Map.entry("PHP_INT_SIZE", 8L),
            Map.entry("PHP_FLOAT_EPSILON", Math.ulp(1.0)),
            Map.entry("PHP_FLOAT_MAX", Double.MAX_VALUE),
            Map.entry("PHP_VERSION", "8.3.0-truffle"),
            Map.entry("PHP_VERSION_ID", 80300L),
            Map.entry("PHP_MAJOR_VERSION", 8L),
            Map.entry("PHP_MINOR_VERSION", 3L),
            Map.entry("PHP_OS", "Linux"),
            Map.entry("PHP_OS_FAMILY", "Linux"),
            Map.entry("M_PI", Math.PI),
            Map.entry("M_E", Math.E),
            Map.entry("NAN", Double.NaN),
            Map.entry("INF", Double.POSITIVE_INFINITY),
            Map.entry("E_ERROR", 1L),
            Map.entry("E_WARNING", 2L),
            Map.entry("E_PARSE", 4L),
            Map.entry("E_NOTICE", 8L),
            Map.entry("E_CORE_ERROR", 16L),
            Map.entry("E_CORE_WARNING", 32L),
            Map.entry("E_COMPILE_ERROR", 64L),
            Map.entry("E_COMPILE_WARNING", 128L),
            Map.entry("E_USER_ERROR", 256L),
            Map.entry("E_USER_WARNING", 512L),
            Map.entry("E_USER_NOTICE", 1024L),
            Map.entry("E_STRICT", 2048L),
            Map.entry("E_RECOVERABLE_ERROR", 4096L),
            Map.entry("E_DEPRECATED", 8192L),
            Map.entry("E_USER_DEPRECATED", 16384L),
            Map.entry("E_ALL", 32767L),
            Map.entry("COUNT_RECURSIVE", 1L),
            Map.entry("COUNT_NORMAL", 0L),
            Map.entry("SORT_REGULAR", 0L),
            Map.entry("SORT_NUMERIC", 1L),
            Map.entry("SORT_STRING", 2L),
            Map.entry("STR_PAD_RIGHT", 1L),
            Map.entry("STR_PAD_LEFT", 0L),
            Map.entry("STR_PAD_BOTH", 2L),
            Map.entry("JSON_PRETTY_PRINT", 128L),
            Map.entry("JSON_UNESCAPED_SLASHES", 64L),
            Map.entry("JSON_UNESCAPED_UNICODE", 256L),
            Map.entry("JSON_THROW_ON_ERROR", 4194304L),
            Map.entry("ENT_QUOTES", 3L),
            Map.entry("PHP_ROUND_HALF_UP", 1L));

    private PhpConstants() {
    }

    /** Looks up a case-sensitive compile-time constant, or null if absent. */
    public static Object compileTime(String name) {
        return COMPILE_TIME.get(name);
    }

    /** Looks up a built-in constant (including case-insensitive true/false/null), or null if absent. */
    public static Object lookupBuiltin(String name) {
        String lower = name.toLowerCase();
        if (lower.equals("true")) {
            return Boolean.TRUE;
        }
        if (lower.equals("false")) {
            return Boolean.FALSE;
        }
        if (lower.equals("null")) {
            return PhpNull.INSTANCE;
        }
        return COMPILE_TIME.get(name);
    }
}
