package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Errors the script itself raises, plus runtime information and assertions. */
final class RuntimeFunctions {

    private RuntimeFunctions() {
    }

    static void register() {
        errors();
        runtimeInfo();
    }

    private static void errors() {
        Builtins.def("trigger_error", 1, 2, (ctx, a) -> {
            long level = a.length > 1 ? PhpOps.toLong(a[1]) : 1024L;
            if (level != 256 && level != 512 && level != 1024 && level != 16384) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "trigger_error(): Argument #2 ($error_level) must be one of E_USER_ERROR, E_USER_WARNING, "
                                + "E_USER_NOTICE, or E_USER_DEPRECATED", null);
            }
            triggerUserError(ctx, PhpOps.toStr(a[0]), level);
            return true;
        });
        Builtins.def("user_error", 1, 2, (ctx, a) -> {
            long level = a.length > 1 ? PhpOps.toLong(a[1]) : 1024L;
            if (level != 256 && level != 512 && level != 1024 && level != 16384) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "user_error(): Argument #2 ($error_level) must be one of E_USER_ERROR, E_USER_WARNING, "
                                + "E_USER_NOTICE, or E_USER_DEPRECATED", null);
            }
            triggerUserError(ctx, PhpOps.toStr(a[0]), level);
            return true;
        });
        // zend.assertions is -1 in this build (php -i says so), so assert() is compiled out:
        // its arguments are never evaluated and the result is always true.
        Builtins.def("assert", 1, 2, (ctx, a) -> true);
    }

    private static void triggerUserError(PhpContext ctx, String message, long level) {
        // The calling line from an AST node or a bytecode call site.
        int line = dev.phpruntime.runtime.PhpBacktrace.callSiteLine(ctx.currentBuiltinCallSite());
        if (level == 256) {
            ctx.userError(8192L, "Passing E_USER_ERROR to trigger_error() is deprecated since 8.4, "
                    + "throw an exception or call exit with a string message instead", line);
        }
        boolean handled = ctx.userError(level, message, line);
        if (level == 256 && !handled) {
            // The fatal line has already gone to stderr; stop the script like php does.
            throw new dev.phpruntime.nodes.ControlFlow.Exit();
        }
    }

    private static void runtimeInfo() {
        Builtins.def("get_defined_functions", 0, 1, (ctx, a) -> {
            boolean excludeDisabled = a.length == 0 || PhpOps.toBool(a[0]);
            List<String> internal = new ArrayList<>(Builtins.names());
            internal.sort(String::compareTo);
            PhpArray out = new PhpArray();
            PhpArray internalArr = new PhpArray();
            for (String n : internal) {
                internalArr.append(n);
            }
            PhpArray userArr = new PhpArray();
            for (String n : ctx.definedFunctionNames()) {
                userArr.append(PhpStrings.lower(n));
            }
            out.put("internal", internalArr);
            out.put("user", userArr);
            return out;
        });
        Builtins.def("gc_enabled", 0, 0, (ctx, a) -> true);
        Builtins.def("zend_version", 0, 0, (ctx, a) -> "4.3.0");
        Builtins.def("php_uname", 0, 1, (ctx, a) -> {
            String mode = a.length > 0 ? PhpOps.toStr(a[0]) : "a";
            if (mode.length() != 1 || "amsnrv".indexOf(mode.charAt(0)) < 0) {
                throw new PhpError(PhpError.VALUE_ERROR,
                        "php_uname(): Argument #1 ($mode) must be one of \"a\", \"m\", \"n\", \"r\", \"s\", or \"v\"", null);
            }
            char m = mode.charAt(0);
            if (m == 'a') {
                return PhpStrings.fromJava(uname('s') + " " + uname('n') + " " + uname('r') + " " + uname('v') + " " + uname('m'));
            }
            return PhpStrings.fromJava(uname(m));
        });
        Builtins.def("ignore_user_abort", 0, 1, (ctx, a) -> {
            ContextState state = ContextState.of(ctx);
            long previous = state.ignoreUserAbort;
            if (a.length > 0 && a[0] != dev.phpruntime.runtime.PhpNull.INSTANCE) {
                state.ignoreUserAbort = PhpOps.toBool(a[0]) ? 1 : 0;
            }
            return previous;
        });
        Builtins.def("phpinfo", 0, 1, (ctx, a) -> {
            long what = a.length > 0 ? PhpOps.toLong(a[0]) : 0x7fffffffL;
            StringBuilder sb = new StringBuilder();
            sb.append("phpinfo()\n");
            if ((what & 1L) != 0) {
                sb.append("PHP Version => 8.3.0-truffle\n");
                sb.append("System => ").append(uname('s')).append(' ').append(uname('n'))
                        .append(' ').append(uname('r')).append(' ').append(uname('m')).append('\n');
                sb.append("Server API => Command Line Interface\n");
                sb.append("Loaded Configuration File => (none)\n");
            }
            if ((what & 2L) != 0) {
                sb.append("\nCore\n\nassert.active => On => On\n");
                sb.append("error_reporting => 32767 => 32767\n");
                sb.append("display_errors => On => On\n");
            }
            ctx.echo(sb.toString());
            return true;
        });
    }

    private static String uname(char mode) {
        return switch (mode) {
            case 's' -> osName();
            case 'n' -> hostName();
            case 'r' -> System.getProperty("os.version", "");
            case 'v' -> kernelVersion();
            case 'm' -> machine();
            default -> "";
        };
    }

    private static String osName() {
        String name = System.getProperty("os.name", "Linux");
        if (name.startsWith("Mac")) {
            return "Darwin";
        }
        if (name.startsWith("Windows")) {
            return "Windows NT";
        }
        return name;
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "localhost";
        }
    }

    private static String machine() {
        String arch = System.getProperty("os.arch", "");
        return switch (arch) {
            case "amd64" -> "x86_64";
            case "arm64" -> "aarch64";
            case "x86" -> "i686";
            default -> arch;
        };
    }

    /** php_uname("v") is the tail of /proc/version after the compiler's parenthesized fields. */
    private static String kernelVersion() {
        try {
            String text = Files.readString(Path.of("/proc/version"), StandardCharsets.ISO_8859_1).trim();
            int close = text.lastIndexOf(')');
            if (close >= 0 && close + 1 < text.length()) {
                return text.substring(close + 1).trim();
            }
        } catch (IOException | RuntimeException ignored) {
            // fall through
        }
        return System.getProperty("os.version", "");
    }
}
