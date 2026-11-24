package dev.phpruntime.bytecode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.builtins.BuiltinParams;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.call.CallArgHelper;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpFunction;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

import java.util.List;

/**
 * Compact argument buffer and per-site binding plan for calls that use named arguments and no
 * spread. The emitter knows the argument count and names, so a call allocates one value array
 * in call-site order and stores each argument at a constant index; the by-reference side table
 * is allocated only when an argument goes to a by-reference parameter. The plan maps each
 * argument to its parameter slot once per callee, so binding does not scan parameter names or
 * build maps on every call.
 */
final class NamedArgs {

    static final byte ARG_VALUE = 0;
    static final byte ARG_VAR = 1;
    static final byte ARG_PLACE = 2;

    static final byte MODE_VALUE = 0;
    static final byte MODE_REF = 1;
    static final byte MODE_BUILTIN_REF = 2;
    /** A magic call reads a place as a plain value, without the lvalue read's warning. */
    static final byte MODE_MAGIC = 3;
    /**
     * A call result (or {@code new}) bound to a builtin's by-reference parameter: the builtin
     * gets a temporary, after php's notice for a call result.
     */
    static final byte MODE_BUILTIN_TEMP = 4;

    /** {@link #ARG_VALUE} forms, as {@link BytecodeEmitter#namedArg} classifies them. */
    static final byte VALUE_PLAIN = 0;
    static final byte VALUE_CALL = 1;
    static final byte VALUE_NEW = 2;

    static final byte KIND_USER = 0;
    static final byte KIND_BUILTIN = 1;
    static final byte KIND_INTERNAL = 2;

    private static final Object NO_DEFAULT = new Object();
    private static final int CACHE_LIMIT = 4;

    private NamedArgs() {
    }

    /** The call site's argument names (null for a positional argument) and argument forms. */
    static final class Spec {
        @CompilationFinal(dimensions = 1) final String[] names;
        @CompilationFinal(dimensions = 1) final byte[] kinds;
        /** Per argument: {@link #VALUE_PLAIN}, {@link #VALUE_CALL} or {@link #VALUE_NEW}. */
        @CompilationFinal(dimensions = 1) private final byte[] valueKinds;
        @CompilationFinal(dimensions = 1) private final boolean[] duplicates;

        Spec(String[] names, byte[] kinds, byte[] valueKinds) {
            this.names = names.clone();
            this.kinds = kinds.clone();
            this.valueKinds = valueKinds.clone();
            this.duplicates = new boolean[names.length];
            for (int i = 0; i < names.length; i++) {
                if (names[i] == null) {
                    continue;
                }
                for (int j = 0; j < i; j++) {
                    if (names[i].equals(names[j])) {
                        duplicates[i] = true;
                        break;
                    }
                }
            }
        }

        /** The name of the named argument at {@code index}, or null. */
        String name(int index) {
            return names[index];
        }

        byte kind(int index) {
            return kinds[index];
        }

        byte valueKind(int index) {
            return valueKinds[index];
        }

        int length() {
            return names.length;
        }

        /** Whether the named argument at {@code index} repeats a name used earlier. */
        boolean duplicate(int index) {
            return duplicates[index];
        }

        String firstName() {
            for (String name : names) {
                if (name != null) {
                    return name;
                }
            }
            return null;
        }
    }

    /**
     * One call's evaluated arguments, in call-site order, or, when a builtin plan is already
     * known, directly in parameter slots with the skipped defaults prefilled. The direct layout
     * lets the call pass this array to the builtin unchanged, exactly as a positional call does.
     */
    static final class Buffer {
        final Spec spec;
        final Plan plan;
        final Object[] values;
        /** Whether {@link #values} is indexed by parameter slot instead of call-site index. */
        final boolean slots;
        private LValueNode[] refs;
        private Object[][] refKeys;

        Buffer(Spec spec, Plan plan) {
            this.spec = spec;
            this.plan = plan;
            this.slots = plan != null && plan.kind == KIND_BUILTIN && !plan.hasError();
            this.values = slots ? builtinSlots(plan) : new Object[spec.length()];
        }

        /** The array index an argument at call-site index {@code index} uses. */
        private int slotOf(int index) {
            return slots ? plan.dest[index] : index;
        }

        void setValue(int index, Object value) {
            values[slotOf(index)] = value;
        }

        /** The by-reference mode of an argument; -1 when only the generic path can decide. */
        int mode(int index) {
            return plan == null ? -1 : plan.mode[index];
        }

        void setRef(int index, LValueNode ref, Object[] keys) {
            int slot = slotOf(index);
            if (refs == null) {
                refs = new LValueNode[values.length];
                refKeys = new Object[values.length][];
            }
            refs[slot] = ref;
            refKeys[slot] = keys;
        }

        LValueNode ref(int index) {
            return refs == null ? null : refs[slotOf(index)];
        }

        Object[] refKeys(int index) {
            return refKeys == null ? null : refKeys[slotOf(index)];
        }
    }

    /** How one call site binds its arguments for one callee. Built once, then immutable. */
    static final class Plan {
        final Spec spec;
        final byte kind;
        final PhpFunctionRootNode fn;
        final Builtins.Def def;
        final int prefixLength;
        /** Argument index to parameter slot; -1 for a named variadic entry, -2 when ignored. */
        @CompilationFinal(dimensions = 1) final int[] dest;
        /** Slot to argument index, or -1 for a default/unpassed slot. */
        @CompilationFinal(dimensions = 1) final int[] slotArg;
        /** Per slot: the builtin default, or {@link #NO_DEFAULT}. */
        @CompilationFinal(dimensions = 1) final Object[] defaults;
        final int totalSlots;
        /** Slot of the trailing {@code NamedVariadic}, or -1. */
        final int variadicSlot;
        final boolean variadicByRef;
        @CompilationFinal(dimensions = 1) final byte[] mode;
        final String error;
        final boolean errorIsArgumentCount;

        Plan(Spec spec, byte kind, PhpFunctionRootNode fn, Builtins.Def def, int prefixLength,
             int[] dest, int[] slotArg, Object[] defaults, int totalSlots, int variadicSlot,
             boolean variadicByRef, byte[] mode, String error, boolean errorIsArgumentCount) {
            this.spec = spec;
            this.kind = kind;
            this.fn = fn;
            this.def = def;
            this.prefixLength = prefixLength;
            this.dest = dest;
            this.slotArg = slotArg;
            this.defaults = defaults;
            this.totalSlots = totalSlots;
            this.variadicSlot = variadicSlot;
            this.variadicByRef = variadicByRef;
            this.mode = mode;
            this.error = error;
            this.errorIsArgumentCount = errorIsArgumentCount;
        }

        boolean hasError() {
            return error != null;
        }

        /** Throws the binding error this callee always raises for this site. */
        @TruffleBoundary
        void throwError(Node site) {
            if (errorIsArgumentCount) {
                throw new PhpError(PhpError.ARGUMENT_COUNT, error, site);
            }
            throw new PhpError(error, site);
        }

        /** The parameter a named argument bound to a by-reference parameter writes. */
        int refParam(int arg) {
            int d = dest[arg];
            if (d >= 0) {
                return d;
            }
            return d == -1 ? fn.normalParamCount() : -1;
        }
    }

    // ------------------------------------------------------------------ per-site plan cache

    private record Entry(Object key, int kind, Plan plan) {
    }

    private static final Entry[] EMPTY = new Entry[0];

    /**
     * The per-instruction plan cache. At most {@link #CACHE_LIMIT} callees are cached; a site
     * that sees more goes megamorphic and uses the generic binding. Entries are immutable and
     * published as one array, as other requests read the cache without the lock.
     */
    static final class Cache extends Node {
        @CompilationFinal(dimensions = 1) private Entry[] entries = EMPTY;

        static Cache create() {
            return new Cache();
        }

        Plan planFor(Object callee, Spec spec) {
            Object key = keyOf(callee);
            int kind = kindOf(callee);
            if (key == null) {
                return null;
            }
            Entry[] snapshot = entries;
            for (Entry entry : snapshot) {
                if (entry.key() == key && entry.kind() == kind) {
                    return entry.plan();
                }
            }
            if (snapshot.length >= CACHE_LIMIT) {
                return null;
            }
            // Compiled code holds the entries array as a constant, so it is invalidated here,
            // outside the boundary; otherwise it would miss and take the lock on every call.
            CompilerDirectives.transferToInterpreterAndInvalidate();
            return planSlow(callee, spec, key, kind);
        }

        @TruffleBoundary
        private Plan planSlow(Object callee, Spec spec, Object key, int kind) {
            java.util.concurrent.locks.Lock lock = getLock();
            lock.lock();
            try {
                Entry[] snapshot = entries;
                for (Entry entry : snapshot) {
                    if (entry.key() == key && entry.kind() == kind) {
                        return entry.plan();
                    }
                }
                if (snapshot.length >= CACHE_LIMIT) {
                    return null;
                }
                Plan plan = build(callee, spec);
                Entry[] next = java.util.Arrays.copyOf(snapshot, snapshot.length + 1);
                next[snapshot.length] = new Entry(key, kind, plan);
                java.lang.invoke.VarHandle.storeStoreFence();
                entries = next;
                return plan;
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * An identity profile of the plan one operation sees. While every call through the operation
     * uses the same plan (a monomorphic site), compiled code gets the plan as a constant, so the
     * by-reference modes fold and {@link #bindConstant} unrolls; a second plan turns the profile
     * generic for good and the operation reads the plan at run time.
     */
    static final class PlanProfile extends Node {
        @CompilationFinal private Plan expected;
        @CompilationFinal private byte state; // 0 uninitialized, 1 monomorphic, 2 generic

        static PlanProfile create() {
            return new PlanProfile();
        }

        Plan profile(Plan plan) {
            byte st = state;
            if (st == 1) {
                if (plan == expected) {
                    return expected;
                }
                CompilerDirectives.transferToInterpreterAndInvalidate();
                state = 2;
                expected = null;
                return plan;
            }
            if (st == 0) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                expected = plan;
                state = 1;
            }
            return plan;
        }
    }

    /**
     * The cache key: the part of the callee that decides binding. A request-local wrapper
     * object (a method callee, a callable value) is not part of the key.
     */
    static Object keyOf(Object callee) {
        if (callee instanceof Builtins.Def) {
            return callee;
        }
        if (callee instanceof PhpFunction fn) {
            return rootOf(fn.callTarget());
        }
        if (callee instanceof PhpBytecodeRootNode.MethodCallee m) {
            if (m.entry() == null) {
                return null;
            }
            // Prefer the declared function (shared AST) and fall back to the call target, which
            // belongs to the class template: a MethodEntry may be built per request.
            PhpFunctionRootNode root = m.sigTarget() != null ? rootOf(m.sigTarget()) : null;
            return root != null ? root : m.sigTarget();
        }
        if (callee instanceof PhpBytecodeRootNode.ValueCallee v) {
            dev.phpruntime.runtime.PhpCallable cb = v.callable();
            return switch (cb.kind()) {
                case BUILTIN -> cb.builtin();
                case MAGIC_CALL -> null;
                default -> v.sig() != null ? v.sig() : rootOf(cb.callTarget());
            };
        }
        return null;
    }

    /** Distinguishes callee shapes with the same binding key but a different argument prefix. */
    static int kindOf(Object callee) {
        if (callee instanceof Builtins.Def) {
            return 1;
        }
        if (callee instanceof PhpFunction) {
            return 2;
        }
        if (callee instanceof PhpBytecodeRootNode.MethodCallee) {
            return 3;
        }
        if (callee instanceof PhpBytecodeRootNode.ValueCallee v) {
            dev.phpruntime.runtime.PhpCallable cb = v.callable();
            return switch (cb.kind()) {
                case CLOSURE -> 4;
                case USER_FUNCTION -> 5;
                case METHOD -> 6 + (cb.traitSelf() != null ? 1 : 0);
                case MAGIC_CALL -> 8;
                case BUILTIN -> 9;
            };
        }
        return 0;
    }

    static PhpFunctionRootNode rootOf(RootCallTarget target) {
        return target == null ? null : PhpFunctionRootNode.of(target.getRootNode());
    }

    // ------------------------------------------------------------------ plan construction

    @TruffleBoundary
    static Plan build(Object callee, Spec spec) {
        if (callee instanceof Builtins.Def def) {
            return buildBuiltin(def, spec, 0);
        }
        if (callee instanceof PhpFunction fn) {
            PhpFunctionRootNode fnRoot = rootOf(fn.callTarget());
            return fnRoot == null ? null : buildUser(fnRoot, spec, 0);
        }
        if (callee instanceof PhpBytecodeRootNode.MethodCallee m) {
            if (m.entry() == null) {
                return null;
            }
            if (spec.firstName() != null && dev.phpruntime.runtime.NamedForwarding.forwardsMethod(m.entry())) {
                // php lets these methods accept names they do not declare; the generic binder
                // forwards them to the callable or constructor they invoke.
                return null;
            }
            PhpFunctionRootNode fnRoot = m.sigTarget() != null ? rootOf(m.sigTarget()) : null;
            if (fnRoot != null) {
                return buildUser(fnRoot, spec, 2);
            }
            long refMask = m.sigTarget() != null
                    ? PhpBytecodeRootNode.Ops.refMaskSlow(m.sigTarget().getRootNode()) : 0L;
            refMask |= m.entry().refMask();
            return buildInternal(m.entry().parameterNames(), spec, 2, refMask);
        }
        if (callee instanceof PhpBytecodeRootNode.ValueCallee v) {
            dev.phpruntime.runtime.PhpCallable cb = v.callable();
            if (cb.kind() == dev.phpruntime.runtime.PhpCallable.Kind.BUILTIN) {
                return buildBuiltin(cb.builtin(), spec, 0);
            }
            if (cb.kind() == dev.phpruntime.runtime.PhpCallable.Kind.MAGIC_CALL) {
                return null;
            }
            PhpFunctionRootNode fnRoot = v.sig() != null ? v.sig() : rootOf(cb.callTarget());
            if (fnRoot == null) {
                return null;
            }
            int prefix = switch (cb.kind()) {
                case CLOSURE -> 3;
                case METHOD -> cb.traitSelf() != null ? 3 : 2;
                default -> 0;
            };
            return buildUser(fnRoot, spec, prefix);
        }
        return null;
    }

    private static Plan buildUser(PhpFunctionRootNode fn, Spec spec, int prefix) {
        int argc = spec.length();
        int normal = fn.normalParamCount();
        boolean variadic = fn.isVariadic();
        int[] dest = new int[argc];
        int[] slotArg = new int[normal];
        java.util.Arrays.fill(slotArg, -1);
        boolean[] occupied = new boolean[normal];
        int positional = 0;
        for (int i = 0; i < argc; i++) {
            if (spec.name(i) == null) {
                dest[i] = positional;
                if (positional < normal) {
                    occupied[positional] = true;
                }
                positional++;
            }
        }
        String error = null;
        int highest = positional - 1;
        for (int i = 0; i < argc; i++) {
            String name = spec.name(i);
            if (name == null) {
                continue;
            }
            int idx = paramIndex(fn, name);
            if (idx >= 0) {
                dest[i] = idx;
                if (error == null && (idx < positional || occupied[idx])) {
                    error = "Named parameter $" + name + " overwrites previous argument";
                }
                occupied[idx] = true;
                slotArg[idx] = i;
                highest = Math.max(highest, idx);
            } else if (variadic) {
                dest[i] = -1;
            } else {
                dest[i] = -2;
                if (error == null) {
                    error = "Unknown named parameter $" + name;
                }
            }
        }
        int variadicSlot = -1;
        int totalSlots;
        if (hasVariadicEntry(dest)) {
            totalSlots = Math.max(positional, normal);
            variadicSlot = totalSlots;
        } else {
            totalSlots = Math.max(positional, highest + 1);
        }
        byte[] mode = new byte[argc];
        for (int i = 0; i < argc; i++) {
            int d = dest[i];
            boolean byRef = d >= 0 ? fn.isParamByRef(d) : d == -1 && fn.isParamByRef(normal);
            mode[i] = byRef ? MODE_REF : MODE_VALUE;
        }
        return new Plan(spec, KIND_USER, fn, null, prefix, dest, slotArg, null, totalSlots,
                variadicSlot, variadic && fn.isParamByRef(normal), mode, error, false);
    }

    private static boolean hasVariadicEntry(int[] dest) {
        for (int d : dest) {
            if (d == -1) {
                return true;
            }
        }
        return false;
    }

    private static Plan buildBuiltin(Builtins.Def def, Spec spec, int prefix) {
        if (spec.firstName() != null && dev.phpruntime.runtime.NamedForwarding.forwardsFunction(def.name())) {
            // call_user_func forwards names it does not declare to its callback.
            return null;
        }
        int argc = spec.length();
        BuiltinParams.Param[] params = BuiltinParams.forName(def.name());
        int[] dest = new int[argc];
        java.util.Arrays.fill(dest, -2);
        int positional = 0;
        for (int i = 0; i < argc; i++) {
            if (spec.name(i) == null) {
                dest[i] = positional++;
            }
        }
        if (params == null) {
            boolean rejectsVariadic = def.maxArgs() < 0;
            return new Plan(spec, KIND_BUILTIN, null, def, prefix, dest, new int[0], null,
                    positional, -1, false, valuesOnly(argc),
                    rejectsVariadic ? "Internal function " + def.name() + "() does not accept named variadic arguments"
                            : "Unknown named parameter $" + spec.firstName(),
                    rejectsVariadic);
        }
        int normal = params.length;
        while (normal > 0 && params[normal - 1].variadic()) {
            normal--;
        }
        boolean variadic = normal < params.length;
        int capacity = Math.max(normal, argc) + 1;
        int[] slotArg = new int[capacity];
        java.util.Arrays.fill(slotArg, -1);
        for (int i = 0; i < positional; i++) {
            slotArg[i] = i;
        }
        String error = null;
        boolean errorCount = false;
        int highest = positional - 1;
        for (int i = 0; i < argc; i++) {
            String name = spec.name(i);
            if (name == null) {
                continue;
            }
            int idx = builtinParamIndex(params, normal, name);
            if (idx < 0) {
                dest[i] = -2;
                if (error == null) {
                    if (variadic) {
                        error = "Internal function " + def.name() + "() does not accept named variadic arguments";
                        errorCount = true;
                    } else {
                        error = "Unknown named parameter $" + name;
                    }
                }
                continue;
            }
            dest[i] = idx;
            if (error == null && idx < positional) {
                error = "Named parameter $" + name + " overwrites previous argument";
            }
            if (error == null && def.isRef(idx) && spec.kind(i) == ARG_VALUE
                    && spec.valueKind(i) != VALUE_CALL) {
                error = def.name() + "(): Argument #" + (idx + 1) + " ($" + params[idx].name()
                        + ") could not be passed by reference";
            }
            slotArg[idx] = i;
            highest = Math.max(highest, idx);
        }
        int length = Math.max(positional, highest + 1);
        Object[] defaults = null;
        for (int i = positional; i < length && i < normal; i++) {
            if (slotArg[i] >= 0) {
                continue;
            }
            BuiltinParams.Param param = params[i];
            if (!param.hasDefault()) {
                if (error == null) {
                    errorCount = true;
                    if (param.optional()) {
                        error = def.name() + "(): Argument #" + (i + 1) + " ($" + param.name()
                                + ") must be passed explicitly, because the default value is not known";
                    } else {
                        error = def.name() + "(): Argument #" + (i + 1) + " ($" + param.name()
                                + ") not passed";
                    }
                }
                if (defaults == null) {
                    defaults = new Object[length];
                    java.util.Arrays.fill(defaults, NO_DEFAULT);
                }
                continue;
            }
            if (defaults == null) {
                defaults = new Object[length];
                java.util.Arrays.fill(defaults, NO_DEFAULT);
            }
            defaults[i] = param.defaultValue() == null ? PhpNull.INSTANCE : param.defaultValue();
        }
        byte[] mode = new byte[argc];
        for (int i = 0; i < argc; i++) {
            int d = dest[i];
            boolean byRef = d >= 0 && def.isRef(d);
            boolean hasPlace = spec.kind(i) == ARG_VAR || spec.kind(i) == ARG_PLACE;
            mode[i] = !byRef ? MODE_VALUE : hasPlace ? MODE_BUILTIN_REF
                    : spec.valueKind(i) != VALUE_PLAIN ? MODE_BUILTIN_TEMP : MODE_VALUE;
        }
        return new Plan(spec, KIND_BUILTIN, null, def, prefix, dest, slotArg, defaults, length,
                -1, false, mode, error, errorCount);
    }

    private static Plan buildInternal(List<String> paramNames, Spec spec, int prefix, long refMask) {
        int argc = spec.length();
        int[] dest = new int[argc];
        int positional = 0;
        for (int i = 0; i < argc; i++) {
            if (spec.name(i) == null) {
                dest[i] = positional++;
            } else {
                dest[i] = -2;
            }
        }
        if (paramNames.isEmpty()) {
            if (spec.firstName() != null) {
                return new Plan(spec, KIND_INTERNAL, null, null, prefix, dest,
                        positionalSlots(positional), null, positional, -1, false,
                        internalModes(dest, refMask),
                        "Unknown named parameter $" + spec.firstName(), false);
            }
            return new Plan(spec, KIND_INTERNAL, null, null, prefix, dest,
                    positionalSlots(positional), null, positional, -1, false,
                    internalModes(dest, refMask), null, false);
        }
        int capacity = Math.max(paramNames.size(), argc) + 1;
        int[] slotArg = new int[capacity];
        java.util.Arrays.fill(slotArg, -1);
        for (int i = 0; i < positional; i++) {
            slotArg[i] = i;
        }
        String error = null;
        int highest = positional - 1;
        for (int i = 0; i < argc; i++) {
            String name = spec.name(i);
            if (name == null) {
                continue;
            }
            int idx = internalParamIndex(paramNames, name);
            if (idx < 0) {
                dest[i] = -2;
                if (error == null) {
                    error = "Unknown named parameter $" + name;
                }
                continue;
            }
            dest[i] = idx;
            if (error == null && idx < positional) {
                error = "Named parameter $" + name + " overwrites previous argument";
            }
            slotArg[idx] = i;
            highest = Math.max(highest, idx);
        }
        int totalSlots = Math.max(positional, highest + 1);
        return new Plan(spec, KIND_INTERNAL, null, null, prefix, dest, slotArg, null, totalSlots,
                -1, false, internalModes(dest, refMask), error, false);
    }

    private static byte[] internalModes(int[] dest, long refMask) {
        byte[] mode = new byte[dest.length];
        for (int i = 0; i < dest.length; i++) {
            int d = dest[i];
            mode[i] = d >= 0 && PhpFunctionRootNode.refBit(refMask, d) ? MODE_REF : MODE_VALUE;
        }
        return mode;
    }

    private static int[] positionalSlots(int positional) {
        int[] slots = new int[positional];
        for (int i = 0; i < positional; i++) {
            slots[i] = i;
        }
        return slots;
    }

    private static byte[] valuesOnly(int argc) {
        byte[] mode = new byte[argc];
        java.util.Arrays.fill(mode, MODE_VALUE);
        return mode;
    }

    private static int paramIndex(PhpFunctionRootNode fn, String name) {
        String[] names = fn.paramNames();
        int normal = fn.normalParamCount();
        for (int i = 0; i < normal; i++) {
            if (name.equals(names[i])) {
                return i;
            }
        }
        return -1;
    }

    private static int builtinParamIndex(BuiltinParams.Param[] params, int normal, String name) {
        for (int i = 0; i < normal; i++) {
            if (params[i].name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static int internalParamIndex(List<String> names, String name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equals(name)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ binding

    /** Builds the callee's argument array: prefix, then parameter slots in the plan's order. */
    @TruffleBoundary
    static Object[] bind(Plan plan, int prefixLength, Object a, Object b, Object c, Object[] values) {
        int extra = plan.variadicSlot >= 0 ? 1 : 0;
        Object[] out = new Object[prefixLength + plan.totalSlots + extra];
        if (prefixLength > 0) {
            out[0] = a;
        }
        if (prefixLength > 1) {
            out[1] = b;
        }
        if (prefixLength > 2) {
            out[2] = c;
        }
        int base = prefixLength;
        if (plan.kind == KIND_USER) {
            java.util.Arrays.fill(out, base, base + plan.totalSlots, PhpFunctionRootNode.UNPASSED);
            PhpArray namedEntries = null;
            String[] names = plan.spec.names;
            for (int i = 0; i < values.length; i++) {
                int d = plan.dest[i];
                if (d >= 0) {
                    out[base + d] = values[i];
                } else if (d == -1) {
                    if (namedEntries == null) {
                        namedEntries = new PhpArray();
                    }
                    String name = names[i];
                    Object value = values[i];
                    if (value instanceof PhpReference ref && plan.variadicByRef) {
                        namedEntries.putRef(name, ref);
                    } else {
                        Object v = value instanceof PhpReference ref ? ref.getValue() : value;
                        namedEntries.put(name, PhpOps.copyOnAssign(v));
                    }
                }
            }
            if (plan.variadicSlot >= 0) {
                out[base + plan.totalSlots] = new PhpFunctionRootNode.NamedVariadic(namedEntries);
            }
        } else if (plan.kind == KIND_BUILTIN) {
            for (int s = 0; s < plan.totalSlots; s++) {
                int arg = plan.slotArg[s];
                if (arg >= 0) {
                    out[base + s] = values[arg];
                } else {
                    Object value = plan.defaults == null ? null : plan.defaults[s];
                    out[base + s] = value == null || value == NO_DEFAULT
                            ? PhpNull.INSTANCE : PhpOps.copyOnAssign(value);
                }
            }
        } else {
            for (int s = 0; s < plan.totalSlots; s++) {
                int arg = plan.slotArg[s];
                out[base + s] = arg >= 0 ? values[arg] : PhpNull.INSTANCE;
            }
        }
        return out;
    }

    /**
     * {@link #bind} for a plan that is a compilation constant: the loops unroll over the call
     * site's arguments, so the argument array can stay virtual into an inlined callee. Plans with
     * a named-variadic entry go through {@link #bind}.
     */
    static Object[] bindArgs(Plan plan, Object a, Object b, Object c, Object[] values) {
        if (CompilerDirectives.inCompiledCode() && CompilerDirectives.isPartialEvaluationConstant(plan)
                && plan.variadicSlot < 0) {
            return bindConstant(plan, a, b, c, values);
        }
        return bind(plan, plan.prefixLength, a, b, c, values);
    }

    @ExplodeLoop
    private static Object[] bindConstant(Plan plan, Object a, Object b, Object c, Object[] values) {
        int base = plan.prefixLength;
        Object[] out = new Object[base + plan.totalSlots];
        if (base > 0) {
            out[0] = a;
        }
        if (base > 1) {
            out[1] = b;
        }
        if (base > 2) {
            out[2] = c;
        }
        if (plan.kind == KIND_USER) {
            for (int s = 0; s < plan.totalSlots; s++) {
                out[base + s] = PhpFunctionRootNode.UNPASSED;
            }
            int[] dest = plan.dest;
            for (int i = 0; i < dest.length; i++) {
                if (dest[i] >= 0) {
                    out[base + dest[i]] = values[i];
                }
            }
        } else if (plan.kind == KIND_BUILTIN) {
            Object[] defaults = plan.defaults;
            int[] slotArg = plan.slotArg;
            for (int s = 0; s < plan.totalSlots; s++) {
                int arg = slotArg[s];
                out[base + s] = arg >= 0 ? values[arg] : builtinDefault(defaults, s);
            }
        } else {
            int[] slotArg = plan.slotArg;
            for (int s = 0; s < plan.totalSlots; s++) {
                int arg = slotArg[s];
                out[base + s] = arg >= 0 ? values[arg] : PhpNull.INSTANCE;
            }
        }
        return out;
    }

    /** The value a skipped slot gets: the declared default, or "not passed" as null. */
    private static Object builtinDefault(Object[] defaults, int slot) {
        Object value = defaults == null ? null : defaults[slot];
        return value == null || value == NO_DEFAULT ? PhpNull.INSTANCE : PhpOps.copyOnAssign(value);
    }

    /**
     * A direct-layout builtin buffer's argument array, prefilled with the declared defaults. The
     * fill explodes when the plan is a compilation constant, so a monomorphic site stores each
     * default as a constant and the array stays virtual.
     */
    static Object[] builtinSlots(Plan plan) {
        Object[] out = new Object[plan.totalSlots];
        if (CompilerDirectives.inCompiledCode() && CompilerDirectives.isPartialEvaluationConstant(plan)) {
            fillSlots(out, plan);
        } else {
            fillSlotsSlow(out, plan);
        }
        return out;
    }

    @ExplodeLoop
    private static void fillSlots(Object[] out, Plan plan) {
        for (int s = 0; s < plan.totalSlots; s++) {
            out[s] = builtinDefault(plan.defaults, s);
        }
    }

    private static void fillSlotsSlow(Object[] out, Plan plan) {
        for (int s = 0; s < plan.totalSlots; s++) {
            out[s] = builtinDefault(plan.defaults, s);
        }
    }

    /**
     * A builtin with named arguments: places defaults, then calls the builtin exactly as the
     * positional path does. A monomorphic site builds the argument array directly in parameter
     * slots, so the array the builtin receives is the one the call site filled; megamorphic
     * sites fall back to {@link #bind}. By-reference arguments arrive as cells, as they do from
     * the positional path, so the writeback needs no frame or lvalue side table.
     */
    static Object callBuiltin(Builtins.Def def, Buffer buffer, Plan plan, PhpBytecodeRootNode.SiteNode site) {
        Object[] values = buffer.slots ? buffer.values : bindArgs(plan, null, null, null, buffer.values);
        if (def.refMask() == 0) {
            return PhpBytecodeRootNode.Ops.callBuiltin(def, values, site);
        }
        return PhpBytecodeRootNode.Ops.callBuiltinValue(def, values, site);
    }

    // ------------------------------------------------------------------ generic fallback

    @TruffleBoundary
    static void throwDuplicate(String name, Node site) {
        throw new PhpError("Named parameter $" + name + " overwrites previous argument", site);
    }

    /** The old list/map argument structure, rebuilt only when a site is megamorphic. */
    @TruffleBoundary
    static CallArgHelper.RawCallArgs toRaw(Buffer buffer) {
        CallArgHelper.RawCallArgs raw = CallArgHelper.newRawCallArgs();
        for (int i = 0; i < buffer.values.length; i++) {
            String name = buffer.spec.name(i);
            Object value = buffer.values[i];
            LValueNode ref = buffer.ref(i);
            Object[] keys = buffer.refKeys(i);
            if (name != null) {
                raw.named().put(name, value);
                if (ref != null) {
                    raw.namedRefTargets().put(name, ref);
                    raw.namedRefKeys().put(name, keys);
                }
                raw.namedNodes().put(name, null);
            } else {
                raw.positional().add(value);
                raw.posRefTargets().add(ref);
                raw.posRefKeys().add(keys);
                raw.posNodes().add(null);
            }
        }
        return raw;
    }

    /** The array a {@code __call}/{@code __callStatic} receives, from the compact buffer. */
    @TruffleBoundary
    static PhpArray magicArray(Buffer buffer) {
        PhpArray arr = new PhpArray();
        for (int i = 0; i < buffer.values.length; i++) {
            Object value = buffer.values[i];
            String name = buffer.spec.name(i);
            Object copied = PhpOps.copyOnAssign(value);
            if (name == null) {
                arr.append(copied);
            } else {
                arr.put(name, copied);
            }
        }
        return arr;
    }

    /** The by-reference mode of an argument at a site with no plan. */
    @TruffleBoundary
    static int genericMode(Object callee, String name, int index) {
        Builtins.Def builtin = PhpBytecodeRootNode.builtinCallee(callee);
        if (builtin != null) {
            return MODE_BUILTIN_REF;
        }
        if (PhpBytecodeRootNode.Ops.ignoresRefTargets(callee)) {
            return MODE_MAGIC;
        }
        PhpFunctionRootNode fn = PhpBytecodeRootNode.signature(callee);
        if (fn != null) {
            boolean byRef = name != null
                    ? namedByRef(fn, name)
                    : fn.isParamByRef(index);
            return byRef ? MODE_REF : MODE_VALUE;
        }
        if (callee instanceof PhpBytecodeRootNode.MethodCallee m && m.entry() != null
                && m.sigTarget() != null) {
            long mask = PhpBytecodeRootNode.Ops.refMaskSlow(m.sigTarget().getRootNode()) | m.entry().refMask();
            if (mask != 0) {
                int param = name != null ? internalParamIndex(m.entry().parameterNames(), name) : index;
                if (param >= 0 && PhpFunctionRootNode.refBit(mask, param)) {
                    return MODE_REF;
                }
            }
        }
        return MODE_VALUE;
    }

    static boolean namedByRef(PhpFunctionRootNode fn, String name) {
        int idx = paramIndex(fn, name);
        if (idx >= 0) {
            return fn.isParamByRef(idx);
        }
        return fn.isVariadic() && fn.isParamByRef(fn.normalParamCount());
    }

    /** Whether a callee is a Java-implemented method, whose by-reference returns are unwrapped. */
    static boolean isInternalCallee(Object callee) {
        return callee instanceof PhpBytecodeRootNode.MethodCallee m && m.entry() != null
                && PhpBytecodeRootNode.signature(callee) == null;
    }

    /** An expression argument bound to a by-reference parameter: php's error or temporary cell. */
    @TruffleBoundary
    static Object valueToRef(Object value, int kind, Buffer buffer, Object callee, int index, String name,
                             Node site, int line) {
        PhpFunctionRootNode fn = buffer.plan != null && buffer.plan.fn != null
                ? buffer.plan.fn : PhpBytecodeRootNode.signature(callee);
        int param = buffer.plan != null ? buffer.plan.refParam(index)
                : (name != null ? indexOf(fn, name) : index);
        if (kind == 0) {
            throw cannotPassByRef(callee, fn, param, site);
        }
        if (kind == 1) {
            dev.phpruntime.PhpContext.get(site).notice("Only variables should be passed by reference", line);
        }
        return new PhpReference(PhpOps.copyOnAssign(value));
    }

    /** php's notice for a call result passed to a by-reference parameter. */
    @TruffleBoundary
    static void onlyVariablesNotice(Node site, int line) {
        dev.phpruntime.PhpContext.get(site).notice("Only variables should be passed by reference", line);
    }

    private static int indexOf(PhpFunctionRootNode fn, String name) {
        int idx = paramIndex(fn, name);
        return idx >= 0 ? idx : fn.normalParamCount();
    }

    /** A {@code $GLOBALS} element cannot be bound by reference; only the place path needs this. */
    @TruffleBoundary
    static PhpError globalsByRef(Buffer buffer, Object callee, int index, String name, Node site) {
        PhpFunctionRootNode fn = buffer.plan != null && buffer.plan.fn != null
                ? buffer.plan.fn : PhpBytecodeRootNode.signature(callee);
        int param = buffer.plan != null ? buffer.plan.refParam(index)
                : (name != null ? indexOf(fn, name) : index);
        return cannotPassByRef(callee, fn, param, site);
    }

    /** php's error names the Java method too, not the placeholder "unknown()". */
    private static PhpError cannotPassByRef(Object callee, PhpFunctionRootNode fn, int param, Node site) {
        if (fn == null && callee instanceof PhpBytecodeRootNode.MethodCallee m && m.entry() != null) {
            List<String> names = m.entry().parameterNames();
            String pName = param >= 0 && param < names.size() ? names.get(param) : null;
            return CallArgHelper.cannotPassByRef(
                    m.entry().declaringClass().name() + "::" + m.entry().name(), pName, param, site);
        }
        return CallArgHelper.cannotPassByRef(fn, param, site);
    }
}
