package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.PhpContext;
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
            return ref.value == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(ref.value);
        }
        return res;
    }

    /** Evaluates one positional argument for a user function parameter that may be by-reference. */
    public static Object evalArgForParam(VirtualFrame frame, PhpNode argNode, LValueNode refTarget,
                                         PhpFunctionRootNode fnRoot, int paramIdx, Node site) {
        if (fnRoot != null && fnRoot.isParamByRef(paramIdx)) {
            return evalByRefArg(frame, argNode, refTarget, fnRoot, paramIdx, site);
        }
        return PhpOps.copyOnAssign(argNode.execute(frame));
    }

    public static Object evalByRefArg(VirtualFrame frame, PhpNode argNode, LValueNode refTarget,
                                      PhpFunctionRootNode fnRoot, int paramIdx, Node site) {
        if (refTarget != null) {
            if (refTarget instanceof dev.phpruntime.nodes.var.GlobalVarNode gv && gv.isGlobalsSuperglobal()) {
                throw cannotPassByRef(fnRoot, paramIdx, site);
            }
            if (refTarget instanceof LocalVarNode lv && "this".equals(lv.varName())) {
                throw cannotPassByRef(fnRoot, paramIdx, site);
            }
            return refTarget.obtainCell(frame, refTarget.evalKeys(frame));
        }
        if (isFunctionOrNewResult(argNode)) {
            Object res = argNode.executeForRef(frame);
            if (res instanceof PhpReference ref) {
                if (ref.value == null) {
                    ref.value = PhpNull.INSTANCE;
                }
                return ref;
            }
            if (!(argNode instanceof dev.phpruntime.nodes.expr.NewNode)) {
                PhpContext.get(site).notice("Only variables should be passed by reference",
                        site instanceof PhpNode pn ? pn.line() : 0);
            }
            return new PhpReference(PhpOps.copyOnAssign(res));
        }
        throw cannotPassByRef(fnRoot, paramIdx, site);
    }

    private static boolean isFunctionOrNewResult(PhpNode node) {
        return node instanceof CallNode
                || node instanceof MethodCallNode
                || node instanceof StaticMethodCallNode
                || node instanceof CallValueNode
                || node instanceof dev.phpruntime.nodes.expr.NullsafeMemberNode
                || node instanceof dev.phpruntime.nodes.expr.NewNode;
    }

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
    public static Object[] evalComplexUserArgs(VirtualFrame frame, RootCallTarget target, Object[] prefix,
                                               PhpNode[] args, LValueNode[] refTargets,
                                               String[] argNames, boolean[] spreads, Node site) {
        PhpFunctionRootNode fnRoot = target.getRootNode() instanceof PhpFunctionRootNode r ? r : null;
        RawCallArgs raw = evaluateCallSite(frame, args, refTargets, argNames, spreads, fnRoot, site);
        return bindToFunction(prefix, raw, fnRoot, site);
    }

    public record RawCallArgs(List<Object> positional, List<LValueNode> posRefTargets,
                              List<Object[]> posRefKeys, List<PhpNode> posNodes,
                              Map<String, Object> named, Map<String, LValueNode> namedRefTargets,
                              Map<String, Object[]> namedRefKeys, Map<String, PhpNode> namedNodes) {
    }

    public static RawCallArgs evaluateCallSite(VirtualFrame frame, PhpNode[] args, LValueNode[] refTargets,
                                               String[] argNames, boolean[] spreads,
                                               PhpFunctionRootNode fnRoot, Node site) {
        return evaluateCallSite(frame, args, refTargets, argNames, spreads, fnRoot, false, site);
    }

    public static RawCallArgs evaluateCallSite(VirtualFrame frame, PhpNode[] args, LValueNode[] refTargets,
                                               String[] argNames, boolean[] spreads,
                                               PhpFunctionRootNode fnRoot, boolean builtinHasRefs, Node site) {
        List<Object> positional = new ArrayList<>(args.length);
        List<LValueNode> posRefs = new ArrayList<>(args.length);
        List<Object[]> posKeys = new ArrayList<>(args.length);
        List<PhpNode> posNodes = new ArrayList<>(args.length);
        Map<String, Object> named = null;
        Map<String, LValueNode> namedRefs = null;
        Map<String, Object[]> namedKeys = null;
        Map<String, PhpNode> namedNodes = null;
        boolean seenNamed = false;

        for (int i = 0; i < args.length; i++) {
            String name = argNames != null ? argNames[i] : null;
            boolean isSpread = spreads != null && spreads[i];
            if (isSpread) {
                LValueNode spreadLv = refTargets != null ? refTargets[i] : null;
                Object unpacked;
                PhpArray writableSpread = null;
                boolean needRefFromSpread = spreadLv != null && (builtinHasRefs || (fnRoot != null && fnRoot.hasAnyRefParam()));
                if (needRefFromSpread) {
                    Object[] sk = spreadLv.evalKeys(frame);
                    Object cur = spreadLv.read(frame, sk);
                    if (cur instanceof PhpArray arr) {
                        PhpReference cell = spreadLv.obtainCell(frame, sk);
                        writableSpread = arr.forWrite();
                        cell.value = writableSpread;
                        unpacked = writableSpread;
                    } else {
                        unpacked = cur == null ? PhpNull.INSTANCE : cur;
                    }
                } else {
                    unpacked = args[i].execute(frame);
                }
                unpackCallArg(unpacked, writableSpread, positional, posRefs, posKeys, posNodes,
                        named == null ? (named = new LinkedHashMap<>()) : named, seenNamed, site);
                if (named != null && !named.isEmpty()) {
                    seenNamed = true;
                }
            } else if (name != null) {
                seenNamed = true;
                if (named == null) {
                    named = new LinkedHashMap<>();
                    namedRefs = new LinkedHashMap<>();
                    namedKeys = new LinkedHashMap<>();
                    namedNodes = new LinkedHashMap<>();
                }
                checkDuplicateNamed(named, name, site);
                LValueNode lv = refTargets != null ? refTargets[i] : null;
                int pIdx = fnRoot != null ? findParamIndex(fnRoot, name) : -1;
                if (fnRoot != null && pIdx >= 0 && fnRoot.isParamByRef(pIdx)) {
                    putMap(named, name, evalByRefArg(frame, args[i], lv, fnRoot, pIdx, site));
                } else if (fnRoot != null && pIdx < 0 && fnRoot.isVariadic() && fnRoot.isParamByRef(fnRoot.normalParamCount())) {
                    putMap(named, name, evalByRefArg(frame, args[i], lv, fnRoot, fnRoot.normalParamCount(), site));
                } else {
                    if (lv != null) {
                        Object[] ek = lv.evalKeys(frame);
                        Object v = lv.read(frame, ek);
                        putMap(named, name, v == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(v));
                        putMap(namedRefs, name, lv);
                        putMap(namedKeys, name, ek);
                    } else {
                        putMap(named, name, PhpOps.copyOnAssign(args[i].execute(frame)));
                    }
                    putMap(namedNodes, name, args[i]);
                }
            } else {
                LValueNode lv = refTargets != null ? refTargets[i] : null;
                int pIdx = positional.size();
                if (fnRoot != null && fnRoot.isParamByRef(pIdx)) {
                    addList(positional, evalByRefArg(frame, args[i], lv, fnRoot, pIdx, site));
                    addList(posRefs, null);
                    addList(posKeys, null);
                } else {
                    if (lv != null) {
                        Object[] ek = lv.evalKeys(frame);
                        Object v = lv.read(frame, ek);
                        addList(positional, v == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(v));
                        addList(posRefs, lv);
                        addList(posKeys, ek);
                    } else {
                        addList(positional, PhpOps.copyOnAssign(args[i].execute(frame)));
                        addList(posRefs, null);
                        addList(posKeys, null);
                    }
                }
                addList(posNodes, args[i]);
            }
        }
        return new RawCallArgs(positional, posRefs, posKeys, posNodes, named, namedRefs, namedKeys, namedNodes);
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
    private static void checkDuplicateNamed(Map<String, Object> named, String name, Node site) {
        if (named.containsKey(name)) {
            throw new PhpError("Named parameter $" + name + " overwrites previous argument", site);
        }
    }

    @TruffleBoundary
    private static int findParamIndex(PhpFunctionRootNode fnRoot, String name) {
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
    private static void unpackCallArg(Object unpacked, PhpArray writableRefSource,
                                      List<Object> positional, List<LValueNode> posRefs,
                                      List<Object[]> posKeys, List<PhpNode> posNodes,
                                      Map<String, Object> named, boolean alreadySeenNamed, Node site) {
        if (!(unpacked instanceof PhpArray src)) {
            String given = unpacked == null || unpacked == PhpNull.INSTANCE ? "null" : PhpOps.typeName(unpacked);
            throw new PhpError(PhpError.TYPE_ERROR, "Only arrays and Traversables can be unpacked, " + given + " given", site);
        }
        boolean seenNamed = alreadySeenNamed || !named.isEmpty();
        for (Object k : src.keys()) {
            if (k instanceof String s) {
                seenNamed = true;
                if (named.containsKey(s)) {
                    throw new PhpError("Named parameter $" + s + " overwrites previous argument", site);
                }
                Object val = writableRefSource != null ? writableRefSource.obtainElementRef(k)
                        : (src.getRaw(k) instanceof PhpReference ref ? ref : PhpOps.copyOnAssign(src.get(k)));
                named.put(s, val);
            } else {
                if (seenNamed) {
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
                    Object v = e.getValue() instanceof PhpReference ref ? ref.value : e.getValue();
                    extraNamed.put(pName, PhpOps.copyOnAssign(v));
                }
            } else {
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
    public static Object callComplexBuiltin(VirtualFrame frame, Builtins.Def def, PhpNode[] args,
                                            LValueNode[] refTargets, String[] argNames, boolean[] spreads,
                                            Node site) {
        RawCallArgs raw = evaluateCallSite(frame, args, refTargets, argNames, spreads, null, def.refMask() != 0, site);
        if (raw.named() != null && !raw.named().isEmpty()) {
            String first = firstKey(raw.named());
            throw new PhpError("Unknown named parameter $" + first, site);
        }
        List<Object> pos = raw.positional();
        Object[] values = toObjectArray(pos);
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof PhpReference ref && !def.isRef(i)) {
                values[i] = ref.value == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(ref.value);
            } else if (values[i] instanceof PhpReference ref && def.isRef(i)) {
                values[i] = ref.value == null ? PhpNull.INSTANCE : ref.value;
            }
        }
        CallNode.checkArity(def, values.length, site);
        Object result = invokeBuiltin(def, values, site);
        writeBackBuiltinRefs(frame, def, raw, values);
        return result;
    }

    @TruffleBoundary
    private static String firstKey(Map<String, Object> map) {
        return map.keySet().iterator().next();
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

    private static void writeBackBuiltinRefs(VirtualFrame frame, Builtins.Def def, RawCallArgs raw, Object[] values) {
        Object[] orig = toObjectArray(raw.positional());
        LValueNode[] lvs = toLValueArray(raw.posRefTargets());
        Object[][] keys = toKeysArray(raw.posRefKeys());
        for (int i = 0; i < values.length; i++) {
            if (!def.isRef(i)) {
                continue;
            }
            if (orig[i] instanceof PhpReference ref) {
                ref.value = values[i];
            } else if (lvs[i] != null) {
                lvs[i].write(frame, keys[i], values[i]);
            }
        }
    }

    @TruffleBoundary
    private static Object invokeBuiltin(Builtins.Def def, Object[] values, Node site) {
        PhpContext ctx = PhpContext.get(site);
        Node prev = ctx.currentBuiltinCallSite();
        ctx.setCurrentBuiltinCallSite(site);
        try {
            return def.impl().call(ctx, values);
        } catch (PhpError e) {
            throw e.withTrace(dev.phpruntime.runtime.PhpBacktrace.captureFromBuiltin(site, def.name())).at(site);
        } finally {
            ctx.setCurrentBuiltinCallSite(prev);
        }
    }
}
