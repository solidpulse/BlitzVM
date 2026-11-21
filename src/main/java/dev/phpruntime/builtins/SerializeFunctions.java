package dev.phpruntime.builtins;

import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.call.CallArgHelper;
import dev.phpruntime.runtime.EnumCaseState;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpAttribute;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpResource;
import dev.phpruntime.runtime.PhpStrings;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * serialize and unserialize for scalars, arrays, objects, enums and back-references. Each string
 * char is one byte, so serialized lengths are ordinary String.length(), including binary and
 * invalid UTF-8.
 */
final class SerializeFunctions {

    private SerializeFunctions() {
    }

    static void register() {
        Builtins.def("serialize", 1, 1, (ctx, a) -> {
            Serializer s = new Serializer(ctx);
            s.value(a[0]);
            return s.sb.toString();
        });
        Builtins.def("unserialize", 1, 2, (ctx, a) -> {
            String data = dataArgument(ctx, a[0]);
            Options options = Options.parse(ctx, a.length > 1 ? a[1] : PhpNull.INSTANCE, a.length > 1);
            return new Unserializer(ctx, PhpStrings.bytes(data), options).run();
        });
    }

    private static String dataArgument(PhpContext ctx, Object value) {
        if (value == PhpNull.INSTANCE) {
            ctx.deprecated("unserialize(): Passing null to parameter #1 ($data) of type string is deprecated", 0);
            return "";
        }
        if (value instanceof PhpArray) {
            throw new PhpError(PhpError.TYPE_ERROR,
                    "unserialize(): Argument #1 ($data) must be of type string, array given", null);
        }
        return PhpOps.toStr(value);
    }

    // ------------------------------------------------------------------ serialize

    private static final class Serializer {
        final PhpContext ctx;
        final StringBuilder sb = new StringBuilder();
        private IdentityHashMap<PhpObject, Integer> objectIds;
        private IdentityHashMap<PhpReference, Integer> refIds;
        private IdentityHashMap<PhpArray, Integer> arrayPositions;
        /** Positions of expanded root-array references, php's two-level form for &$array. */
        private IdentityHashMap<PhpArray, Integer> expandedRoots;
        private int count;

        Serializer(PhpContext ctx) {
            this.ctx = ctx;
        }

        void value(Object v) {
            if (v instanceof PhpReference ref) {
                reference(ref);
                return;
            }
            if (v instanceof PhpObject obj) {
                Integer id = objectIds == null ? null : objectIds.get(obj);
                if (id != null) {
                    sb.append("r:").append(id).append(';');
                    return;
                }
                count++;
                if (objectIds == null) {
                    objectIds = new IdentityHashMap<>();
                }
                objectIds.put(obj, count);
                writeObject(obj);
                return;
            }
            count++;
            writePlain(v);
        }

        private void reference(PhpReference ref) {
            Integer id = refIds == null ? null : refIds.get(ref);
            if (id != null) {
                sb.append("R:").append(id).append(';');
                return;
            }
            Object inner = ref.getValue();
            if (inner instanceof PhpObject obj) {
                Integer objectId = objectIds == null ? null : objectIds.get(obj);
                if (refIds == null) {
                    refIds = new IdentityHashMap<>();
                }
                if (objectId != null) {
                    refIds.put(ref, objectId);
                    sb.append("R:").append(objectId).append(';');
                    return;
                }
                refIds.put(ref, count + 1);
                value(obj);
                return;
            }
            if (inner instanceof PhpArray arr && expandedRoots != null) {
                Integer expanded = expandedRoots.get(arr);
                if (expanded != null) {
                    if (refIds == null) {
                        refIds = new IdentityHashMap<>();
                    }
                    refIds.put(ref, expanded);
                    sb.append("R:").append(expanded).append(';');
                    return;
                }
            }
            if (inner instanceof PhpArray arr && arrayPositions != null) {
                Integer arrayId = arrayPositions.get(arr);
                if (arrayId != null) {
                    if (arrayId == 1) {
                        // php keeps a reference to the root array as a fresh value and then
                        // points the inner copy at it: a:1:{i:0;a:1:{i:0;R:2;}}.
                        if (refIds == null) {
                            refIds = new IdentityHashMap<>();
                        }
                        if (expandedRoots == null) {
                            expandedRoots = new IdentityHashMap<>();
                        }
                        count++;
                        int expandedId = count;
                        expandedRoots.put(arr, expandedId);
                        refIds.put(ref, expandedId);
                        arrayPositions.remove(arr);
                        writePlain(arr);
                        arrayPositions.put(arr, 1);
                        return;
                    }
                    if (refIds == null) {
                        refIds = new IdentityHashMap<>();
                    }
                    refIds.put(ref, arrayId);
                    sb.append("R:").append(arrayId).append(';');
                    return;
                }
            }
            if (refIds == null) {
                refIds = new IdentityHashMap<>();
            }
            refIds.put(ref, count + 1);
            value(inner);
        }

        private void writePlain(Object v) {
            if (v == PhpNull.INSTANCE || v == null) {
                sb.append("N;");
            } else if (v instanceof Boolean b) {
                sb.append("b:").append(b ? 1 : 0).append(';');
            } else if (v instanceof Long l) {
                sb.append("i:").append(l).append(';');
            } else if (v instanceof Double d) {
                sb.append("d:").append(PhpOps.formatDouble(d, 17)).append(';');
            } else if (v instanceof String s) {
                sb.append("s:").append(s.length()).append(":\"").append(s).append("\";");
            } else if (v instanceof PhpArray arr) {
                if (arrayPositions == null) {
                    arrayPositions = new IdentityHashMap<>();
                }
                arrayPositions.put(arr, count);
                sb.append("a:").append(arr.size()).append(":{");
                for (Map.Entry<Object, Object> entry : arr.rawEntries()) {
                    writeKey(entry.getKey());
                    value(entry.getValue());
                }
                sb.append('}');
                arrayPositions.remove(arr);
            } else if (v instanceof PhpResource) {
                sb.append("i:0;");
            } else if (v instanceof dev.phpruntime.runtime.PhpBacktrace.LazyTrace lazy) {
                value(lazy.toPhpArray());
            } else {
                throw new PhpError("serialize(): serializing " + PhpOps.typeName(v) + " is not supported yet");
            }
        }

        private void writeKey(Object key) {
            if (key instanceof Long l) {
                sb.append("i:").append(l).append(';');
            } else {
                String s = PhpOps.toStr(key);
                sb.append("s:").append(s.length()).append(":\"").append(s).append("\";");
            }
        }

        private void writeObject(PhpObject obj) {
            PhpClass cls = obj.phpClass();
            if (obj.nativeState() instanceof EnumCaseState enumCase) {
                String name = enumCase.enumName() + ":" + enumCase.caseName();
                sb.append("E:").append(name.length()).append(":\"").append(name).append("\";");
                return;
            }
            if (!isSerializable(cls)) {
                throw new PhpError("Exception", "Serialization of '" + displayName(cls) + "' is not allowed", null);
            }
            if (isIncomplete(cls)) {
                writeIncomplete(obj);
                return;
            }
            PhpClass.MethodEntry serializeMethod = cls.lookupMethod("__serialize");
            if (serializeMethod != null) {
                Object result = invoke(obj, serializeMethod);
                if (!(result instanceof PhpArray properties)) {
                    throw new PhpError(PhpError.TYPE_ERROR, cls.name() + "::__serialize() must return an array", null);
                }
                writeHeader(cls.name(), properties.size());
                for (Map.Entry<Object, Object> entry : properties.rawEntries()) {
                    writeKey(entry.getKey());
                    value(entry.getValue());
                }
                sb.append('}');
                return;
            }
            if (implementsSerializable(cls)) {
                PhpClass.MethodEntry legacy = cls.lookupMethod("serialize");
                Object result = legacy == null ? PhpNull.INSTANCE : invoke(obj, legacy);
                if (result == PhpNull.INSTANCE || result == null) {
                    sb.append("N;");
                    return;
                }
                if (!(result instanceof String payload)) {
                    throw new PhpError("Exception", cls.name() + "::serialize() must return a string or NULL", null);
                }
                sb.append("C:").append(cls.name().length()).append(":\"").append(cls.name())
                        .append("\":").append(payload.length()).append(":{").append(payload).append('}');
                return;
            }
            PhpClass.MethodEntry sleep = cls.lookupMethod("__sleep");
            if (sleep != null) {
                writeSleep(obj, cls, sleep);
                return;
            }
            List<PropValue> props = allProperties(obj);
            writeHeader(cls.name(), props.size());
            for (PropValue prop : props) {
                writeKey(prop.key());
                value(prop.value());
            }
            sb.append('}');
        }

        private void writeSleep(PhpObject obj, PhpClass cls, PhpClass.MethodEntry sleep) {
            Object result = invoke(obj, sleep);
            if (!(result instanceof PhpArray names)) {
                ctx.warning("serialize(): " + cls.name()
                        + "::__sleep() should return an array only containing the names of instance-variables to serialize", 0);
                sb.append("N;");
                return;
            }
            List<PropValue> selected = new ArrayList<>();
            Map<String, Boolean> seen = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> entry : names.rawEntries()) {
                Object rawName = entry.getValue();
                if (!(rawName instanceof String)) {
                    ctx.warning("serialize(): " + cls.name()
                            + "::__sleep() should return an array only containing the names of instance-variables to serialize", 0);
                }
                String name = PhpOps.toStr(rawName == null ? PhpNull.INSTANCE : rawName);
                PhpClass.InstanceProp prop = findSleepProperty(cls, name);
                if (prop == null) {
                    ctx.warning("serialize(): \"" + name
                            + "\" returned as member variable from __sleep() but does not exist", 0);
                    continue;
                }
                if (seen.containsKey(name)) {
                    ctx.warning("serialize(): \"" + name + "\" is returned from __sleep() multiple times", 0);
                    continue;
                }
                seen.put(name, Boolean.TRUE);
                Object raw = getProperty(obj, prop.storageKey());
                if (raw == PhpClass.UNINITIALIZED) {
                    continue;
                }
                selected.add(new PropValue(serializedKey(cls, prop.storageKey()), unwrap(raw)));
            }
            writeHeader(cls.name(), selected.size());
            for (PropValue prop : selected) {
                writeKey(prop.key());
                value(prop.value());
            }
            sb.append('}');
        }

        private void writeIncomplete(PhpObject obj) {
            String original = incompleteName(obj);
            List<PropValue> props = new ArrayList<>();
            for (PropValue prop : allProperties(obj)) {
                if (!prop.key().equals("__PHP_Incomplete_Class_Name")) {
                    props.add(prop);
                }
            }
            writeHeader(original, props.size());
            for (PropValue prop : props) {
                writeKey(prop.key());
                value(prop.value());
            }
            sb.append('}');
        }

        private void writeHeader(String className, int properties) {
            sb.append("O:").append(className.length()).append(":\"").append(className)
                    .append("\":").append(properties).append(":{");
        }

        private List<PropValue> allProperties(PhpObject obj) {
            Object[] keys = DynamicObject.GetKeyArrayNode.getUncached().execute(obj);
            DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
            List<PropValue> props = new ArrayList<>(keys.length);
            for (Object rawKey : keys) {
                String key = (String) rawKey;
                Object raw = get.execute(obj, key, PhpNull.INSTANCE);
                if (raw == PhpClass.UNINITIALIZED) {
                    continue;
                }
                props.add(new PropValue(serializedKey(obj.phpClass(), key), unwrap(raw)));
            }
            return props;
        }

        /** PHP stores protected properties under their plain name; the wire name is mangled. */
        private static String serializedKey(PhpClass cls, String key) {
            if (key.startsWith("\0")) {
                return key;
            }
            PhpClass.InstanceProp prop = cls.findVisibleProp(key);
            if (prop != null && prop.visibility() == dev.phpruntime.runtime.Visibility.PROTECTED) {
                return "\0*\0" + key;
            }
            return key;
        }

        private static Object unwrap(Object raw) {
            if (raw instanceof dev.phpruntime.runtime.PhpBacktrace.LazyTrace lazy) {
                return lazy.toPhpArray();
            }
            return raw;
        }

        private static Object getProperty(PhpObject obj, String storageKey) {
            return DynamicObject.GetNode.getUncached().execute(obj, storageKey, PhpNull.INSTANCE);
        }

        private static PhpClass.InstanceProp findSleepProperty(PhpClass cls, String name) {
            PhpClass.InstanceProp visible = cls.findVisibleProp(name);
            return visible != null ? visible : cls.findPrivateProp(name);
        }

        private static String incompleteName(PhpObject obj) {
            Object value = getProperty(obj, "__PHP_Incomplete_Class_Name");
            return value instanceof String name ? name : obj.phpClass().name();
        }

        private boolean isSerializable(PhpClass cls) {
            if (cls.template().isAnonymous() || displayName(cls).startsWith("class@anonymous")) {
                return false;
            }
            if (NEVER_SERIALIZABLE.contains(cls.lowerName())) {
                return false;
            }
            if (cls.lookupMethod("__serialize") != null) {
                return true;
            }
            if (cls.isThrowable()) {
                return true;
            }
            if (!cls.isBuiltin()) {
                return true;
            }
            return cls.name().equalsIgnoreCase("stdClass") || isIncomplete(cls);
        }

        private Object invoke(PhpObject obj, PhpClass.MethodEntry entry) {
            if (entry.callTarget() == null) {
                return PhpNull.INSTANCE;
            }
            Object[] args = new Object[2];
            args[0] = obj;
            args[1] = obj.phpClass();
            if (entry.fromTrait()) {
                Object[] withTrait = new Object[3];
                withTrait[0] = args[0];
                withTrait[1] = args[1];
                withTrait[2] = entry.declaringClass();
                args = withTrait;
            }
            return CallArgHelper.unwrapReturn(entry.callTarget().call(args));
        }
    }

    private static String displayName(PhpClass cls) {
        String name = cls.name();
        int nul = name.indexOf('\0');
        return nul < 0 ? name : name.substring(0, nul);
    }

    /** Internal classes that have no __serialize and are never serializable in php. */
    private static final java.util.Set<String> NEVER_SERIALIZABLE = java.util.Set.of(
            "closure", "generator", "mysqli", "mysqli_result", "mysqli_stmt", "mysqli_sql_exception");

    private static boolean isIncomplete(PhpClass cls) {
        return cls.name().equalsIgnoreCase("__PHP_Incomplete_Class");
    }

    private static boolean implementsSerializable(PhpClass cls) {
        for (PhpClass iface : cls.interfaces()) {
            if (iface.name().equalsIgnoreCase("Serializable") || implementsSerializable(iface)) {
                return true;
            }
        }
        PhpClass parent = cls.parent();
        return parent != null && implementsSerializable(parent);
    }

    private record PropValue(String key, Object value) {
    }

    // ------------------------------------------------------------------ options

    private static final class Options {
        boolean allowAll = true;
        boolean denyAll;
        java.util.Set<String> allowed;
        int maxDepth;

        static Options parse(PhpContext ctx, Object raw, boolean provided) {
            Options options = new Options();
            if (!provided) {
                return options;
            }
            if (raw == PhpNull.INSTANCE) {
                throw new PhpError(PhpError.TYPE_ERROR,
                        "unserialize(): Argument #2 ($options) must be of type array, null given", null);
            }
            if (!(raw instanceof PhpArray array)) {
                throw new PhpError(PhpError.TYPE_ERROR, "unserialize(): Argument #2 ($options) must be of type array, "
                        + optionType(raw) + " given", null);
            }
            Object allowed = array.get("allowed_classes");
            if (allowed != null) {
                if (allowed instanceof Boolean b) {
                    options.allowAll = b;
                    options.denyAll = !b;
                } else if (allowed instanceof PhpArray names) {
                    options.allowAll = false;
                    options.allowed = new java.util.HashSet<>();
                    for (Object value : names.values()) {
                        Object item = value instanceof PhpReference ref ? ref.getValue() : value;
                        if (item instanceof String name) {
                            options.allowed.add(PhpStrings.lower(name));
                        } else if (item instanceof PhpObject obj) {
                            throw new PhpError("Object of class " + obj.phpClass().name()
                                    + " could not be converted to string", null);
                        } else {
                            throw new PhpError(PhpError.TYPE_ERROR,
                                    "unserialize(): Option \"allowed_classes\" must be an array of class names, "
                                            + optionType(item) + " given", null);
                        }
                    }
                } else {
                    throw new PhpError(PhpError.TYPE_ERROR,
                            "unserialize(): Option \"allowed_classes\" must be of type array|bool, "
                                    + optionType(allowed) + " given", null);
                }
            }
            Object depth = array.get("max_depth");
            if (depth != null) {
                if (!(depth instanceof Long value)) {
                    throw new PhpError(PhpError.TYPE_ERROR,
                            "unserialize(): Option \"max_depth\" must be of type int, "
                                    + optionType(depth) + " given", null);
                }
                if (value < 0) {
                    throw new PhpError(PhpError.VALUE_ERROR,
                            "unserialize(): Option \"max_depth\" must be greater than or equal to 0", null);
                }
                options.maxDepth = (int) Math.min(value, Integer.MAX_VALUE);
            }
            return options;
        }

        private static String optionType(Object value) {
            if (value instanceof Boolean b) {
                return b ? "true" : "false";
            }
            if (value instanceof PhpObject obj) {
                return obj.phpClass().name();
            }
            return PhpOps.typeName(value);
        }

        boolean allows(String className) {
            if (denyAll) {
                return false;
            }
            return allowAll || allowed == null || allowed.contains(PhpStrings.lower(className));
        }
    }

    // ------------------------------------------------------------------ unserialize

    private interface Binder {
        void bind(PhpReference cell);
    }

    private static final class Entry {
        final Object value;
        Binder binder;
        PhpReference cell;

        Entry(Object value, Binder binder) {
            this.value = value;
            this.binder = binder;
        }
    }

    private static final class Malformed extends RuntimeException {
        final int offset;

        Malformed(int offset) {
            super(null, null, false, false);
            this.offset = offset;
        }
    }

    /** A parsed numeric token: the value clamped to long, and whether it did not fit. */
    private record NumberToken(long value, int colon, boolean negative, boolean overflow) {
    }

    private static final class Unserializer {
        private final PhpContext ctx;
        private final byte[] in;
        private final Options options;
        private final List<Entry> entries = new ArrayList<>();
        private int pos;

        Unserializer(PhpContext ctx, byte[] in, Options options) {
            this.ctx = ctx;
            this.in = in;
            this.options = options;
        }

        Object run() {
            if (in.length == 0) {
                return false;
            }
            try {
                Object value = value(1, true);
                if (pos < in.length) {
                    ctx.warning("unserialize(): Extra data starting at offset " + pos + " of " + in.length + " bytes", 0);
                }
                return value;
            } catch (Malformed e) {
                ctx.warning("unserialize(): Error at offset " + e.offset + " of " + in.length + " bytes", 0);
                return false;
            }
        }

        private void register(Object value, Binder binder) {
            entries.add(new Entry(value, binder));
        }

        private Object value(int depth, boolean track) {
            if (pos >= in.length || in[pos] == '}') {
                ctx.warning("unserialize(): Unexpected end of serialized data", 0);
                throw new Malformed(pos);
            }
            int start = pos;
            char type = (char) in[pos];
            switch (type) {
                case 'N': {
                    if (pos + 1 >= in.length || in[pos + 1] != ';') {
                        throw new Malformed(start);
                    }
                    pos += 2;
                    if (track) {
                        register(PhpNull.INSTANCE, null);
                    }
                    return PhpNull.INSTANCE;
                }
                case 'b':
                    return booleanValue(start, track);
                case 'i':
                    return longValue(start, track);
                case 'd':
                    return doubleValue(start, track);
                case 's':
                    return stringValue(start, track);
                case 'a':
                    return arrayValue(start, depth, track);
                case 'O':
                    return objectValue(start, depth, false, track);
                case 'C':
                    return objectValue(start, depth, true, track);
                case 'E':
                    return enumValue(start, track);
                case 'r':
                    return referenceValue(start, false);
                case 'R':
                    return referenceValue(start, true);
                default:
                    throw new Malformed(start);
            }
        }

        private Object booleanValue(int start, boolean track) {
            expect(start + 1, ':');
            if (start + 3 >= in.length) {
                throw new Malformed(start);
            }
            byte digit = in[start + 2];
            if ((digit != '0' && digit != '1') || in[start + 3] != ';') {
                throw new Malformed(start);
            }
            pos = start + 4;
            Object value = digit == '1';
            if (track) {
                register(value, null);
            }
            return value;
        }

        private Object longValue(int start, boolean track) {
            expect(start + 1, ':');
            NumberToken token = number(start + 2, ';', true);
            if (token == null) {
                throw new Malformed(start);
            }
            long value;
            if (token.overflow()) {
                ctx.warning("unserialize(): Numerical result out of range", 0);
                value = token.negative() ? Long.MIN_VALUE : Long.MAX_VALUE;
            } else {
                value = token.negative() ? -token.value() : token.value();
            }
            pos = token.colon() + 1;
            if (track) {
                register(value, null);
            }
            return value;
        }

        private Object doubleValue(int start, boolean track) {
            expect(start + 1, ':');
            int end = find(start + 2, ';');
            if (end < 0) {
                throw new Malformed(start);
            }
            String text = PhpStrings.of(in, start + 2, end - start - 2);
            Double value;
            switch (text) {
                case "INF" -> value = Double.POSITIVE_INFINITY;
                case "-INF" -> value = Double.NEGATIVE_INFINITY;
                case "NAN" -> value = Double.NaN;
                default -> {
                    try {
                        value = Double.parseDouble(text);
                    } catch (NumberFormatException e) {
                        throw new Malformed(start);
                    }
                }
            }
            pos = end + 1;
            if (track) {
                register(value, null);
            }
            return value;
        }

        private Object stringValue(int start, boolean track) {
            expect(start + 1, ':');
            int lengthStart = start + 2;
            NumberToken token = number(lengthStart, ':', false);
            if (token == null || token.negative()) {
                throw new Malformed(start);
            }
            if (token.overflow() || token.value() > Integer.MAX_VALUE) {
                throw new Malformed(lengthStart);
            }
            int length = (int) token.value();
            int quote = token.colon() + 1;
            if (quote >= in.length || in[quote] != '"') {
                throw new Malformed(start);
            }
            int contentStart = quote + 1;
            if (length > in.length - contentStart) {
                throw new Malformed(lengthStart);
            }
            int closeQuote = contentStart + length;
            if (closeQuote >= in.length || in[closeQuote] != '"') {
                throw new Malformed(closeQuote);
            }
            if (closeQuote + 1 >= in.length || in[closeQuote + 1] != ';') {
                throw new Malformed(closeQuote + 1);
            }
            pos = closeQuote + 2;
            String value = PhpStrings.of(in, contentStart, length);
            if (track) {
                register(value, null);
            }
            return value;
        }

        private Object arrayValue(int start, int depth, boolean track) {
            expect(start + 1, ':');
            NumberToken token = number(start + 2, ':', true);
            if (token == null) {
                throw new Malformed(start);
            }
            if (token.negative()) {
                throw new Malformed(start);
            }
            int brace = token.colon() + 1;
            if (brace >= in.length || in[brace] != '{') {
                throw new Malformed(start);
            }
            if (token.overflow()) {
                ctx.warning("unserialize(): Numerical result out of range", 0);
                throw new Malformed(brace + 1);
            }
            int count = boundedCount(token.value());
            pos = brace + 1;
            if (options.maxDepth > 0 && depth > options.maxDepth) {
                depthError(depth);
            }
            PhpArray array = new PhpArray();
            if (track) {
                register(array, null);
            }
            for (int i = 0; i < count; i++) {
                int keyStart = pos;
                Object key = value(depth + 1, false);
                Object normalized = key instanceof PhpReference ref ? ref.getValue() : key;
                if (!(normalized instanceof Long) && !(normalized instanceof String)) {
                    if (normalized instanceof PhpArray || normalized instanceof PhpObject) {
                        throw new Malformed(keyStart);
                    }
                    throw new Malformed(pos);
                }
                int before = entries.size();
                Object item = value(depth + 1, true);
                array.put(normalized, item);
                setBinder(before, cell -> array.putRef(normalized, cell));
            }
            if (pos >= in.length || in[pos] != '}') {
                throw new Malformed(pos);
            }
            pos++;
            return array;
        }

        private Object objectValue(int start, int depth, boolean custom, boolean track) {
            expect(start + 1, ':');
            int lengthStart = start + 2;
            NumberToken nameToken = number(lengthStart, ':', false);
            if (nameToken == null || nameToken.negative()) {
                throw new Malformed(start);
            }
            if (nameToken.overflow() || nameToken.value() > Integer.MAX_VALUE) {
                throw new Malformed(lengthStart);
            }
            int nameLength = (int) nameToken.value();
            if (nameLength == 0) {
                throw new Malformed(lengthStart);
            }
            int quote = nameToken.colon() + 1;
            if (quote >= in.length || in[quote] != '"') {
                throw new Malformed(start);
            }
            int nameStart = quote + 1;
            if (nameLength > in.length - nameStart) {
                throw new Malformed(lengthStart);
            }
            int nameEnd = nameStart + nameLength;
            if (nameEnd >= in.length || in[nameEnd] != '"') {
                throw new Malformed(nameEnd);
            }
            String className = PhpStrings.of(in, nameStart, nameLength);
            if (!validClassName(className)) {
                throw new Malformed(start);
            }
            if (nameEnd + 1 >= in.length || in[nameEnd + 1] != ':') {
                throw new Malformed(nameEnd + 1);
            }
            int numberStart = nameEnd + 2;
            NumberToken countToken = number(numberStart, ':', true);
            if (countToken == null) {
                throw new Malformed(numberStart);
            }
            if (countToken.negative() && !custom) {
                throw new Malformed(countToken.colon());
            }
            if (countToken.overflow()) {
                ctx.warning("unserialize(): Numerical result out of range", 0);
                if (!custom) {
                    throw new Malformed(countToken.colon());
                }
            }
            int brace = countToken.colon() + 1;
            if (brace >= in.length || in[brace] != '{') {
                throw new Malformed(brace);
            }
            int payloadStart = brace + 1;
            pos = payloadStart;

            PhpClass cls = resolveClass(className);
            if (custom) {
                return customObject(cls, className, start, countToken, payloadStart);
            }
            if (cls == null) {
                cls = ctx.lookupClass("__PHP_Incomplete_Class");
            }
            if (options.maxDepth > 0 && depth > options.maxDepth) {
                depthError(depth);
            }
            PhpObject obj = createObject(cls);
            if (track) {
                register(obj, null);
            }
            if (isIncomplete(cls)) {
                putProperty(obj, "__PHP_Incomplete_Class_Name", className);
            }
            PhpClass.MethodEntry unserializeMethod = cls.lookupMethod("__unserialize");
            PhpArray data = unserializeMethod != null ? new PhpArray() : null;
            int elements = custom ? 0 : boundedCount(countToken.value());
            for (int i = 0; i < elements; i++) {
                Object key = propertyKey();
                if (data != null) {
                    int before = entries.size();
                    Object item = value(depth + 1, true);
                    data.put(key, item);
                    setBinder(before, cell -> data.putRef(key, cell));
                    continue;
                }
                assignProperty(obj, cls, key, depth);
            }
            if (pos >= in.length || in[pos] != '}') {
                throw new Malformed(pos);
            }
            pos++;
            if (data != null) {
                invokeOne(obj, unserializeMethod, new Object[]{data});
            } else {
                PhpClass.MethodEntry wakeup = cls.lookupMethod("__wakeup");
                if (wakeup != null) {
                    invokeOne(obj, wakeup, new Object[0]);
                }
            }
            return obj;
        }

        private Object customObject(PhpClass cls, String className, int start, NumberToken lengthToken, int payloadStart) {
            long required = lengthToken.overflow() ? Long.MAX_VALUE
                    : lengthToken.negative() ? -lengthToken.value() : lengthToken.value();
            int present = in.length - payloadStart;
            if (required < 0) {
                ctx.warning("Insufficient data for unserializing - " + required + " required, "
                        + present + " present", 0);
                throw new Malformed(payloadStart);
            }
            if (required > present - 1) {
                ctx.warning("Insufficient data for unserializing - " + required + " required, "
                        + present + " present", 0);
                throw new Malformed(payloadStart);
            }
            int close = payloadStart + (int) required;
            if (close >= in.length || in[close] != '}') {
                throw new Malformed(close);
            }
            String payload = PhpStrings.of(in, payloadStart, (int) required);
            pos = close + 1;

            if (cls == null) {
                cls = ctx.lookupClass("__PHP_Incomplete_Class");
            }
            PhpObject obj = createObject(cls);
            register(obj, null);
            if (isIncomplete(cls)) {
                putProperty(obj, "__PHP_Incomplete_Class_Name", className);
                ctx.warning("Class __PHP_Incomplete_Class has no unserializer", 0);
                return obj;
            }
            if (!implementsSerializable(cls)) {
                ctx.warning("Class " + cls.name() + " has no unserializer", 0);
                return obj;
            }
            PhpClass.MethodEntry legacy = cls.lookupMethod("unserialize");
            if (legacy != null) {
                invokeOne(obj, legacy, new Object[]{payload});
            }
            return obj;
        }

        private Object enumValue(int start, boolean track) {
            expect(start + 1, ':');
            int lengthStart = start + 2;
            NumberToken token = number(lengthStart, ':', false);
            if (token == null || token.negative()) {
                throw new Malformed(start);
            }
            if (token.overflow() || token.value() > Integer.MAX_VALUE || token.value() == 0) {
                throw new Malformed(lengthStart);
            }
            int length = (int) token.value();
            int quote = token.colon() + 1;
            if (quote >= in.length || in[quote] != '"') {
                throw new Malformed(start);
            }
            int nameStart = quote + 1;
            if (length > in.length - nameStart) {
                throw new Malformed(lengthStart);
            }
            int nameEnd = nameStart + length;
            if (nameEnd >= in.length || in[nameEnd] != '"') {
                throw new Malformed(nameEnd);
            }
            if (nameEnd + 1 >= in.length || in[nameEnd + 1] != ';') {
                throw new Malformed(nameEnd + 1);
            }
            String name = PhpStrings.of(in, nameStart, length);
            pos = nameEnd + 2;
            int colon = name.indexOf(':');
            if (colon < 0) {
                ctx.warning("unserialize(): Invalid enum name '" + name + "' (missing colon)", 0);
                throw new Malformed(start);
            }
            String className = name.substring(0, colon);
            String caseName = name.substring(colon + 1);
            PhpClass cls = ctx.lookupOrAutoloadClass(className);
            if (cls == null) {
                ctx.warning("unserialize(): Class '" + className + "' not found", 0);
                throw new Malformed(start);
            }
            if (!cls.template().isEnum()) {
                ctx.warning("unserialize(): Class '" + cls.name() + "' is not an enum", 0);
                throw new Malformed(start);
            }
            Object value = cls.enumCaseValue(ctx, caseName);
            if (value == null) {
                ctx.warning("unserialize(): Undefined constant " + cls.name() + "::" + caseName, 0);
                throw new Malformed(pos);
            }
            if (track) {
                register(value, null);
            }
            return value;
        }

        private Object referenceValue(int start, boolean strong) {
            expect(start + 1, ':');
            NumberToken token = number(start + 2, ';', false);
            if (token == null || token.negative() || token.overflow()) {
                throw new Malformed(start);
            }
            pos = token.colon() + 1;
            long id = token.value();
            if (id <= 0 || id > entries.size()) {
                throw new Malformed(pos);
            }
            Entry entry = entries.get((int) id - 1);
            if (!strong) {
                if (!(entry.value instanceof PhpObject)) {
                    throw new Malformed(pos);
                }
                return entry.value;
            }
            if (entry.cell == null) {
                entry.cell = new PhpReference(entry.value);
                if (entry.binder != null) {
                    entry.binder.bind(entry.cell);
                }
            }
            return entry.cell;
        }

        private Object propertyKey() {
            int start = pos;
            Object key = value(1, false);
            Object normalized = key instanceof PhpReference ref ? ref.getValue() : key;
            if (normalized instanceof Long l) {
                return PhpOps.toStr(l);
            }
            if (normalized instanceof String s) {
                return s;
            }
            if (normalized instanceof PhpArray || normalized instanceof PhpObject) {
                throw new Malformed(start);
            }
            throw new Malformed(pos);
        }

        private void assignProperty(PhpObject obj, PhpClass cls, Object key, int depth) {
            String name = (String) key;
            if (isIncomplete(cls)) {
                int before = entries.size();
                Object item = value(depth + 1, true);
                setBinder(before, cell -> putProperty(obj, name, cell));
                putProperty(obj, name, item);
                return;
            }
            boolean starMangled = name.startsWith("\0*\0");
            PhpClass.InstanceProp prop = resolveProperty(cls, name);
            if (prop == null && starMangled) {
                throw new Malformed(pos);
            }
            Object item;
            String storageKey;
            if (prop != null) {
                storageKey = prop.storageKey();
                int before = entries.size();
                item = value(depth + 1, true);
                setBinder(before, cell -> putProperty(obj, storageKey, cell));
                if (prop.declaredType() != null) {
                    Object plain = item instanceof PhpReference ref ? ref.getValue() : item;
                    Object checked = prop.declaredType().checkProperty(plain == null ? PhpNull.INSTANCE : plain,
                            ctx, null, true, obj.phpClass(), prop.declaringClass().name(), prop.name());
                    if (!(item instanceof PhpReference)) {
                        item = checked;
                    }
                }
            } else {
                storageKey = name;
                if (!allowsDynamic(cls)) {
                    ctx.deprecated("Creation of dynamic property " + cls.name() + "::$"
                            + displayProperty(name) + " is deprecated", 0);
                }
                int before = entries.size();
                item = value(depth + 1, true);
                setBinder(before, cell -> putProperty(obj, storageKey, cell));
            }
            putProperty(obj, storageKey, item);
        }

        private void setBinder(int entriesBefore, Binder binder) {
            if (entries.size() > entriesBefore) {
                Entry entry = entries.get(entriesBefore);
                if (entry.binder == null) {
                    entry.binder = binder;
                }
            }
        }

        private PhpClass.InstanceProp resolveProperty(PhpClass cls, String name) {
            if (name.startsWith("\0")) {
                int sep = name.indexOf('\0', 1);
                if (sep < 0) {
                    return null;
                }
                String declaring = name.substring(1, sep);
                String prop = name.substring(sep + 1);
                if (declaring.equals("*")) {
                    return resolvePlain(cls, prop);
                }
                if (!PhpStrings.equalsIgnoreCase(declaring, cls.name())) {
                    boolean inHierarchy = false;
                    for (PhpClass c = cls; c != null; c = c.parent()) {
                        if (PhpStrings.equalsIgnoreCase(c.name(), declaring)) {
                            inHierarchy = true;
                            break;
                        }
                    }
                    return inHierarchy ? cls.findPrivatePropDeclaredIn(declaring, prop) : null;
                }
                return resolvePlain(cls, prop);
            }
            return resolvePlain(cls, name);
        }

        private static PhpClass.InstanceProp resolvePlain(PhpClass cls, String name) {
            PhpClass.InstanceProp visible = cls.findVisibleProp(name);
            return visible != null ? visible : cls.findPrivateProp(name);
        }

        private boolean allowsDynamic(PhpClass cls) {
            if (isIncomplete(cls)) {
                return true;
            }
            for (PhpClass c = cls; c != null; c = c.parent()) {
                if (c.name().equalsIgnoreCase("stdClass")) {
                    return true;
                }
                for (PhpAttribute attribute : c.template().attributes()) {
                    String name = attribute.name();
                    int sep = name.lastIndexOf('\\');
                    if ((sep < 0 ? name : name.substring(sep + 1)).equalsIgnoreCase("AllowDynamicProperties")) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static String displayProperty(String name) {
            int nul = name.lastIndexOf('\0');
            return nul < 0 ? name : name.substring(nul + 1);
        }

        private PhpClass resolveClass(String className) {
            if (options.denyAll) {
                return null;
            }
            PhpClass cls = ctx.lookupOrAutoloadClass(className);
            if (cls == null) {
                return tryCallback(className);
            }
            if (!options.allows(cls.name())) {
                return null;
            }
            return cls;
        }

        private PhpClass tryCallback(String className) {
            String callback = ContextState.of(ctx).unserializeCallbackFunc;
            if (callback == null || callback.isEmpty()) {
                return null;
            }
            PhpCallable callable = PhpCallable.resolveOrNull(ctx, callback);
            if (callable == null) {
                throw new PhpError("Invalid callback " + callback + ", function \"" + callback
                        + "\" not found or invalid function name", null);
            }
            callable.call(ctx, null, null, className);
            PhpClass cls = ctx.lookupOrAutoloadClass(className);
            if (cls == null) {
                ctx.warning("unserialize(): Function " + callback
                        + "() hasn't defined the class it was called for", 0);
                return null;
            }
            return options.allows(cls.name()) ? cls : null;
        }

        private PhpObject createObject(PhpClass cls) {
            if (cls == null) {
                cls = ctx.lookupClass("__PHP_Incomplete_Class");
            }
            if (cls.isInterface()) {
                throw new PhpError("Cannot instantiate interface " + cls.name());
            }
            if (cls.isTrait()) {
                throw new PhpError("Cannot instantiate trait " + cls.name());
            }
            if (cls.template().isEnum()) {
                throw new PhpError("Cannot instantiate enum " + cls.name());
            }
            if (cls.isAbstract()) {
                throw new PhpError("Cannot instantiate abstract class " + cls.name());
            }
            if ("Closure".equalsIgnoreCase(cls.name())) {
                throw new PhpError("Instantiation of class Closure is not allowed");
            }
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            cls.populateDefaults(obj);
            return obj;
        }

        private Object invokeOne(PhpObject obj, PhpClass.MethodEntry entry, Object[] args) {
            if (entry.callTarget() == null) {
                return PhpNull.INSTANCE;
            }
            Object[] callArgs = new Object[2 + args.length];
            callArgs[0] = obj;
            callArgs[1] = obj.phpClass();
            System.arraycopy(args, 0, callArgs, 2, args.length);
            if (entry.fromTrait()) {
                Object[] withTrait = new Object[callArgs.length + 1];
                withTrait[0] = callArgs[0];
                withTrait[1] = callArgs[1];
                withTrait[2] = entry.declaringClass();
                System.arraycopy(callArgs, 2, withTrait, 3, args.length);
                callArgs = withTrait;
            }
            return CallArgHelper.unwrapReturn(entry.callTarget().call(callArgs));
        }

        private static void putProperty(PhpObject obj, String key, Object value) {
            DynamicObject.PutNode.getUncached().execute(obj, key, value);
        }

        private void expect(int at, char c) {
            if (at >= in.length || in[at] != c) {
                throw new Malformed(at);
            }
        }

        private int find(int from, char c) {
            for (int i = from; i < in.length; i++) {
                if (in[i] == c) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * Scans an optional sign, decimal digits and the terminating character. Returns null when
         * the token is not numeric. Overflow saturates to the signed range.
         */
        private NumberToken number(int from, char terminator, boolean allowPlus) {
            int i = from;
            boolean negative = false;
            if (i < in.length && (in[i] == '-' || (allowPlus && in[i] == '+'))) {
                negative = in[i] == '-';
                i++;
            }
            int digitsStart = i;
            java.math.BigInteger magnitude = java.math.BigInteger.ZERO;
            while (i < in.length && in[i] >= '0' && in[i] <= '9') {
                magnitude = magnitude.multiply(java.math.BigInteger.TEN)
                        .add(java.math.BigInteger.valueOf(in[i] - '0'));
                i++;
            }
            if (i == digitsStart || i >= in.length || in[i] != terminator) {
                return null;
            }
            java.math.BigInteger limit = java.math.BigInteger.valueOf(Long.MAX_VALUE);
            if (negative) {
                limit = limit.add(java.math.BigInteger.ONE);
            }
            boolean overflow = magnitude.compareTo(limit) > 0;
            long value;
            if (overflow) {
                value = Long.MAX_VALUE;
            } else {
                value = magnitude.longValue();
            }
            return new NumberToken(value, i, negative, overflow);
        }

        private int boundedCount(long value) {
            return (int) Math.min(value, in.length);
        }

        private static boolean validClassName(String name) {
            if (name.isEmpty() || name.charAt(0) == '\\') {
                return false;
            }
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '_' || c == '\\' || c >= 0x80;
                if (!ok) {
                    return false;
                }
            }
            return true;
        }

        private void depthError(int depth) {
            ctx.warning("unserialize(): Maximum depth of " + options.maxDepth
                    + " exceeded. The depth limit can be changed using the max_depth unserialize() option"
                    + " or the unserialize_max_depth ini setting", 0);
            throw new Malformed(pos);
        }
    }
}
