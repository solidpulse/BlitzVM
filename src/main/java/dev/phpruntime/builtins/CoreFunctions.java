package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpRequest;

import java.util.Locale;

/** Type inspection, conversion, time, sleeping and the HTTP response API. */
final class CoreFunctions {

    private CoreFunctions() {
    }

    static void register() {
        types();
        time();
        http();
        Builtins.def("function_exists", 1, 1, (ctx, a) ->
                ctx.lookupFunction(PhpOps.toStr(a[0])) != null || Builtins.lookup(PhpOps.toStr(a[0])) != null);
        Builtins.def("define", 2, 2, (ctx, a) ->
                ctx.defineConstant(PhpOps.toStr(a[0]), PhpOps.copyOnAssign(a[1]), 0));
        Builtins.def("defined", 1, 1, (ctx, a) ->
                ctx.hasConstant(PhpOps.toStr(a[0])));
        Builtins.def("constant", 1, 1, (ctx, a) -> {
            String name = PhpOps.toStr(a[0]);
            Object v = ctx.readConstant(name);
            if (v == null) {
                throw new PhpError("Undefined constant \"" + name + "\"");
            }
            return v;
        });
        Builtins.def("php_sapi_name", 0, 0, (ctx, a) -> ctx.request().scheduler == dev.phpruntime.runtime.GuestScheduler.NONE ? "cli" : "truffle-http");
        Builtins.def("phpversion", 0, 1, (ctx, a) -> "8.3.0-truffle");
        Builtins.def("memory_get_usage", 0, 1, (ctx, a) -> Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        Builtins.def("memory_get_peak_usage", 0, 1, (ctx, a) -> Runtime.getRuntime().totalMemory());
        Builtins.def("error_reporting", 0, 1, (ctx, a) -> 32767L);
        Builtins.def("ini_set", 2, 2, (ctx, a) -> false);
        Builtins.def("set_time_limit", 1, 1, (ctx, a) -> true);
        Builtins.def("gethostname", 0, 0, (ctx, a) -> {
            try {
                return java.net.InetAddress.getLocalHost().getHostName();
            } catch (java.net.UnknownHostException e) {
                return "localhost";
            }
        });
        Builtins.def("getmypid", 0, 0, (ctx, a) -> ProcessHandle.current().pid());
    }

    private static void types() {
        Builtins.def("is_int", 1, 1, (ctx, a) -> a[0] instanceof Long);
        Builtins.def("is_integer", 1, 1, (ctx, a) -> a[0] instanceof Long);
        Builtins.def("is_long", 1, 1, (ctx, a) -> a[0] instanceof Long);
        Builtins.def("is_float", 1, 1, (ctx, a) -> a[0] instanceof Double);
        Builtins.def("is_double", 1, 1, (ctx, a) -> a[0] instanceof Double);
        Builtins.def("is_string", 1, 1, (ctx, a) -> a[0] instanceof String);
        Builtins.def("is_bool", 1, 1, (ctx, a) -> a[0] instanceof Boolean);
        Builtins.def("is_array", 1, 1, (ctx, a) -> a[0] instanceof PhpArray);
        Builtins.def("is_null", 1, 1, (ctx, a) -> a[0] == PhpNull.INSTANCE);
        Builtins.def("is_scalar", 1, 1, (ctx, a) -> a[0] instanceof Long || a[0] instanceof Double || a[0] instanceof String || a[0] instanceof Boolean);
        Builtins.def("is_numeric", 1, 1, (ctx, a) ->
                a[0] instanceof Long || a[0] instanceof Double || (a[0] instanceof String s && PhpOps.isNumericString(s)));
        Builtins.def("gettype", 1, 1, (ctx, a) -> switch (PhpOps.typeName(a[0])) {
            case "int" -> "integer";
            case "float" -> "double";
            case "bool" -> "boolean";
            case "null" -> "NULL";
            default -> PhpOps.typeName(a[0]);
        });
        Builtins.def("get_debug_type", 1, 1, (ctx, a) -> PhpOps.typeName(a[0]));
        Builtins.def("intval", 1, 2, (ctx, a) -> {
            if (a.length > 1 && a[0] instanceof String s) {
                try {
                    return Long.parseLong(s.strip(), (int) PhpOps.toLong(a[1]));
                } catch (NumberFormatException e) {
                    return 0L;
                }
            }
            return PhpOps.toLong(a[0]);
        });
        Builtins.def("floatval", 1, 1, (ctx, a) -> PhpOps.toDouble(a[0]));
        Builtins.def("doubleval", 1, 1, (ctx, a) -> PhpOps.toDouble(a[0]));
        Builtins.def("strval", 1, 1, (ctx, a) -> PhpOps.toStr(a[0]));
        Builtins.def("boolval", 1, 1, (ctx, a) -> PhpOps.toBool(a[0]));
    }

    private static void time() {
        Builtins.def("time", 0, 0, (ctx, a) -> System.currentTimeMillis() / 1000);
        Builtins.def("microtime", 0, 1, (ctx, a) -> {
            long nanos = System.currentTimeMillis() * 1_000_000L + (System.nanoTime() % 1_000_000L);
            double secs = nanos / 1e9;
            if (a.length > 0 && PhpOps.toBool(a[0])) {
                return secs;
            }
            long whole = (long) secs;
            return String.format(Locale.ROOT, "%.8f %d", secs - whole, whole);
        });
        Builtins.def("hrtime", 0, 1, (ctx, a) -> {
            long t = System.nanoTime();
            if (a.length > 0 && PhpOps.toBool(a[0])) {
                return t;
            }
            PhpArray r = new PhpArray();
            r.append(t / 1_000_000_000L);
            r.append(t % 1_000_000_000L);
            return r;
        });
        // Sleeping releases the CPU slot to other requests, like a goroutine parking.
        Builtins.def("usleep", 1, 1, (ctx, a) -> {
            long micros = PhpOps.toLong(a[0]);
            ctx.request().scheduler.blocking(null, () -> Thread.sleep(micros / 1000, (int) (micros % 1000) * 1000));
            return PhpNull.INSTANCE;
        });
        Builtins.def("sleep", 1, 1, (ctx, a) -> {
            long secs = PhpOps.toLong(a[0]);
            ctx.request().scheduler.blocking(null, () -> Thread.sleep(secs * 1000));
            return 0L;
        });
    }

    private static void http() {
        Builtins.def("header", 1, 3, (ctx, a) -> {
            String line = PhpOps.toStr(a[0]);
            boolean replace = a.length < 2 || PhpOps.toBool(a[1]);
            PhpRequest req = ctx.request();
            if (a.length > 2) {
                req.status = (int) PhpOps.toLong(a[2]);
            }
            if (line.regionMatches(true, 0, "HTTP/", 0, 5)) {
                String[] parts = line.split(" ");
                if (parts.length > 1) {
                    req.status = Integer.parseInt(parts[1]);
                }
                return PhpNull.INSTANCE;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new PhpError("header(): malformed header \"" + line + "\"");
            }
            String name = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            req.setHeader(name, value, replace);
            if (name.equalsIgnoreCase("Location") && req.status == 200) {
                req.status = 302;
            }
            return PhpNull.INSTANCE;
        });
        Builtins.def("http_response_code", 0, 1, (ctx, a) -> {
            PhpRequest req = ctx.request();
            long old = req.status;
            if (a.length > 0) {
                req.status = (int) PhpOps.toLong(a[0]);
            }
            return old;
        });
        Builtins.def("headers_sent", 0, 0, (ctx, a) -> false);
    }
}
