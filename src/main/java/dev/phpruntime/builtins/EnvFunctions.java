package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;

import java.util.List;
import java.util.Map;

/** Environment and configuration: getenv, ini_get, sys_get_temp_dir, extension checks. */
final class EnvFunctions {

    /** Extensions whose functions this runtime provides (at least the parts WordPress checks for). */
    private static final List<String> EXTENSIONS = List.of("Core", "date", "standard", "ctype", "hash", "json", "mbstring", "intl");

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
            Map.entry("short_open_tag", ""),
            Map.entry("output_buffering", "0"),
            Map.entry("precision", "14"),
            Map.entry("serialize_precision", "-1"),
            Map.entry("arg_separator.output", "&"),
            Map.entry("date.timezone", ""),
            Map.entry("intl.default_locale", ""),
            Map.entry("open_basedir", ""),
            Map.entry("disable_functions", ""),
            Map.entry("zlib.output_compression", "0"),
            Map.entry("mbstring.func_overload", "0"));

    /**
     * Values get_cfg_var() reports. Unlike ini_get() these are the values from the
     * configuration file and do not change when a script calls ini_set().
     */
    private static final Map<String, String> CFG = Map.ofEntries(
            Map.entry("precision", "14"),
            Map.entry("serialize_precision", "-1"),
            Map.entry("upload_max_filesize", "2M"),
            Map.entry("post_max_size", "8M"),
            Map.entry("max_execution_time", "0"),
            Map.entry("max_input_time", "-1"),
            Map.entry("memory_limit", "-1"),
            Map.entry("default_charset", "UTF-8"),
            Map.entry("short_open_tag", ""),
            Map.entry("display_errors", ""),
            Map.entry("log_errors", "1"),
            Map.entry("error_reporting", "22527"),
            Map.entry("allow_url_fopen", "1"),
            Map.entry("file_uploads", "1"));

    private EnvFunctions() {
    }

    static void register() {
        Builtins.def("getenv", 0, 2, (ctx, a) -> {
            ContextState state = ContextState.of(ctx);
            if (a.length == 0 || a[0] == PhpNull.INSTANCE) {
                PhpArray out = new PhpArray();
                System.getenv().forEach((k, v) -> out.put(PhpStrings.fromJava(k), PhpStrings.fromJava(v)));
                state.envOverrides.forEach((k, v) -> {
                    if (v == null) {
                        out.remove(PhpStrings.fromJava(k));
                    } else {
                        out.put(PhpStrings.fromJava(k), PhpStrings.fromJava(v));
                    }
                });
                return out;
            }
            String name = PhpStrings.toJava(PhpOps.toStr(a[0]));
            String v = state.envOverrides.containsKey(name) ? state.envOverrides.get(name) : System.getenv(name);
            return v == null ? false : PhpStrings.fromJava(v);
        });
        Builtins.def("putenv", 1, 1, (ctx, a) -> {
            String setting = PhpOps.toStr(a[0]);
            int eq = setting.indexOf('=');
            ContextState state = ContextState.of(ctx);
            if (eq < 0) {
                state.envOverrides.put(setting, null);
            } else {
                state.envOverrides.put(setting.substring(0, eq), setting.substring(eq + 1));
            }
            return true;
        });
        Builtins.def("ini_get", 1, 1, (ctx, a) -> {
            String name = PhpOps.toStr(a[0]);
            if (PhpStrings.lower(name).equals("unserialize_callback_func")) {
                return ContextState.of(ctx).unserializeCallbackFunc;
            }
            if (PhpStrings.lower(name).equals("error_reporting")) {
                return String.valueOf(ctx.errorReportingIni());
            }
            String v = INI.get(name);
            return v == null ? false : v;
        });
        Builtins.def("get_cfg_var", 1, 1, (ctx, a) -> {
            String name = PhpOps.toStr(a[0]);
            String v = CFG.get(name);
            return v == null ? false : PhpStrings.fromJava(v);
        });
        Builtins.def("sys_get_temp_dir", 0, 0, (ctx, a) -> PhpStrings.fromJava(tempDir()));
        Builtins.def("extension_loaded", 1, 1, (ctx, a) -> {
            String name = PhpOps.toStr(a[0]);
            return EXTENSIONS.stream().anyMatch(e -> e.equalsIgnoreCase(name));
        });
        Builtins.def("get_loaded_extensions", 0, 1, (ctx, a) ->
                a.length > 0 && PhpOps.toBool(a[0]) ? new PhpArray() : PhpArray.ofList(EXTENSIONS));
    }

    /** sys_get_temp_dir(): $TMPDIR without trailing slashes, else /tmp. */
    static String tempDir() {
        String tmp = System.getenv("TMPDIR");
        if (tmp == null || tmp.isEmpty()) {
            return "/tmp";
        }
        while (tmp.length() > 1 && tmp.endsWith("/")) {
            tmp = tmp.substring(0, tmp.length() - 1);
        }
        return tmp;
    }

    /** The ini settings ini_get() reports. */
    static Map<String, String> iniValues() {
        return INI;
    }
}
