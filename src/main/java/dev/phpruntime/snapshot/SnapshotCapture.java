package dev.phpruntime.snapshot;

import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.builtins.ContextState;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClosure;
import dev.phpruntime.runtime.PhpFunction;
import dev.phpruntime.runtime.PhpGenerator;
import dev.phpruntime.runtime.PhpNativeState;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpResource;
import dev.phpruntime.runtime.PhpStringBuffer;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the state at the end of the boot include into a {@link Snapshot}, or refuses with the
 * reasons it found. The pre-boot delta recorded when the include starts is left out and later
 * re-created by the entry script.
 */
final class SnapshotCapture {

    static final String OUTPUT_WRITTEN = "output was written during boot";
    static final String OUTPUT_BUFFER_OPEN = "an output buffer was left open by boot";
    static final String HEADERS_SET = "headers were set by boot";
    static final String OPEN_RESOURCE = "an open file, stream or directory handle was left by boot";
    static final String LIVE_GENERATOR = "a live generator was left by boot";

    private SnapshotCapture() {
    }

    /** State recorded when the boot include starts; everything here is excluded from the snapshot. */
    record PreBoot(Map<String, String> globals, Map<String, String> constants,
                   IdentityHashMap<PhpClass, String> classStates, Set<String> functions,
                   Set<String> classes, Set<String> includedFiles, boolean outputStarted,
                   int headerCount, int status) {

        boolean hasGlobal(String name) {
            return globals.containsKey(name);
        }

        boolean hasConstant(String name) {
            return constants.containsKey(name);
        }
    }

    static PreBoot beginBoot(PhpContext ctx) {
        Map<String, String> globals = new LinkedHashMap<>();
        for (PhpContext.SnapshotGlobal global : ctx.snapshotGlobals()) {
            globals.put(global.name(), StateFingerprint.digest(global.cell().getValue()));
        }
        Map<String, String> constants = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : ctx.snapshotConstants().entrySet()) {
            constants.put(entry.getKey(), StateFingerprint.digest(entry.getValue()));
        }
        IdentityHashMap<PhpClass, String> classStates = new IdentityHashMap<>();
        Set<String> classes = new LinkedHashSet<>(ctx.snapshotClasses().keySet());
        for (Map.Entry<String, PhpClass> entry : ctx.snapshotClasses().entrySet()) {
            PhpClass cls = entry.getValue();
            if (!classStates.containsKey(cls)) {
                PhpClass.RequestState state = ctx.classStateOrNull(cls);
                classStates.put(cls, state == null ? "" : StateFingerprint.classStateDigest(state));
            }
        }
        Set<String> functions = new LinkedHashSet<>();
        for (PhpFunction fn : ctx.snapshotDeclaredFunctions()) {
            functions.add(fn.lowerName());
        }
        Set<String> included = new LinkedHashSet<>(ctx.snapshotIncludedFiles());
        return new PreBoot(globals, constants, classStates, functions, classes, included,
                ctx.outputStarted(), ctx.request().responseHeaders.size(), ctx.request().status);
    }

    static Snapshot capture(PhpContext ctx, PreBoot pre) {
        List<String> reasons = refusalReasons(ctx, pre);
        if (!reasons.isEmpty()) {
            throw new SnapshotRefused(reasons);
        }
        return new Builder(ctx, pre).build();
    }

    // ---------------------------------------------------------------- refusal checks

    private static List<String> refusalReasons(PhpContext ctx, PreBoot pre) {
        Set<String> reasons = new LinkedHashSet<>();
        if (ctx.outputStarted() && !pre.outputStarted()) {
            reasons.add(OUTPUT_WRITTEN);
        }
        if (!ctx.outputBuffers().isEmpty()) {
            reasons.add(OUTPUT_BUFFER_OPEN);
        }
        if (ctx.request().responseHeaders.size() > pre.headerCount() || ctx.request().status != pre.status()) {
            reasons.add(HEADERS_SET);
        }
        reasons.addAll(ContextState.snapshotRefusals(ctx));

        Map<String, PhpReference> globals = new LinkedHashMap<>();
        for (PhpContext.SnapshotGlobal global : ctx.snapshotGlobals()) {
            globals.put(global.name(), global.cell());
        }
        for (Map.Entry<String, String> entry : pre.globals().entrySet()) {
            PhpReference cell = globals.get(entry.getKey());
            if (cell == null) {
                reasons.add("boot removed the pre-boot global $" + entry.getKey());
            } else if (!StateFingerprint.digest(cell.getValue()).equals(entry.getValue())) {
                reasons.add("boot changed the pre-boot global $" + entry.getKey());
            }
        }
        for (Map.Entry<String, String> entry : pre.constants().entrySet()) {
            Object value = ctx.readUserConstant(entry.getKey());
            if (value == null) {
                reasons.add("boot removed the pre-boot constant " + entry.getKey());
            } else if (!StateFingerprint.digest(value).equals(entry.getValue())) {
                reasons.add("boot changed the pre-boot constant " + entry.getKey());
            }
        }
        for (String name : pre.functions()) {
            if (ctx.lookupFunction(name) == null) {
                reasons.add("boot removed the pre-boot function " + name + "()");
            }
        }
        for (Map.Entry<PhpClass, String> entry : pre.classStates().entrySet()) {
            PhpClass cls = entry.getKey();
            if (ctx.lookupClass(cls.template().lowerName()) == null) {
                reasons.add("boot removed the pre-boot class " + cls.name());
                continue;
            }
            PhpClass.RequestState state = ctx.classStateOrNull(cls);
            String now = state == null ? "" : StateFingerprint.classStateDigest(state);
            if (!now.equals(entry.getValue())) {
                reasons.add("boot changed the state of the pre-boot class " + cls.name());
            }
        }
        for (String path : pre.includedFiles()) {
            if (!ctx.isIncluded(path)) {
                reasons.add("boot removed the pre-boot include " + path);
            }
        }

        Scanner scanner = new Scanner();
        for (PhpContext.SnapshotGlobal global : ctx.snapshotGlobals()) {
            if (!pre.hasGlobal(global.name())) {
                scanner.value(global.cell());
            }
        }
        for (Map.Entry<String, Object> entry : ctx.snapshotConstants().entrySet()) {
            if (!pre.hasConstant(entry.getKey())) {
                scanner.value(entry.getValue());
            }
        }
        for (Map.Entry<PhpClass, PhpClass.RequestState> entry : ctx.snapshotClassStates()) {
            if (!pre.classStates().containsKey(entry.getKey())) {
                scanner.classState(entry.getValue());
            }
        }
        for (Object callable : ctx.snapshotAutoloaders()) {
            scanner.value(callable);
        }
        for (PhpContext.ShutdownEntry entry : ctx.snapshotShutdownFunctions()) {
            scanner.value(entry.callable());
            for (Object arg : entry.args()) {
                scanner.value(arg);
            }
        }
        for (PhpContext.ErrorHandlerEntry entry : ctx.snapshotErrorHandlers()) {
            scanner.value(entry.callable());
        }
        for (Object handler : ctx.snapshotExceptionHandlers()) {
            scanner.value(handler);
        }
        for (Map.Entry<Object, Map<String, PhpReference>> entry : ctx.snapshotStaticLocals()) {
            if (entry.getKey() != ctx) {
                scanner.value(entry.getKey());
            }
            for (PhpReference cell : entry.getValue().values()) {
                scanner.value(cell);
            }
        }
        Object substitute = ContextState.snapshot(ctx).mbSubstitute();
        scanner.value(substitute);

        reasons.addAll(scanner.reasons);
        return new ArrayList<>(reasons);
    }

    /** Finds state the copier cannot represent; it never builds anything. */
    private static final class Scanner {
        private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        private final Set<String> reasons = new LinkedHashSet<>();
        private final IdentityHashMap<PhpArray, Boolean> pureMemo = new IdentityHashMap<>();

        void classState(PhpClass.RequestState state) {
            PhpClass.RequestState.StateSnapshot snap = state.captureState();
            for (PhpClass.RequestState.StaticValue value : snap.statics().values()) {
                value(value.value());
            }
            if (snap.constants() != null) {
                for (Object value : snap.constants().values()) {
                    value(value);
                }
            }
            if (snap.defaultValues() != null) {
                for (Object value : snap.defaultValues()) {
                    value(value);
                }
            }
        }

        void value(Object v) {
            if (v == null || v == PhpNull.INSTANCE || v instanceof String || v instanceof Long
                    || v instanceof Integer || v instanceof Double || v instanceof Boolean
                    || v instanceof PhpClass || v instanceof PhpFunction
                    || v instanceof dev.phpruntime.nodes.PhpFunctionRootNode) {
                return;
            }
            if (v instanceof PhpStringBuffer buffer) {
                value(buffer.asString());
                return;
            }
            if (v instanceof PhpReference ref) {
                if (seen.put(ref, Boolean.TRUE) == null) {
                    value(ref.getValue());
                }
                return;
            }
            if (v instanceof PhpResource resource) {
                if (!resource.isClosed()) {
                    reasons.add(OPEN_RESOURCE);
                }
                return;
            }
            if (v instanceof PhpArray array) {
                if (seen.put(array, Boolean.TRUE) != null || isPure(array)) {
                    return;
                }
                for (Map.Entry<Object, Object> entry : array.rawEntries()) {
                    value(entry.getValue());
                }
                return;
            }
            if (v instanceof PhpObject object) {
                if (seen.put(object, Boolean.TRUE) != null) {
                    return;
                }
                PhpClosure closure = object.closure();
                if (closure != null) {
                    value(closure.boundThis());
                    if (closure.captures() != null) {
                        for (Object capture : closure.captures()) {
                            value(capture);
                        }
                    }
                }
                PhpNativeState nativeState = object.nativeState();
                if (nativeState != null) {
                    scanNative(nativeState);
                }
                Object[] keys = DynamicObject.GetKeyArrayNode.getUncached().execute(object);
                for (Object key : keys) {
                    Object raw = DynamicObject.GetNode.getUncached().execute(object, key, PhpNull.INSTANCE);
                    if (raw != PhpClass.UNINITIALIZED) {
                        value(raw);
                    }
                }
                return;
            }
            if (v instanceof PhpClosure closure) {
                value(closure.boundThis());
                if (closure.captures() != null) {
                    for (Object capture : closure.captures()) {
                        value(capture);
                    }
                }
                return;
            }
            if (v instanceof PhpGenerator) {
                reasons.add(LIVE_GENERATOR);
                return;
            }
            if (v instanceof PhpNativeState) {
                reasons.add("native state of " + v.getClass().getSimpleName() + " is not supported");
                return;
            }
            reasons.add("value of type " + v.getClass().getName() + " is not supported");
        }

        private void scanNative(PhpNativeState state) {
            if (state instanceof PhpGenerator generator) {
                reasons.add(generator.isClosed() ? "native state of Generator is not supported" : LIVE_GENERATOR);
                return;
            }
            String name = state.getClass().getSimpleName();
            switch (name) {
                case "DateTimeState", "DateTimeZoneState", "DateIntervalState", "SplFileState" -> {
                }
                case "SplArrayState" -> {
                    // The storage is public; walk whatever it points at.
                    if (state instanceof dev.phpruntime.runtime.SplArrayState arrayState) {
                        value(arrayState.storage.array);
                        value(arrayState.storage.object);
                    } else {
                        reasons.add("native state of " + name + " is not supported");
                    }
                }
                case "DirectoryState" -> reasons.add(OPEN_RESOURCE);
                default -> reasons.add("native state of " + name + " is not supported");
            }
        }

        boolean isPure(PhpArray array) {
            if (array.isImmutable()) {
                return true;
            }
            Boolean known = pureMemo.get(array);
            if (known != null) {
                return known;
            }
            // Mark first so a pathological cycle cannot recurse; references make an array impure
            // anyway, so this only guards the checker.
            pureMemo.put(array, Boolean.FALSE);
            for (Map.Entry<Object, Object> entry : array.rawEntries()) {
                Object value = entry.getValue();
                if (value == null || value == PhpNull.INSTANCE || value instanceof String
                        || value instanceof Long || value instanceof Integer || value instanceof Double
                        || value instanceof Boolean || value instanceof PhpStringBuffer) {
                    continue;
                }
                if (value instanceof PhpArray inner && isPure(inner)) {
                    continue;
                }
                return false;
            }
            pureMemo.put(array, Boolean.TRUE);
            return true;
        }
    }

    // ---------------------------------------------------------------- building

    private static final class Builder {
        private final PhpContext ctx;
        private final PreBoot pre;
        private final IdentityHashMap<Object, Object> mapped = new IdentityHashMap<>();
        private final IdentityHashMap<PhpArray, Boolean> pureMemo = new IdentityHashMap<>();

        Builder(PhpContext ctx, PreBoot pre) {
            this.ctx = ctx;
            this.pre = pre;
        }

        Snapshot build() {
            List<Snapshot.Global> globals = new ArrayList<>();
            for (PhpContext.SnapshotGlobal global : ctx.snapshotGlobals()) {
                if (pre.hasGlobal(global.name())) {
                    continue;
                }
                globals.add(new Snapshot.Global(global.name(), global.id(), global.dynamic(),
                        ref(global.cell())));
            }

            Map<String, Object> constants = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ctx.snapshotConstants().entrySet()) {
                if (!pre.hasConstant(entry.getKey())) {
                    constants.put(entry.getKey(), value(entry.getValue()));
                }
            }

            List<PhpFunction> functions = new ArrayList<>();
            for (PhpFunction fn : ctx.snapshotDeclaredFunctions()) {
                if (!pre.functions().contains(fn.lowerName())) {
                    functions.add(fn);
                }
            }

            Map<String, PhpClass> classes = new LinkedHashMap<>();
            for (Map.Entry<String, PhpClass> entry : ctx.snapshotClasses().entrySet()) {
                if (!pre.classes().contains(entry.getKey())) {
                    classes.put(entry.getKey(), entry.getValue());
                }
            }

            List<Snapshot.ClassStateNode> classStates = new ArrayList<>();
            for (Map.Entry<PhpClass, PhpClass.RequestState> entry : ctx.snapshotClassStates()) {
                if (!pre.classStates().containsKey(entry.getKey())) {
                    classStates.add(new Snapshot.ClassStateNode(entry.getKey(), state(entry.getValue())));
                }
            }

            Set<String> included = new LinkedHashSet<>();
            for (String path : ctx.snapshotIncludedFiles()) {
                if (!pre.includedFiles().contains(path)) {
                    included.add(path);
                }
            }

            List<Object> autoloaders = new ArrayList<>();
            for (Object callable : ctx.snapshotAutoloaders()) {
                autoloaders.add(value(callable));
            }

            List<Snapshot.ShutdownEntry> shutdown = new ArrayList<>();
            for (PhpContext.ShutdownEntry entry : ctx.snapshotShutdownFunctions()) {
                Object[] args = new Object[entry.args().length];
                for (int i = 0; i < args.length; i++) {
                    args[i] = value(entry.args()[i]);
                }
                shutdown.add(new Snapshot.ShutdownEntry(value(entry.callable()), args));
            }

            List<Snapshot.ErrorHandlerEntry> handlers = new ArrayList<>();
            for (PhpContext.ErrorHandlerEntry entry : ctx.snapshotErrorHandlers()) {
                handlers.add(new Snapshot.ErrorHandlerEntry(value(entry.callable()), entry.errorLevels()));
            }

            List<Object> exceptionHandlers = new ArrayList<>();
            for (Object handler : ctx.snapshotExceptionHandlers()) {
                exceptionHandlers.add(value(handler));
            }

            List<Snapshot.StaticEntry> staticLocals = new ArrayList<>();
            for (Map.Entry<Object, Map<String, PhpReference>> entry : ctx.snapshotStaticLocals()) {
                Map<String, Object> cells = new LinkedHashMap<>();
                for (Map.Entry<String, PhpReference> cell : entry.getValue().entrySet()) {
                    cells.put(cell.getKey(), ref(cell.getValue()));
                }
                staticLocals.add(new Snapshot.StaticEntry(owner(entry.getKey()), cells));
            }

            return new Snapshot(globals, constants, functions, classes, classStates, included,
                    autoloaders, shutdown, handlers, exceptionHandlers, staticLocals,
                    ContextState.snapshot(ctx), ctx.snapshotNextObjectId(), ctx.snapshotNextAnonymousId());
        }

        private Object owner(Object owner) {
            if (owner == ctx) {
                return Snapshot.ContextOwner.INSTANCE;
            }
            if (owner instanceof PhpObject object) {
                return object(object);
            }
            if (owner instanceof dev.phpruntime.nodes.PhpFunctionRootNode
                    || owner instanceof PhpFunction) {
                return owner;
            }
            throw new SnapshotRefused(List.of("static local owner of type "
                    + owner.getClass().getName() + " is not supported"));
        }

        private PhpClass.RequestState.StateSnapshot state(PhpClass.RequestState state) {
            PhpClass.RequestState.StateSnapshot snap = state.captureState();
            Map<String, PhpClass.RequestState.StaticValue> statics = new LinkedHashMap<>();
            for (Map.Entry<String, PhpClass.RequestState.StaticValue> entry : snap.statics().entrySet()) {
                PhpClass.RequestState.StaticValue value = entry.getValue();
                statics.put(entry.getKey(), new PhpClass.RequestState.StaticValue(
                        value.initialized(), value(value.value())));
            }
            Map<String, Object> constants = null;
            if (snap.constants() != null) {
                constants = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : snap.constants().entrySet()) {
                    constants.put(entry.getKey(), value(entry.getValue()));
                }
            }
            Object[] defaults = null;
            if (snap.defaultValues() != null) {
                defaults = new Object[snap.defaultValues().length];
                for (int i = 0; i < defaults.length; i++) {
                    defaults[i] = value(snap.defaultValues()[i]);
                }
            }
            return new PhpClass.RequestState.StateSnapshot(statics, constants, defaults);
        }

        private Object value(Object v) {
            if (v == null || v == PhpNull.INSTANCE || v instanceof String || v instanceof Long
                    || v instanceof Integer || v instanceof Double || v instanceof Boolean
                    || v instanceof PhpClass || v instanceof PhpFunction
                    || v instanceof dev.phpruntime.nodes.PhpFunctionRootNode) {
                return v instanceof Integer n ? (Object) (long) n : v;
            }
            if (v instanceof PhpStringBuffer buffer) {
                return buffer.asString();
            }
            if (v instanceof PhpReference ref) {
                return ref(ref);
            }
            if (v instanceof PhpObject object) {
                return object(object);
            }
            if (v instanceof PhpResource resource) {
                return new Snapshot.ResourceNode(resource.id(), resource.type(), resource.isClosed());
            }
            if (v instanceof PhpArray array) {
                if (isPure(array)) {
                    freezePure(array);
                    return array;
                }
                return array(array);
            }
            if (v instanceof PhpClosure closure) {
                return closure(closure);
            }
            throw new SnapshotRefused(List.of("value of type " + v.getClass().getName()
                    + " is not supported"));
        }

        private Snapshot.RefNode ref(PhpReference ref) {
            Snapshot.RefNode node = (Snapshot.RefNode) mapped.get(ref);
            if (node != null) {
                return node;
            }
            node = new Snapshot.RefNode();
            node.aliased = ref.isAliased();
            PhpReference.CellCheck check = ref.cellCheckForSnapshot();
            if (check != null) {
                node.check = new Snapshot.CheckNode(check.declaredType(), check.strict(),
                        check.selfClass(), check.owner(), check.property());
            }
            mapped.put(ref, node);
            node.value = value(ref.getValue());
            return node;
        }

        private Snapshot.ArrayNode array(PhpArray array) {
            Snapshot.ArrayNode node = (Snapshot.ArrayNode) mapped.get(array);
            if (node != null) {
                return node;
            }
            node = new Snapshot.ArrayNode();
            mapped.put(array, node);
            List<Map.Entry<Object, Object>> entries = array.rawEntries();
            node.keys = new Object[entries.size()];
            node.values = new Object[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                node.keys[i] = entries.get(i).getKey();
                node.values[i] = value(entries.get(i).getValue());
            }
            return node;
        }

        private Snapshot.ObjectNode object(PhpObject object) {
            Snapshot.ObjectNode node = (Snapshot.ObjectNode) mapped.get(object);
            if (node != null) {
                return node;
            }
            node = new Snapshot.ObjectNode(object.phpClass(), object.id());
            mapped.put(object, node);
            if (object.closure() != null) {
                node.closure = closure(object.closure());
            }
            if (object.nativeState() != null) {
                node.nativeState = nativeState(object.nativeState());
            }
            Object[] keys = DynamicObject.GetKeyArrayNode.getUncached().execute(object);
            node.keys = keys;
            node.values = new Object[keys.length];
            for (int i = 0; i < keys.length; i++) {
                Object raw = DynamicObject.GetNode.getUncached().execute(object, keys[i], PhpNull.INSTANCE);
                node.values[i] = raw == PhpClass.UNINITIALIZED ? raw : value(raw);
            }
            return node;
        }

        private Snapshot.ClosureNode closure(PhpClosure closure) {
            Snapshot.ClosureNode node = new Snapshot.ClosureNode();
            node.kind = closure.kind();
            node.callTarget = closure.callTarget();
            node.builtin = closure.builtin();
            node.boundThis = value(closure.boundThis());
            node.calledClass = closure.calledClass();
            node.traitSelf = closure.traitSelf();
            node.magicMethodName = closure.magicMethodName();
            Object[] captures = closure.captures();
            if (captures != null) {
                node.captures = new Object[captures.length];
                for (int i = 0; i < captures.length; i++) {
                    node.captures[i] = value(captures[i]);
                }
            }
            return node;
        }

        private Object nativeState(PhpNativeState state) {
            if (state instanceof PhpGenerator generator) {
                throw new SnapshotRefused(List.of(generator.isClosed()
                        ? "native state of Generator is not supported" : LIVE_GENERATOR));
            }
            String name = state.getClass().getSimpleName();
            if (name.equals("SplArrayState")) {
                dev.phpruntime.runtime.SplArrayState arrayState = (dev.phpruntime.runtime.SplArrayState) state;
                return new Snapshot.NativeArrayNode(value(arrayState.storage.array),
                        value(arrayState.storage.object), arrayState.flags, arrayState.position,
                        arrayState.iteratorClass);
            }
            // DateTime*, DateInterval and SplFileInfo states own no request objects; copy()
            // produces a prototype whose copy() is taken again for each restore.
            return state.copy();
        }

        private boolean isPure(PhpArray array) {
            if (array.isImmutable()) {
                return true;
            }
            Boolean known = pureMemo.get(array);
            if (known != null) {
                return known;
            }
            pureMemo.put(array, Boolean.FALSE);
            for (Map.Entry<Object, Object> entry : array.rawEntries()) {
                Object value = entry.getValue();
                if (value == null || value == PhpNull.INSTANCE || value instanceof String
                        || value instanceof Long || value instanceof Integer || value instanceof Double
                        || value instanceof Boolean || value instanceof PhpStringBuffer) {
                    continue;
                }
                if (value instanceof PhpArray inner && isPure(inner)) {
                    continue;
                }
                return false;
            }
            pureMemo.put(array, Boolean.TRUE);
            return true;
        }

        private void freezePure(PhpArray array) {
            if (array.isImmutable()) {
                return;
            }
            for (Object raw : array.rawValues()) {
                if (raw instanceof PhpArray inner) {
                    freezePure(inner);
                }
            }
            array.freeze();
        }
    }

}
