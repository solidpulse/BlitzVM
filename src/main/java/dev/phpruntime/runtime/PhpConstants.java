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
            Map.entry("JSON_HEX_TAG", 1L),
            Map.entry("JSON_HEX_AMP", 2L),
            Map.entry("JSON_HEX_APOS", 4L),
            Map.entry("JSON_HEX_QUOT", 8L),
            Map.entry("JSON_FORCE_OBJECT", 16L),
            Map.entry("JSON_NUMERIC_CHECK", 32L),
            Map.entry("JSON_PARTIAL_OUTPUT_ON_ERROR", 512L),
            Map.entry("JSON_PRESERVE_ZERO_FRACTION", 1024L),
            Map.entry("JSON_UNESCAPED_LINE_TERMINATORS", 2048L),
            Map.entry("JSON_OBJECT_AS_ARRAY", 1L),
            Map.entry("JSON_BIGINT_AS_STRING", 2L),
            Map.entry("JSON_INVALID_UTF8_IGNORE", 1048576L),
            Map.entry("JSON_INVALID_UTF8_SUBSTITUTE", 2097152L),
            Map.entry("JSON_ERROR_NONE", 0L),
            Map.entry("JSON_ERROR_DEPTH", 1L),
            Map.entry("JSON_ERROR_STATE_MISMATCH", 2L),
            Map.entry("JSON_ERROR_CTRL_CHAR", 3L),
            Map.entry("JSON_ERROR_SYNTAX", 4L),
            Map.entry("JSON_ERROR_UTF8", 5L),
            Map.entry("JSON_ERROR_RECURSION", 6L),
            Map.entry("JSON_ERROR_INF_OR_NAN", 7L),
            Map.entry("JSON_ERROR_UNSUPPORTED_TYPE", 8L),
            Map.entry("JSON_ERROR_INVALID_PROPERTY_NAME", 9L),
            Map.entry("JSON_ERROR_UTF16", 10L),
            Map.entry("PREG_PATTERN_ORDER", 1L),
            Map.entry("PREG_SET_ORDER", 2L),
            Map.entry("PREG_OFFSET_CAPTURE", 256L),
            Map.entry("PREG_UNMATCHED_AS_NULL", 512L),
            Map.entry("PREG_SPLIT_NO_EMPTY", 1L),
            Map.entry("PREG_SPLIT_DELIM_CAPTURE", 2L),
            Map.entry("PREG_SPLIT_OFFSET_CAPTURE", 4L),
            Map.entry("PREG_GREP_INVERT", 1L),
            Map.entry("PREG_NO_ERROR", 0L),
            Map.entry("PREG_INTERNAL_ERROR", 1L),
            Map.entry("PREG_BACKTRACK_LIMIT_ERROR", 2L),
            Map.entry("PREG_RECURSION_LIMIT_ERROR", 3L),
            Map.entry("PREG_BAD_UTF8_ERROR", 4L),
            Map.entry("PREG_BAD_UTF8_OFFSET_ERROR", 5L),
            Map.entry("PREG_JIT_STACKLIMIT_ERROR", 6L),
            Map.entry("ENT_QUOTES", 3L),
            Map.entry("ENT_COMPAT", 2L),
            Map.entry("ENT_NOQUOTES", 0L),
            Map.entry("ENT_IGNORE", 4L),
            Map.entry("ENT_SUBSTITUTE", 8L),
            Map.entry("ENT_DISALLOWED", 128L),
            Map.entry("ENT_HTML401", 0L),
            Map.entry("ENT_XML1", 16L),
            Map.entry("ENT_XHTML", 32L),
            Map.entry("ENT_HTML5", 48L),
            Map.entry("PATHINFO_DIRNAME", 1L),
            Map.entry("PATHINFO_BASENAME", 2L),
            Map.entry("PATHINFO_EXTENSION", 4L),
            Map.entry("PATHINFO_FILENAME", 8L),
            Map.entry("PATHINFO_ALL", 15L),
            Map.entry("FILE_USE_INCLUDE_PATH", 1L),
            Map.entry("FILE_APPEND", 8L),
            Map.entry("LOCK_SH", 1L),
            Map.entry("LOCK_EX", 2L),
            Map.entry("LOCK_UN", 3L),
            Map.entry("SCANDIR_SORT_ASCENDING", 0L),
            Map.entry("SCANDIR_SORT_DESCENDING", 1L),
            Map.entry("SCANDIR_SORT_NONE", 2L),
            Map.entry("CASE_LOWER", 0L),
            Map.entry("CASE_UPPER", 1L),
            Map.entry("PHP_URL_SCHEME", 0L),
            Map.entry("PHP_URL_HOST", 1L),
            Map.entry("PHP_URL_PORT", 2L),
            Map.entry("PHP_URL_USER", 3L),
            Map.entry("PHP_URL_PASS", 4L),
            Map.entry("PHP_URL_PATH", 5L),
            Map.entry("PHP_URL_QUERY", 6L),
            Map.entry("PHP_URL_FRAGMENT", 7L),
            Map.entry("PHP_QUERY_RFC1738", 1L),
            Map.entry("PHP_QUERY_RFC3986", 2L),
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
