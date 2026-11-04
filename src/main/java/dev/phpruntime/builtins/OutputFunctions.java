package dev.phpruntime.builtins;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** var_dump, print_r, var_export, printf: byte-for-byte compatible with php-cli output. */
final class OutputFunctions {

    private OutputFunctions() {
    }

    static void register() {
        Builtins.def("var_dump", 1, -1, (ctx, a) -> {
            for (Object v : a) {
                StringBuilder sb = new StringBuilder();
                varDump(sb, v, 0);
                ctx.echo(sb.toString());
            }
            return PhpNull.INSTANCE;
        });
        Builtins.def("print_r", 1, 2, (ctx, a) -> {
            StringBuilder sb = new StringBuilder();
            printR(sb, a[0], 0);
            if (a.length > 1 && PhpOps.toBool(a[1])) {
                return sb.toString();
            }
            ctx.echo(sb.toString());
            return true;
        });
        Builtins.def("var_export", 1, 2, (ctx, a) -> {
            StringBuilder sb = new StringBuilder();
            varExport(sb, a[0], 0);
            if (a.length > 1 && PhpOps.toBool(a[1])) {
                return sb.toString();
            }
            ctx.echo(sb.toString());
            return PhpNull.INSTANCE;
        });
        Builtins.def("printf", 1, -1, (ctx, a) -> {
            String s = StringFunctions.sprintf(a);
            ctx.echo(s);
            return (long) s.getBytes(StandardCharsets.UTF_8).length;
        });
        Builtins.def("flush", 0, 0, (ctx, a) -> {
            ctx.flush();
            return PhpNull.INSTANCE;
        });
    }

    static int byteLength(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    static void varDump(StringBuilder sb, Object v, int indent) {
        sb.append(" ".repeat(indent));
        if (v instanceof Long l) {
            sb.append("int(").append(l).append(")\n");
        } else if (v instanceof Double d) {
            sb.append("float(").append(PhpOps.formatDouble(d, 17)).append(")\n");
        } else if (v instanceof String s) {
            sb.append("string(").append(byteLength(s)).append(") \"").append(s).append("\"\n");
        } else if (v instanceof Boolean b) {
            sb.append("bool(").append(b).append(")\n");
        } else if (v instanceof PhpArray arr) {
            sb.append("array(").append(arr.size()).append(") {\n");
            for (Map.Entry<Object, Object> e : arr.entries()) {
                sb.append(" ".repeat(indent + 2)).append('[');
                if (e.getKey() instanceof String k) {
                    sb.append('"').append(k).append('"');
                } else {
                    sb.append(e.getKey());
                }
                sb.append("]=>\n");
                varDump(sb, e.getValue(), indent + 2);
            }
            sb.append(" ".repeat(indent)).append("}\n");
        } else {
            sb.append("NULL\n");
        }
    }

    static void printR(StringBuilder sb, Object v, int indent) {
        if (!(v instanceof PhpArray arr)) {
            sb.append(PhpOps.toStr(v));
            return;
        }
        sb.append("Array\n").append(" ".repeat(indent)).append("(\n");
        for (Map.Entry<Object, Object> e : arr.entries()) {
            sb.append(" ".repeat(indent + 4)).append('[').append(e.getKey()).append("] => ");
            printR(sb, e.getValue(), indent + 8);
            sb.append('\n');
        }
        sb.append(" ".repeat(indent)).append(")\n");
    }

    static void varExport(StringBuilder sb, Object v, int indent) {
        if (v instanceof String s) {
            sb.append('\'').append(s.replace("\\", "\\\\").replace("'", "\\'")).append('\'');
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Double d) {
            String f = PhpOps.formatDouble(d, 17);
            sb.append(f);
            if (f.matches("-?\\d+")) {
                sb.append(".0");
            }
        } else if (v instanceof PhpArray arr) {
            sb.append("array (\n");
            for (Map.Entry<Object, Object> e : arr.entries()) {
                sb.append(" ".repeat(indent + 2));
                if (e.getKey() instanceof String k) {
                    sb.append('\'').append(k.replace("\\", "\\\\").replace("'", "\\'")).append('\'');
                } else {
                    sb.append(e.getKey());
                }
                sb.append(" => ");
                if (e.getValue() instanceof PhpArray) {
                    sb.append('\n').append(" ".repeat(indent + 2));
                }
                varExport(sb, e.getValue(), indent + 2);
                sb.append(",\n");
            }
            sb.append(" ".repeat(indent)).append(')');
        } else if (v == PhpNull.INSTANCE) {
            sb.append("NULL");
        } else {
            sb.append(PhpOps.toStr(v));
        }
    }

    /** Kept for symmetry with other registries; output always goes through the context. */
    static void echo(PhpContext ctx, String s) {
        ctx.echo(s);
    }
}
