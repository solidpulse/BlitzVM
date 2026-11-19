package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.PhpContext;
import dev.phpruntime.builtins.BuiltinParams;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.nodes.var.LocalVarNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared argument preparation helpers for by-reference parameters, argument unpacking ({@code ...$args}),
 * and named arguments ({@code name: val}) across all call sites.
 */
public final class CallArgHelper {

    private CallArgHelper() {
    }

    public static Object unwrapReturn(Object res) {
        if (res instanceof PhpReference ref) {
            return unwrapReturnedRef(ref);
        }
        return res;
    }

    /**
     * Only functions returning by reference return a cell. Out of line, so the unwrap and the
     * append-buffer conversion are not compiled into every call's return.
     */
    @TruffleBoundary
    private static Object unwrapReturnedRef(PhpReference ref) {
        Object v = ref.getValue();
        return v == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(v);
    }

    /** Evaluates one positional argument for a user function parameter that may be by-reference. */
    @TruffleBoundary
    public static PhpError cannotPassByRef(PhpFunctionRootNode fnRoot, int paramIdx, Node site) {
        String fnDesc = fnRoot != null ? fnRoot.qualifiedCallableName() : "unknown";
        String pName = fnRoot != null ? fnRoot.paramName(paramIdx) : null;
        String suffix = pName != null ? " ($" + pName + ")" : "";
        return new PhpError(fnDesc + "(): Argument #" + (paramIdx + 1) + suffix + " could not be passed by reference", site);
    }

    /**
     * Evaluates a call site that has spread ({@code ...$x}) or named ({@code k: v}) arguments
     * for a target {@link RootCallTarget}, packing prefix slots + resolved parameter values.
     */
    public record RawCallArgs(List<Object> positional, List<LValueNode> posRefTargets,
                              List<Object[]> posRefKeys, List<PhpNode> posNodes,
                              Map<String, Object> named, Map<String, LValueNode> namedRefTargets,
                              Map<String, Object[]> namedRefKeys, Map<String, PhpNode> namedNodes) {
    }

    /** An empty mutable argument list for bytecode, which evaluates one argument per operation. */
    public static RawCallArgs newRawCallArgs() {
        return new RawCallArgs(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    /** Exploded so each argument and ref-target node is a PE constant (no virtual calls, no frame escape). */
    @TruffleBoundary
    private static <K, V> Map<K, V> newMap() {
        return new LinkedHashMap<>();
    }

    @TruffleBoundary
    private static <T> void addList(List<T> list, T item) {
        list.add(item);
    }

    @TruffleBoundary
    private static <K, V> void putMap(Map<K, V> map, K key, V value) {
        map.put(key, value);
    }

    @TruffleBoundary
    public static void checkDuplicateNamed(Map<String, Object> named, String name, Node site) {
        if (named.containsKey(name)) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("Named parameter $" + name + " overwrites previous argument", site);
        }
    }

    @TruffleBoundary
    public static int findParamIndex(PhpFunctionRootNode fnRoot, String name) {
        String[] names = fnRoot.paramNames();
        int normal = fnRoot.normalParamCount();
        for (int i = 0; i < normal; i++) {
            if (name.equals(names[i])) {
                return i;
            }
        }
        return -1;
    }

    @TruffleBoundary
    public static void unpackCallArg(Object unpacked, PhpArray writableRefSource,
                                      List<Object> positional, List<LValueNode> posRefs,
                                      List<Object[]> posKeys, List<PhpNode> posNodes,
                                      Map<String, Object> named, boolean alreadySeenNamed, Node site) {
        if (unpacked instanceof dev.phpruntime.runtime.PhpObject obj
                && dev.phpruntime.runtime.SplInterfaces.has(obj, dev.phpruntime.runtime.SplInterfaces.TRAVERSABLE)) {
            boolean seenNamed = alreadySeenNamed || !named.isEmpty();
            for (var entry : dev.phpruntime.runtime.SplIterators.entries(obj, site)) {
                Object key = entry.getKey();
                Object value = PhpOps.copyOnAssign(entry.getValue());
                if (key instanceof String s) {
                    seenNamed = true;
                    checkDuplicateNamed(named, s, site);
                    named.put(s, value);
                } else if (key instanceof Long) {
                    if (seenNamed) {
                        com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                        throw new PhpError("Cannot use positional argument after named argument during unpacking", site);
                    }
                    positional.add(value);
                    posRefs.add(null);
                    posKeys.add(null);
                    posNodes.add(null);
                } else {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError(PhpError.TYPE_ERROR, "Keys must be of type int|string during argument unpacking", site);
                }
            }
            return;
        }
        if (!(unpacked instanceof PhpArray src)) {
            String given = unpacked == null || unpacked == PhpNull.INSTANCE ? "null" : PhpOps.typeName(unpacked);
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.TYPE_ERROR, "Only arrays and Traversables can be unpacked, " + given + " given", site);
        }
        boolean seenNamed = alreadySeenNamed || !named.isEmpty();
        for (Object k : src.keys()) {
            if (k instanceof String s) {
                seenNamed = true;
                if (named.containsKey(s)) {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError("Named parameter $" + s + " overwrites previous argument", site);
                }
                Object val = writableRefSource != null ? writableRefSource.obtainElementRef(k)
                        : (src.getRaw(k) instanceof PhpReference ref ? ref : PhpOps.copyOnAssign(src.get(k)));
                named.put(s, val);
            } else {
                if (seenNamed) {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError("Cannot use positional argument after named argument during unpacking", site);
                }
                Object val = writableRefSource != null ? writableRefSource.obtainElementRef(k)
                        : (src.getRaw(k) instanceof PhpReference ref ? ref : PhpOps.copyOnAssign(src.get(k)));
                positional.add(val);
                posRefs.add(null);
                posKeys.add(null);
                posNodes.add(null);
            }
        }
    }

    @TruffleBoundary
    public static Object[] bindToFunction(Object[] prefix, RawCallArgs raw, PhpFunctionRootNode fnRoot, Node site) {
        List<Object> pos = raw.positional();
        Map<String, Object> named = raw.named();
        if (fnRoot == null || (named == null || named.isEmpty())) {
            Object[] out = new Object[prefix.length + pos.size()];
            System.arraycopy(prefix, 0, out, 0, prefix.length);
            for (int i = 0; i < pos.size(); i++) {
                out[prefix.length + i] = pos.get(i);
            }
            return out;
        }
        int normal = fnRoot.normalParamCount();
        boolean variadic = fnRoot.isVariadic();
        List<Object> slots = new ArrayList<>(Math.max(normal, pos.size()));
        for (int i = 0; i < pos.size(); i++) {
            slots.add(pos.get(i));
        }
        PhpArray extraNamed = null;
        for (Map.Entry<String, Object> e : named.entrySet()) {
            String pName = e.getKey();
            int idx = findParamIndex(fnRoot, pName);
            if (idx >= 0) {
                while (slots.size() <= idx) {
                    slots.add(PhpFunctionRootNode.UNPASSED);
                }
                if (slots.get(idx) != PhpFunctionRootNode.UNPASSED) {
                    com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                    throw new PhpError("Named parameter $" + pName + " overwrites previous argument", site);
                }
                slots.set(idx, e.getValue());
            } else if (variadic) {
                if (extraNamed == null) {
                    extraNamed = new PhpArray();
                }
                if (e.getValue() instanceof PhpReference ref && fnRoot.isParamByRef(normal)) {
                    extraNamed.putRef(pName, ref);
                } else {
                    Object v = e.getValue() instanceof PhpReference ref ? ref.getValue() : e.getValue();
                    extraNamed.put(pName, PhpOps.copyOnAssign(v));
                }
            } else {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                throw new PhpError("Unknown named parameter $" + pName, site);
            }
        }
        if (extraNamed != null) {
            while (slots.size() < normal) {
                slots.add(PhpFunctionRootNode.UNPASSED);
            }
            slots.add(new PhpFunctionRootNode.NamedVariadic(extraNamed));
        }
        Object[] out = new Object[prefix.length + slots.size()];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        for (int i = 0; i < slots.size(); i++) {
            out[prefix.length + i] = slots.get(i);
        }
        return out;
    }

    /** Evaluates spread/named arguments for a builtin function, handling by-reference writeback. */
    /** Binds and calls a builtin on already-evaluated raw arguments. */
    public static Object callComplexBuiltinRaw(VirtualFrame frame, Builtins.Def def, RawCallArgs raw, Node site) {
        BuiltinBound bound = hasNamed(raw.named())
                ? bindNamedBuiltin(def, raw, site)
                : positionalBuiltinBound(raw);
        Object[] values = bound.values();
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof PhpReference ref && !def.isRef(i)) {
                values[i] = ref.getValue() == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(ref.getValue());
            } else if (values[i] instanceof PhpReference ref && def.isRef(i)) {
                values[i] = ref.getValue() == null ? PhpNull.INSTANCE : ref.getValue();
            }
        }
        CallNode.checkArity(def, values.length, site);
        Object result = invokeBuiltin(def, values, site);
        if (def.refMask() != 0) {
            // Folds away for builtins without by-ref parameters when def is a PE constant.
            writeBackBuiltinRefs(frame, def, bound);
        }
        return result;
    }

    /** Argument values in parameter order, plus where each by-reference argument came from. */
    private record BuiltinBound(Object[] values, Object[] originals, LValueNode[] lvs, Object[][] keys) {
    }

    @TruffleBoundary
    private static BuiltinBound positionalBuiltinBound(RawCallArgs raw) {
        Object[] values = toObjectArray(raw.positional());
        return new BuiltinBound(values, values.clone(), toLValueArray(raw.posRefTargets()),
                toKeysArray(raw.posRefKeys()));
    }

    /**
     * Places named arguments in their parameter positions, as php does: unknown names first,
     * then a positional argument's position may not be overwritten, then gaps before a named
     * argument get their defaults. A named argument for a by-reference parameter must have a
     * place to write back to.
     */
    @TruffleBoundary
    private static BuiltinBound bindNamedBuiltin(Builtins.Def def, RawCallArgs raw, Node site) {
        List<Object> pos = raw.positional();
        Map<String, Object> named = raw.named();
        int passed = pos.size();
        BuiltinParams.Param[] params = BuiltinParams.forName(def.name());
        if (params == null) {
            throw new PhpError("Unknown named parameter $" + firstKey(named), site);
        }
        int normal = params.length;
        while (normal > 0 && params[normal - 1].variadic()) {
            normal--;
        }
        boolean variadic = normal < params.length;
        Map<Integer, String> namedAt = new LinkedHashMap<>();
        int highest = passed - 1;
        for (Map.Entry<String, Object> e : named.entrySet()) {
            String name = e.getKey();
            int idx = namedParamIndex(params, normal, name);
            if (idx < 0) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                if (variadic) {
                    throw new PhpError(PhpError.ARGUMENT_COUNT,
                            def.name() + "() does not accept unknown named parameters", site);
                }
                throw new PhpError("Unknown named parameter $" + name, site);
            }
            if (idx < passed) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                throw new PhpError("Named parameter $" + name + " overwrites previous argument", site);
            }
            Object lv = raw.namedRefTargets() == null ? null : raw.namedRefTargets().get(name);
            if (def.isRef(idx) && lv == null && !(e.getValue() instanceof PhpReference)) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                throw new PhpError(def.name() + "(): Argument #" + (idx + 1) + " ($" + params[idx].name()
                        + ") could not be passed by reference", site);
            }
            namedAt.put(idx, name);
            highest = Math.max(highest, idx);
        }
        int length = Math.max(passed, highest + 1);
        Object[] values = new Object[length];
        Object[] originals = new Object[length];
        LValueNode[] lvs = new LValueNode[length];
        Object[][] keys = new Object[length][];
        for (int i = 0; i < passed; i++) {
            values[i] = pos.get(i);
            originals[i] = values[i];
            lvs[i] = raw.posRefTargets().get(i);
            keys[i] = raw.posRefKeys().get(i);
        }
        for (int i = passed; i < length; i++) {
            String name = namedAt.get(i);
            if (name != null) {
                values[i] = named.get(name);
                originals[i] = values[i];
                lvs[i] = raw.namedRefTargets() != null ? raw.namedRefTargets().get(name) : null;
                keys[i] = raw.namedRefKeys() != null ? raw.namedRefKeys().get(name) : null;
                continue;
            }
            BuiltinParams.Param param = params[i];
            if (!param.hasDefault()) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                if (param.optional()) {
                    throw new PhpError(PhpError.ARGUMENT_COUNT, def.name() + "(): Argument #" + (i + 1) + " ($"
                            + param.name() + ") must be passed explicitly, because the default value is not known", site);
                }
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        def.name() + "(): Argument #" + (i + 1) + " ($" + param.name() + ") not passed", site);
            }
            Object defaultValue = param.defaultValue();
            values[i] = defaultValue == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(defaultValue);
        }
        return new BuiltinBound(values, originals, lvs, keys);
    }

    private static int namedParamIndex(BuiltinParams.Param[] params, int normal, String name) {
        for (int i = 0; i < normal; i++) {
            if (params[i].name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** The array a {@code __call}/{@code __callStatic} receives from evaluated raw arguments. */
    @TruffleBoundary
    public static PhpArray magicArray(RawCallArgs raw) {
        PhpArray arr = new PhpArray();
        for (Object p : raw.positional()) {
            arr.append(PhpOps.copyOnAssign(p));
        }
        if (raw.named() != null) {
            for (Map.Entry<String, Object> e : raw.named().entrySet()) {
                arr.put(e.getKey(), PhpOps.copyOnAssign(e.getValue()));
            }
        }
        return arr;
    }

    @TruffleBoundary
    public static String firstKey(Map<String, Object> map) {
        return map.keySet().iterator().next();
    }

    @TruffleBoundary
    private static boolean hasNamed(Map<String, Object> named) {
        return named != null && !named.isEmpty();
    }

    @TruffleBoundary
    private static Object[] toObjectArray(List<Object> list) {
        return list.toArray(new Object[0]);
    }

    @TruffleBoundary
    private static LValueNode[] toLValueArray(List<LValueNode> list) {
        return list.toArray(new LValueNode[0]);
    }

    @TruffleBoundary
    private static Object[][] toKeysArray(List<Object[]> list) {
        return list.toArray(new Object[0][]);
    }

    private static void writeBackBuiltinRefs(VirtualFrame frame, Builtins.Def def, BuiltinBound bound) {
        Object[] values = bound.values();
        for (int i = 0; i < values.length; i++) {
            if (!def.isRef(i)) {
                continue;
            }
            Object orig = bound.originals()[i];
            if (orig instanceof PhpReference ref) {
                ref.setValue(values[i]);
            } else if (bound.lvs()[i] != null) {
                bound.lvs()[i].write(frame, bound.keys()[i], values[i]);
            }
        }
    }

    @TruffleBoundary
    private static Object invokeBuiltin(Builtins.Def def, Object[] values, Node site) {
        PhpContext ctx = PhpContext.get(site);
        Node prev = ctx.currentBuiltinCallSite();
        ctx.setCurrentBuiltinCallSite(site);
        try {
            return def.call(ctx, values);
        } catch (PhpError e) {
            throw e.withTrace(dev.phpruntime.runtime.PhpBacktrace.captureFromBuiltin(site, def.name())).at(site);
        } finally {
            ctx.setCurrentBuiltinCallSite(prev);
        }
    }
}
