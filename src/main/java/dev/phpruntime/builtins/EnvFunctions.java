package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

import java.util.List;
import java.util.Map;

/** Environment and configuration: getenv, ini_get, sys_get_temp_dir, extension checks. */
final class EnvFunctions {

    /** Extensions whose functions this runtime provides (at least the parts WordPress checks for). */
    private static final List<String> EXTENSIONS = List.of("Core", "date", "standard", "ctype", "hash", "json", "mbstring");

    /** ini values reported by ini_get; ini_set does not change them yet. */
    private static final Map<String, String> INI = Map.ofEntries(
            Map.entry("memory_limit", "128M"),
            Map.entry("max_execution_time", "0"),
            Map.entry("max_input_time", "-1"),
            Map.entry("display_errors", "1"),
            Map.entry("log_errors", "0"),
            Map.entry("error_log", ""),
            Map.entry("upload_max_filesize", "2M"),
            Map.entry("post_max_size", "8M"),
            Map.entry("max_input_vars", "1000"),
            Map.entry("file_uploads", "1"),
            Map.entry("allow_url_fopen", "1"),
            Map.entry("default_charset", "UTF-8"),
            Map.entry("short_open_tag", "1"),
            Map.entry("output_buffering", "0"),
            Map.entry("precision", "14"),
            Map.entry("serialize_precision", "-1"),
            Map.entry("arg_separator.output", "&"),
            Map.entry("date.timezone", ""),
            Map.entry("open_basedir", ""),
            Map.entry("disable_functions", ""),
            Map.entry("zlib.output_compression", "0"),
            Map.entry("mbstring.func_overload", "0"));

    private EnvFunctions() {
    }

    static void register() {
        Builtins.def("getenv", 0, 2, (ctx, a) -> {
            if (a.length == 0 || a[0] == PhpNull.INSTANCE) {
                PhpArray out = new PhpArray();
                System.getenv().forEach(out::put);
                return out;
            }
            String v = System.getenv(PhpOps.toStr(a[0]));
            return v == null ? false : v;
        });
        Builtins.def("ini_get", 1, 1, (ctx, a) -> {
            String v = INI.get(PhpOps.toStr(a[0]));
            return v == null ? false : v;
        });
        Builtins.def("sys_get_temp_dir", 0, 0, (ctx, a) -> {
            String tmp = System.getenv("TMPDIR");
            if (tmp == null || tmp.isEmpty()) {
                return "/tmp";
            }
            while (tmp.length() > 1 && tmp.endsWith("/")) {
                tmp = tmp.substring(0, tmp.length() - 1);
            }
            return tmp;
        });
        Builtins.def("extension_loaded", 1, 1, (ctx, a) -> {
            String name = PhpOps.toStr(a[0]);
            return EXTENSIONS.stream().anyMatch(e -> e.equalsIgnoreCase(name));
        });
        Builtins.def("get_loaded_extensions", 0, 1, (ctx, a) ->
                a.length > 0 && PhpOps.toBool(a[0]) ? new PhpArray() : PhpArray.ofList(EXTENSIONS));
    }
}
