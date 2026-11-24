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

    /**
     * {@link #findParamIndex} lookups: the generic named-binding path, exposed for tests. Counted
     * only with assertions enabled (the test runs), so serving threads do not share the line.
     */
    private static final java.util.concurrent.atomic.AtomicLong GENERIC_NAMED_BINDINGS =
            new java.util.concurrent.atomic.AtomicLong();

    public static long genericNamedBindingCount() {
        return GENERIC_NAMED_BINDINGS.get();
    }

    public static void resetGenericNamedBindingCount() {
        GENERIC_NAMED_BINDINGS.set(0);
    }

    private static boolean countGenericNamedBinding() {
        GENERIC_NAMED_BINDINGS.incrementAndGet();
        return true;
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
        return cannotPassByRef(fnDesc, pName, paramIdx, site);
    }

    /** The same error for a Java-implemented method, whose own entry supplies the description. */
    @TruffleBoundary
    public static PhpError cannotPassByRef(String fnDesc, String pName, int paramIdx, Node site) {
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

    /** Already-evaluated arguments, with the reference side tables left to the caller. */
    public static RawCallArgs newRawCallArgs(List<Object> positional, Map<String, Object> named) {
        return new RawCallArgs(new ArrayList<>(positional), List.of(), List.of(), List.of(),
                new LinkedHashMap<>(named), null, null, null);
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
        assert countGenericNamedBinding();
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

    /**
     * Named arguments for an internal method: the parameter names come from the class
     * template signature, since there is no guest function frame to read them from. A method
     * php forwards named arguments through (the Reflection invoke and newInstance methods,
     * Closure::call) gets the undeclared names appended as a {@link ForwardedNamedArgs}.
     */
    @TruffleBoundary
    public static Object[] bindToInternal(Object[] prefix, RawCallArgs raw,
                                          dev.phpruntime.runtime.PhpClass.MethodEntry entry, Node site) {
        List<String> paramNames = entry == null ? List.of() : entry.parameterNames();
        int variadic = entry == null ? -1 : entry.variadicIndex();
        boolean forwards = dev.phpruntime.runtime.NamedForwarding.forwardsMethod(entry);
        List<Object> pos = raw.positional();
        Map<String, Object> named = raw.named();
        if (named == null || named.isEmpty()) {
            Object[] out = new Object[prefix.length + pos.size()];
            System.arraycopy(prefix, 0, out, 0, prefix.length);
            for (int i = 0; i < pos.size(); i++) {
                out[prefix.length + i] = pos.get(i);
            }
            return out;
        }
        if (paramNames.isEmpty() && !forwards) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("Unknown named parameter $" + firstKey(named), site);
        }
        List<Object> slots = new ArrayList<>(pos);
        Map<String, Object> forwarded = null;
        for (Map.Entry<String, Object> e : named.entrySet()) {
            int idx = -1;
            int normal = variadic >= 0 ? variadic : paramNames.size();
            for (int i = 0; i < normal; i++) {
                if (paramNames.get(i).equals(e.getKey())) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                if (forwards) {
                    if (forwarded == null) {
                        forwarded = new LinkedHashMap<>();
                    }
                    forwarded.put(e.getKey(), e.getValue());
                    continue;
                }
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                throw new PhpError("Unknown named parameter $" + e.getKey(), site);
            }
            if (idx < pos.size()) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                throw new PhpError("Named parameter $" + e.getKey() + " overwrites previous argument", site);
            }
            while (slots.size() <= idx) {
                slots.add(dev.phpruntime.runtime.PhpNull.INSTANCE);
            }
            slots.set(idx, e.getValue());
        }
        if (forwarded != null) {
            slots.add(new dev.phpruntime.runtime.ForwardedNamedArgs(forwarded));
        }
        Object[] out = new Object[prefix.length + slots.size()];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        for (int i = 0; i < slots.size(); i++) {
            out[prefix.length + i] = slots.get(i);
        }
        return out;
    }

    /**
     * Binds forwarded named arguments to a guest function, as php's forwarding builtins do:
     * a named argument for a by-reference parameter has no place to write back, so it gets a
     * temporary and the "must be passed by reference, value given" warning.
     */
    @TruffleBoundary
    public static Object[] bindForwardedToFunction(PhpContext ctx, PhpFunctionRootNode fnRoot, Object[] prefix,
                                                   List<Object> positional, Map<String, Object> named, Node site) {
        List<Object> pos = new ArrayList<>(positional);
        Map<String, Object> forwarded = new LinkedHashMap<>(named);
        int normal = fnRoot.normalParamCount();
        for (int i = 0; i < pos.size(); i++) {
            if (fnRoot.isParamByRef(i) && !(pos.get(i) instanceof PhpReference)) {
                warningValueForRef(ctx, fnRoot, i, i < normal ? fnRoot.paramName(i) : null, site);
                pos.set(i, new PhpReference(PhpOps.copyOnAssign(pos.get(i))));
            }
        }
        for (Map.Entry<String, Object> e : forwarded.entrySet()) {
            int idx = findParamIndex(fnRoot, e.getKey());
            boolean byRef = idx >= 0 ? fnRoot.isParamByRef(idx)
                    : fnRoot.isVariadic() && fnRoot.isParamByRef(normal);
            if (byRef && !(e.getValue() instanceof PhpReference)) {
                warningValueForRef(ctx, fnRoot, idx >= 0 ? idx : normal,
                        idx >= 0 ? fnRoot.paramName(idx) : e.getKey(), site);
                e.setValue(new PhpReference(PhpOps.copyOnAssign(e.getValue())));
            }
        }
        RawCallArgs raw = new RawCallArgs(pos, List.of(), List.of(), List.of(), forwarded, null, null, null);
        return bindToFunction(prefix, raw, fnRoot, site);
    }

    /** Binds and calls a builtin with forwarded named arguments, warning for by-reference ones. */
    @TruffleBoundary
    public static Object callForwardedBuiltin(PhpContext ctx, Builtins.Def def, List<Object> positional,
                                              Map<String, Object> named, Node site) {
        List<Object> pos = new ArrayList<>(positional);
        Map<String, Object> forwarded = new LinkedHashMap<>(named);
        BuiltinParams.Param[] params = BuiltinParams.forName(def.name());
        if (params != null) {
            int normal = params.length;
            while (normal > 0 && params[normal - 1].variadic()) {
                normal--;
            }
            for (int i = 0; i < pos.size(); i++) {
                if (def.isRef(i) && !(pos.get(i) instanceof PhpReference)) {
                    String name = i < normal ? params[i].name() : null;
                    ctx.warning(def.name() + "(): Argument #" + (i + 1) + (name != null ? " ($" + name + ")" : "")
                            + " must be passed by reference, value given", lineOf(site));
                    pos.set(i, new PhpReference(PhpOps.copyOnAssign(pos.get(i))));
                }
            }
            for (Map.Entry<String, Object> e : forwarded.entrySet()) {
                int idx = -1;
                for (int i = 0; i < normal; i++) {
                    if (params[i].name().equals(e.getKey())) {
                        idx = i;
                        break;
                    }
                }
                if (idx >= 0 && def.isRef(idx) && !(e.getValue() instanceof PhpReference)) {
                    ctx.warning(def.name() + "(): Argument #" + (idx + 1) + " ($" + params[idx].name()
                            + ") must be passed by reference, value given", lineOf(site));
                    e.setValue(new PhpReference(PhpOps.copyOnAssign(e.getValue())));
                }
            }
        }
        LValueNode[] noRefs = null;
        Object[][] noKeys = null;
        RawCallArgs raw = new RawCallArgs(pos, List.of(), List.of(), List.of(), forwarded, null, null, null);
        BuiltinBound bound = bindNamedBuiltin(def, raw, site);
        if (def.refMask() != 0) {
            noRefs = new LValueNode[bound.values().length];
            noKeys = new Object[bound.values().length][];
        }
        return callBoundBuiltin(null, def, bound.values(), bound.originals(), noRefs, noKeys, site);
    }

    private static void warningValueForRef(PhpContext ctx, PhpFunctionRootNode fnRoot, int index,
                                           String name, Node site) {
        String desc = fnRoot.qualifiedCallableName() + "(): Argument #" + (index + 1)
                + (name != null ? " ($" + name + ")" : "");
        ctx.warning(desc + " must be passed by reference, value given", lineOf(site));
    }

    private static int lineOf(Node site) {
        if (site == null) {
            return 0;
        }
        com.oracle.truffle.api.source.SourceSection section = site.getSourceSection();
        return section != null ? section.getStartLine() : 0;
    }

    /** Evaluates spread/named arguments for a builtin function, handling by-reference writeback. */
    public static Object callComplexBuiltinRaw(VirtualFrame frame, Builtins.Def def, RawCallArgs raw, Node site) {
        BuiltinBound bound = hasNamed(raw.named())
                ? bindNamedBuiltin(def, raw, site)
                : positionalBuiltinBound(raw);
        return callBoundBuiltin(frame, def, bound.values(), bound.originals(), bound.lvs(), bound.keys(), site);
    }

    /**
     * Invokes a builtin on arguments already in parameter order. {@code originals}, {@code lvs}
     * and {@code keys} describe by-reference arguments: the value before unwrapping and where a
     * result is written back. They are null for builtins without by-reference parameters.
     */
    public static Object callBoundBuiltin(VirtualFrame frame, Builtins.Def def, Object[] values, Object[] originals,
                                          LValueNode[] lvs, Object[][] keys, Node site) {
        dev.phpruntime.runtime.ForwardedNamedArgs forwarded = null;
        if (values.length > 0 && values[values.length - 1] instanceof dev.phpruntime.runtime.ForwardedNamedArgs bag) {
            forwarded = bag;
            values = java.util.Arrays.copyOf(values, values.length - 1);
            if (originals != null) {
                originals = java.util.Arrays.copyOf(originals, originals.length - 1);
            }
            if (lvs != null) {
                lvs = java.util.Arrays.copyOf(lvs, lvs.length - 1);
            }
            if (keys != null) {
                keys = java.util.Arrays.copyOf(keys, keys.length - 1);
            }
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof PhpReference ref && !def.isRef(i)) {
                values[i] = ref.getValue() == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(ref.getValue());
            } else if (values[i] instanceof PhpReference ref && def.isRef(i)) {
                values[i] = ref.getValue() == null ? PhpNull.INSTANCE : ref.getValue();
            }
        }
        CallNode.checkArity(def, values.length, site);
        PhpContext ctx = forwarded == null ? null : PhpContext.get(site);
        dev.phpruntime.runtime.ForwardedNamedArgs previous = null;
        if (ctx != null) {
            previous = ctx.forwardedNamed();
            ctx.setForwardedNamed(forwarded);
        }
        Object result;
        try {
            result = invokeBuiltin(def, values, site);
        } finally {
            if (ctx != null) {
                ctx.setForwardedNamed(previous);
            }
        }
        if (def.refMask() != 0) {
            // Folds away for builtins without by-ref parameters when def is a PE constant.
            writeBackBuiltinRefs(frame, def, values, originals, lvs, keys);
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
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            if (def.maxArgs() < 0) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        "Internal function " + def.name() + "() does not accept named variadic arguments", site);
            }
            throw new PhpError("Unknown named parameter $" + firstKey(named), site);
        }
        int normal = params.length;
        while (normal > 0 && params[normal - 1].variadic()) {
            normal--;
        }
        boolean variadic = normal < params.length;
        boolean forwards = dev.phpruntime.runtime.NamedForwarding.forwardsFunction(def.name());
        Map<String, Object> forwarded = null;
        Map<Integer, String> namedAt = new LinkedHashMap<>();
        int highest = passed - 1;
        for (Map.Entry<String, Object> e : named.entrySet()) {
            String name = e.getKey();
            int idx = namedParamIndex(params, normal, name);
            if (idx < 0) {
                if (forwards && variadic) {
                    if (forwarded == null) {
                        forwarded = new LinkedHashMap<>();
                    }
                    forwarded.put(name, e.getValue());
                    continue;
                }
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
                if (variadic) {
                    throw new PhpError(PhpError.ARGUMENT_COUNT,
                            "Internal function " + def.name() + "() does not accept named variadic arguments", site);
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
            lvs[i] = raw.posRefTargets() != null && i < raw.posRefTargets().size()
                    ? raw.posRefTargets().get(i) : null;
            keys[i] = raw.posRefKeys() != null && i < raw.posRefKeys().size()
                    ? raw.posRefKeys().get(i) : null;
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
        if (forwarded != null) {
            int at = values.length;
            values = java.util.Arrays.copyOf(values, at + 1);
            originals = java.util.Arrays.copyOf(originals, at + 1);
            lvs = java.util.Arrays.copyOf(lvs, at + 1);
            keys = java.util.Arrays.copyOf(keys, at + 1);
            values[at] = new dev.phpruntime.runtime.ForwardedNamedArgs(forwarded);
            originals[at] = values[at];
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

    private static void writeBackBuiltinRefs(VirtualFrame frame, Builtins.Def def, Object[] values,
                                             Object[] originals, LValueNode[] lvs, Object[][] keys) {
        for (int i = 0; i < values.length; i++) {
            if (!def.isRef(i)) {
                continue;
            }
            Object orig = originals[i];
            if (orig instanceof PhpReference ref) {
                ref.setValue(values[i]);
            } else if (lvs[i] != null) {
                lvs[i].write(frame, keys[i], values[i]);
            }
        }
    }

    @TruffleBoundary
    private static Object invokeBuiltin(Builtins.Def def, Object[] values, Node site) {
        PhpContext ctx = PhpContext.get(site);
        Node prev = ctx.currentBuiltinCallSite();
        String prevName = ctx.enterBuiltin(site, def.name());
        try {
            return def.call(ctx, values);
        } catch (PhpError e) {
            throw e.withTrace(dev.phpruntime.runtime.PhpBacktrace.captureFromBuiltin(site, def.name())).at(site);
        } finally {
            ctx.exitBuiltin(prev, prevName);
        }
    }
}
