package dev.phpruntime.builtins;

import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.Visibility;

import java.util.IdentityHashMap;
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
        Builtins.def("debug_zval_dump", 1, -1, (ctx, a) -> {
            for (Object v : a) {
                StringBuilder sb = new StringBuilder();
                varDump(sb, v, 0);
                if (v instanceof String) {
                    int end = sb.length() - 1;
                    // Java strings have no Zend refcount; literals and shared values are
                    // immutable. Keep the byte length/text correct and report interned.
                    sb.insert(end, " interned");
                }
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
            return (long) s.length();
        });
        Builtins.def("flush", 0, 0, (ctx, a) -> {
            ctx.flushOutput();
            return PhpNull.INSTANCE;
        });
    }

    static int byteLength(String s) {
        return s.length();
    }

    static void varDump(StringBuilder sb, Object v, int indent) {
        varDump(sb, v, indent, new IdentityHashMap<>());
    }

    private static void varDump(StringBuilder sb, Object v, int indent, IdentityHashMap<PhpObject, Boolean> seen) {
        varDump(sb, v, indent, seen, false);
    }

    private static void varDump(StringBuilder sb, Object v, int indent, IdentityHashMap<PhpObject, Boolean> seen, boolean isRef) {
        if (v instanceof PhpReference ref) {
            varDump(sb, ref.value == null ? PhpNull.INSTANCE : ref.value, indent, seen, true);
            return;
        }
        sb.append(" ".repeat(indent));
        if (isRef) {
            sb.append('&');
        }
        if (v instanceof Long l) {
            sb.append("int(").append(l).append(")\n");
        } else if (v instanceof Double d) {
            sb.append("float(").append(PhpOps.formatDouble(d, 17)).append(")\n");
        } else if (v instanceof String s) {
            sb.append("string(").append(byteLength(s)).append(") \"").append(s).append("\"\n");
        } else if (v instanceof Boolean b) {
            sb.append("bool(").append(b).append(")\n");
        } else if (v instanceof PhpArray arr) {
            sb.append("array(").append(arr.size()).append(") {").append((char) 10);
            for (Map.Entry<Object, Object> e : arr.rawEntries()) {
                sb.append(" ".repeat(indent + 2)).append('[');
                if (e.getKey() instanceof String k) {
                    sb.append('"').append(k).append('"');
                } else {
                    sb.append(e.getKey());
                }
                sb.append("]=>\n");
                varDump(sb, e.getValue(), indent + 2, seen, false);
            }
            sb.append(" ".repeat(indent)).append("}\n");
        } else if (v instanceof PhpObject obj) {
            if (seen.containsKey(obj)) {
                sb.append("*RECURSION*\n");
                return;
            }
            seen.put(obj, Boolean.TRUE);
            Object[] keys = DynamicObject.GetKeyArrayNode.getUncached().execute(obj);
            DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
            Map<String, Object> extra = obj.nativeState() != null ? obj.nativeState().debugProperties(obj) : null;
            int initCount = extra != null ? extra.size() : 0;
            for (Object rawKey : keys) {
                if (get.execute(obj, rawKey, PhpNull.INSTANCE) != PhpClass.UNINITIALIZED) {
                    initCount++;
                }
            }
            sb.append("object(").append(obj.phpClass().name()).append(")#").append(obj.id())
                    .append(" (").append(initCount).append(") {").append((char) 10);
            for (Object rawKey : keys) {
                String k = (String) rawKey;
                Object propVal = get.execute(obj, k, PhpNull.INSTANCE);
                if (propVal == PhpClass.UNINITIALIZED) {
                    continue;
                }
                sb.append(" ".repeat(indent + 2));
                appendVarDumpObjectKey(sb, obj.phpClass(), k);
                sb.append("=>").append((char) 10);
                if (propVal instanceof dev.phpruntime.runtime.PhpBacktrace.LazyTrace lt) {
                    propVal = lt.toPhpArray();
                }
                varDump(sb, propVal, indent + 2, seen, false);
            }
            if (extra != null) {
                for (Map.Entry<String, Object> e : extra.entrySet()) {
                    sb.append(" ".repeat(indent + 2));
                    appendVarDumpObjectKey(sb, obj.phpClass(), e.getKey());
                    sb.append("=>").append((char) 10);
                    varDump(sb, e.getValue(), indent + 2, seen, false);
                }
            }
            sb.append(" ".repeat(indent)).append("}").append((char) 10);
            seen.remove(obj);
        } else if (v instanceof dev.phpruntime.runtime.PhpResource r) {
            sb.append("resource(").append(r.id()).append(") of type (").append(r.type()).append(")\n");
        } else {
            sb.append("NULL\n");
        }
    }

    static void printR(StringBuilder sb, Object v, int indent) {
        printR(sb, v, indent, new IdentityHashMap<>());
    }

    private static void printR(StringBuilder sb, Object v, int indent, IdentityHashMap<PhpObject, Boolean> seen) {
        if (v instanceof PhpArray arr) {
            sb.append("Array\n").append(" ".repeat(indent)).append("(\n");
            for (Map.Entry<Object, Object> e : arr.entries()) {
                sb.append(" ".repeat(indent + 4)).append('[').append(e.getKey()).append("] => ");
                printR(sb, e.getValue(), indent + 8, seen);
                sb.append('\n');
            }
            sb.append(" ".repeat(indent)).append(")\n");
            return;
        }
        if (v instanceof PhpObject obj) {
            sb.append(obj.phpClass().name()).append(" Object\n").append(" ".repeat(indent)).append("(\n");
            if (seen.containsKey(obj)) {
                sb.append(" *RECURSION*\n");
                return;
            }
            seen.put(obj, Boolean.TRUE);
            Object[] keys = DynamicObject.GetKeyArrayNode.getUncached().execute(obj);
            DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
            for (Object rawKey : keys) {
                String k = (String) rawKey;
                Object rawVal = get.execute(obj, k, PhpNull.INSTANCE);
                if (rawVal == PhpClass.UNINITIALIZED) {
                    continue;
                }
                sb.append(" ".repeat(indent + 4)).append('[');
                appendPrintRObjectKey(sb, obj.phpClass(), k);
                sb.append("] => ");
                Object propVal = PhpClass.unwrapProp(rawVal);
                printR(sb, propVal, indent + 8, seen);
                sb.append((char) 10);
            }
            if (obj.nativeState() != null) {
                Map<String, Object> extra = obj.nativeState().debugProperties(obj);
                if (extra != null) {
                    for (Map.Entry<String, Object> e : extra.entrySet()) {
                        sb.append(" ".repeat(indent + 4)).append('[');
                        appendPrintRObjectKey(sb, obj.phpClass(), e.getKey());
                        sb.append("] => ");
                        printR(sb, e.getValue(), indent + 8, seen);
                        sb.append('\n');
                    }
                }
            }
            sb.append(" ".repeat(indent)).append(")\n");
            seen.remove(obj);
            return;
        }
        sb.append(PhpOps.toStr(v));
    }

    static void varExport(StringBuilder sb, Object v, int indent) {
        varExport(sb, v, indent, new IdentityHashMap<>());
    }

    private static void varExport(StringBuilder sb, Object v, int indent, IdentityHashMap<PhpObject, Boolean> seen) {
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
                if (e.getValue() instanceof PhpArray || e.getValue() instanceof PhpObject) {
                    sb.append('\n').append(" ".repeat(indent + 2));
                }
                varExport(sb, e.getValue(), indent + 2, seen);
                sb.append(",\n");
            }
            sb.append(" ".repeat(indent)).append(')');
        } else if (v instanceof PhpObject obj) {
            if (seen.containsKey(obj)) {
                sb.append("NULL");
                return;
            }
            seen.put(obj, Boolean.TRUE);
            boolean isStd = obj.phpClass().name().equalsIgnoreCase("stdClass");
            if (isStd) {
                sb.append("(object) array(\n");
            } else {
                sb.append('\\').append(obj.phpClass().name()).append("::__set_state(array(\n");
            }
            Object[] keys = DynamicObject.GetKeyArrayNode.getUncached().execute(obj);
            DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
            for (Object rawKey : keys) {
                String k = (String) rawKey;
                Object rawVal = get.execute(obj, k, PhpNull.INSTANCE);
                if (rawVal == PhpClass.UNINITIALIZED) {
                    continue;
                }
                String prop = plainPropertyName(k);
                Object val = PhpClass.unwrapProp(rawVal);
                sb.append(" ".repeat(indent + 3))
                        .append('\'').append(prop.replace("\\", "\\\\").replace("'", "\\'")).append('\'')
                        .append(" => ");
                if (val instanceof PhpArray || val instanceof PhpObject) {
                    sb.append('\n').append(" ".repeat(indent + 2));
                }
                varExport(sb, val, indent + 2, seen);
                sb.append(",\n");
            }
            sb.append(" ".repeat(indent)).append(isStd ? ")" : "))");
            seen.remove(obj);
        } else if (v == PhpNull.INSTANCE) {
            sb.append("NULL");
        } else {
            sb.append(PhpOps.toStr(v));
        }
    }

    private static void appendVarDumpObjectKey(StringBuilder sb, PhpClass cls, String key) {
        if (key.startsWith("\0")) {
            int sep = key.indexOf('\0', 1);
            String declaring = key.substring(1, sep);
            String prop = key.substring(sep + 1);
            sb.append("[\"").append(prop).append("\":\"").append(declaring).append("\":private]");
            return;
        }
        PhpClass.InstanceProp p = cls.findVisibleProp(key);
        if (p != null && p.visibility() == Visibility.PROTECTED) {
            sb.append("[\"").append(key).append("\":protected]");
        } else {
            sb.append("[\"").append(key).append("\"]");
        }
    }

    private static void appendPrintRObjectKey(StringBuilder sb, PhpClass cls, String key) {
        if (key.startsWith("\0")) {
            int sep = key.indexOf('\0', 1);
            String declaring = key.substring(1, sep);
            String prop = key.substring(sep + 1);
            sb.append(prop).append(':').append(declaring).append(":private");
            return;
        }
        PhpClass.InstanceProp p = cls.findVisibleProp(key);
        if (p != null && p.visibility() == Visibility.PROTECTED) {
            sb.append(key).append(":protected");
        } else {
            sb.append(key);
        }
    }

    private static String plainPropertyName(String key) {
        if (key.startsWith("\0")) {
            int sep = key.indexOf('\0', 1);
            return key.substring(sep + 1);
        }
        return key;
    }

    /** Kept for symmetry with other registries; output always goes through the context. */
    static void echo(PhpContext ctx, String s) {
        ctx.echo(s);
    }
}
