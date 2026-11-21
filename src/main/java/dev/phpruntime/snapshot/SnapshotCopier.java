package dev.phpruntime.snapshot;

import dev.phpruntime.PhpContext;
import dev.phpruntime.builtins.ContextState;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClosure;
import dev.phpruntime.runtime.EnumCaseState;
import dev.phpruntime.runtime.PhpNativeState;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpResource;
import dev.phpruntime.runtime.SplArrayState;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Fills one fresh request context from a snapshot. One instance per restore; the identity map
 * makes the copy preserve aliasing, cycles and identity, and every copied object keeps the
 * {@code spl_object_id} it had at capture.
 */
final class SnapshotCopier {

    private final PhpContext ctx;
    private final IdentityHashMap<Object, Object> copies = new IdentityHashMap<>();

    private SnapshotCopier(PhpContext ctx) {
        this.ctx = ctx;
    }

    static void restore(PhpContext ctx, Snapshot snapshot) {
        new SnapshotCopier(ctx).restoreAll(snapshot);
    }

    private void restoreAll(Snapshot snapshot) {
        ctx.restoreObjectIds(snapshot.nextObjectId, snapshot.nextAnonymousId);
        ctx.restoreEnumObjectId(snapshot.nextEnumObjectId);

        for (Map.Entry<String, PhpClass> entry : snapshot.classes.entrySet()) {
            ctx.restoreClass(entry.getKey(), entry.getValue());
        }
        ctx.restoreFunctions(snapshot.functionTable, snapshot.functions);
        for (String path : snapshot.includedFiles) {
            ctx.markIncluded(path);
        }
        for (Map.Entry<String, Object> entry : snapshot.constants.entrySet()) {
            ctx.restoreConstant(entry.getKey(), copyValue(entry.getValue()));
        }
        for (Snapshot.ClassStateNode node : snapshot.classStates) {
            node.cls().restoreState(ctx, state(node.state()));
        }

        for (Snapshot.Global global : snapshot.globals) {
            PhpReference cell = copyRef(global.cell());
            if (global.dynamic()) {
                ctx.restoreDynamicGlobal(global.name(), cell);
            } else {
                ctx.restoreGlobalCell(global.id(), cell);
            }
        }

        for (Object callable : snapshot.autoloaders) {
            ctx.restoreAutoload(copyValue(callable));
        }
        for (Snapshot.ShutdownEntry entry : snapshot.shutdownFunctions) {
            Object[] args = new Object[entry.args().length];
            for (int i = 0; i < args.length; i++) {
                args[i] = copyValue(entry.args()[i]);
            }
            ctx.registerShutdownFunction(copyValue(entry.callable()), args);
        }
        // The captured lists are top-first; push bottom-first to rebuild the same stacks.
        for (int i = snapshot.errorHandlers.size() - 1; i >= 0; i--) {
            Snapshot.ErrorHandlerEntry entry = snapshot.errorHandlers.get(i);
            ctx.setErrorHandler(copyValue(entry.callable()), entry.errorLevels());
        }
        for (int i = snapshot.exceptionHandlers.size() - 1; i >= 0; i--) {
            ctx.setExceptionHandler(copyValue(snapshot.exceptionHandlers.get(i)));
        }
        for (Snapshot.StaticEntry entry : snapshot.staticLocals) {
            Object owner = entry.owner() == Snapshot.ContextOwner.INSTANCE
                    ? ctx : copyValue(entry.owner());
            for (Map.Entry<String, Object> cell : entry.cells().entrySet()) {
                ctx.restoreStaticLocal(owner, cell.getKey(), copyRef((Snapshot.RefNode) cell.getValue()));
            }
        }

        ContextState.restore(ctx, snapshot.contextState);
        restoreEnvironment(snapshot.envAdditions);
    }

    /**
     * Applies what boot added to the environment on top of this request's own values. A key the
     * request defines wins, as it would have with the immutable dotenv loader: that loader skips
     * a variable defined in {@code $_SERVER}, {@code $_ENV} or {@code getenv()}, so a replayed
     * addition must not appear in any of the three. Keys boot wrote outside the loader (only one
     * of the three structures) are replayed into that structure alone, since php would have
     * written them regardless.
     */
    private void restoreEnvironment(Snapshot.EnvAdditions additions) {
        if (additions == null) {
            return;
        }
        if (additions.environmentGlobal()) {
            ctx.exposeAutoGlobalById(ctx.language().globalId("_ENV"));
        }
        if (additions.isEmpty()) {
            return;
        }
        Set<String> loaderKeys = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : additions.putenv().entrySet()) {
            if (containsKey(additions.server(), entry.getKey())
                    || containsKey(additions.environment(), entry.getKey())) {
                loaderKeys.add(entry.getKey());
            }
        }
        // Decide against the request as it arrived, before any addition is written: a key that
        // is replayed into $_SERVER must not then make its own $_ENV replay look request-supplied.
        Set<String> defined = new HashSet<>();
        for (String name : loaderKeys) {
            if (definedInRequest(name)) {
                defined.add(name);
            }
        }
        for (Map.Entry<Object, Object> entry : additions.server().entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (loaderKeys.contains(key) ? defined.contains(key) : hasInGlobal("_SERVER", entry.getKey())) {
                continue;
            }
            putInGlobal("_SERVER", entry.getKey(), copyValue(entry.getValue()));
        }
        for (Map.Entry<Object, Object> entry : additions.environment().entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (loaderKeys.contains(key) ? defined.contains(key) : hasInGlobal("_ENV", entry.getKey())) {
                continue;
            }
            putInGlobal("_ENV", entry.getKey(), copyValue(entry.getValue()));
        }
        for (Map.Entry<String, String> entry : additions.putenv().entrySet()) {
            if (loaderKeys.contains(entry.getKey())) {
                if (defined.contains(entry.getKey())) {
                    continue;
                }
            } else if (ContextState.envOverrides(ctx).containsKey(entry.getKey())) {
                continue;
            }
            ContextState.putEnvOverride(ctx, entry.getKey(), entry.getValue());
        }
    }

    private static boolean containsKey(Map<Object, Object> map, String key) {
        for (Object candidate : map.keySet()) {
            if (String.valueOf(candidate).equals(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasInGlobal(String name, Object key) {
        Object value = ctx.peekGlobal(name);
        return value instanceof PhpArray array && array.containsKey(key);
    }

    private void putInGlobal(String name, Object key, Object value) {
        Object current = ctx.peekGlobal(name);
        if (current instanceof PhpArray array) {
            array.put(key, value);
        }
    }

    /** Whether the request already defines a variable in any of the loader's three readers. */
    private boolean definedInRequest(String name) {
        if (hasInGlobal("_SERVER", name) || hasInGlobal("_ENV", name)) {
            return true;
        }
        if (System.getenv(name) != null) {
            return true;
        }
        return ContextState.envOverrides(ctx).containsKey(name);
    }

    private PhpClass.RequestState.StateSnapshot state(PhpClass.RequestState.StateSnapshot in) {
        Map<String, PhpClass.RequestState.StaticValue> statics = new LinkedHashMap<>();
        for (Map.Entry<String, PhpClass.RequestState.StaticValue> entry : in.statics().entrySet()) {
            PhpClass.RequestState.StaticValue value = entry.getValue();
            statics.put(entry.getKey(), new PhpClass.RequestState.StaticValue(
                    value.initialized(), copyValue(value.value())));
        }
        Map<String, Object> constants = null;
        if (in.constants() != null) {
            constants = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : in.constants().entrySet()) {
                constants.put(entry.getKey(), copyValue(entry.getValue()));
            }
        }
        Object[] defaults = null;
        if (in.defaultValues() != null) {
            defaults = new Object[in.defaultValues().length];
            for (int i = 0; i < defaults.length; i++) {
                defaults[i] = copyValue(in.defaultValues()[i]);
            }
        }
        Map<String, Object> enumCases = null;
        if (in.enumCases() != null) {
            enumCases = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : in.enumCases().entrySet()) {
                enumCases.put(entry.getKey(), copyValue(entry.getValue()));
            }
        }
        return new PhpClass.RequestState.StateSnapshot(statics, constants, defaults, enumCases);
    }

    private Object copyValue(Object v) {
        if (v instanceof Integer n) {
            return (long) n;
        }
        if (v == null || v == PhpNull.INSTANCE || v == PhpClass.UNINITIALIZED
                || v instanceof String || v instanceof Long || v instanceof Double
                || v instanceof Boolean || v instanceof PhpClass
                || v instanceof dev.phpruntime.nodes.PhpFunctionRootNode
                || v instanceof dev.phpruntime.runtime.PhpFunction) {
            return v;
        }
        if (v instanceof Snapshot.RefNode ref) {
            return copyRef(ref);
        }
        if (v instanceof Snapshot.ObjectNode object) {
            return copyObject(object);
        }
        if (v instanceof Snapshot.ArrayNode array) {
            return copyArray(array);
        }
        if (v instanceof Snapshot.ClosureNode closure) {
            return copyClosure(closure);
        }
        if (v instanceof Snapshot.ResourceNode resource) {
            PhpResource copy = new PhpResource(resource.id(), resource.type(), null);
            if (resource.closed()) {
                copy.close();
            }
            return copy;
        }
        if (v instanceof Snapshot.NativeArrayNode nativeArray) {
            return copyNativeArray(nativeArray);
        }
        if (v instanceof Snapshot.NativeStateNode nativeState) {
            return ((RestoredNativeState) nativeState.state()).restoreNativeState(ctx, this::copyValue);
        }
        if (v instanceof PhpNativeState prototype) {
            return prototype.copy();
        }
        if (v instanceof PhpArray frozen) {
            // A frozen pure array: shared by every restore, and every write copies.
            return frozen;
        }
        throw new IllegalStateException("unsupported snapshot value " + v.getClass().getName());
    }

    private PhpReference copyRef(Snapshot.RefNode node) {
        PhpReference existing = (PhpReference) copies.get(node);
        if (existing != null) {
            return existing;
        }
        PhpReference cell = PhpReference.local(PhpNull.INSTANCE);
        copies.put(node, cell);
        cell.setValue(copyValue(node.value));
        if (node.aliased) {
            cell.alias();
        }
        if (node.check != null) {
            Snapshot.CheckNode check = node.check;
            cell.setCellCheck(new PhpReference.CellCheck(check.type(), ctx, check.strict(),
                    check.selfClass(), check.owner(), check.property()));
        }
        return cell;
    }

    private PhpArray copyArray(Snapshot.ArrayNode node) {
        PhpArray existing = (PhpArray) copies.get(node);
        if (existing != null) {
            return existing;
        }
        // Sized up front so filling it never grows or rehashes.
        PhpArray array = PhpArray.withCapacity(node.keys.length,
                node.keys.length > 0 && node.keys[0] instanceof String);
        copies.put(node, array);
        for (int i = 0; i < node.keys.length; i++) {
            array.put(node.keys[i], copyValue(node.values[i]));
        }
        return array;
    }

    private PhpObject copyObject(Snapshot.ObjectNode node) {
        PhpObject existing = (PhpObject) copies.get(node);
        if (existing != null) {
            return existing;
        }
        // Enum cases are canonical per request: E::CASE, E::from(), cases() and every value
        // reached through the snapshot must be this one object, with the id it had at capture.
        EnumCaseState enumCase = node.nativeState instanceof EnumCaseState state ? state : null;
        if (enumCase != null) {
            Object canonical = ctx.classState(node.cls).enumCase(enumCase.caseName());
            if (canonical instanceof PhpObject caseObject) {
                copies.put(node, caseObject);
                return caseObject;
            }
        }
        PhpObject object = new PhpObject(node.cls, node.id);
        copies.put(node, object);
        // The object exists in the map before its closure and properties are copied, so a
        // closure bound to it (or a property pointing back to it) resolves to this instance.
        if (node.closure != null) {
            object.setClosure(copyClosure(node.closure));
        }
        if (node.nativeState != null) {
            object.setNativeState((PhpNativeState) copyValue(node.nativeState));
        }
        Object[] values = new Object[node.keys.length];
        for (int i = 0; i < values.length; i++) {
            Object raw = node.values[i];
            values[i] = raw == PhpClass.UNINITIALIZED ? raw : copyValue(raw);
        }
        node.putAll().execute(object, node.keys, values);
        if (enumCase != null) {
            ctx.classState(node.cls).putEnumCase(enumCase.caseName(), object);
        }
        return object;
    }

    private PhpClosure copyClosure(Snapshot.ClosureNode node) {
        Object[] captures = null;
        if (node.captures != null) {
            captures = new Object[node.captures.length];
            for (int i = 0; i < captures.length; i++) {
                captures[i] = copyValue(node.captures[i]);
            }
        }
        return new PhpClosure(node.kind, node.callTarget, node.builtin,
                copyValue(node.boundThis), node.calledClass, node.traitSelf, captures,
                node.magicMethodName);
    }

    private SplArrayState copyNativeArray(Snapshot.NativeArrayNode node) {
        SplArrayState state;
        if (node.array() != null) {
            state = new SplArrayState(copyValue(node.array()), node.flags());
        } else {
            state = new SplArrayState(copyValue(node.object()), node.flags());
        }
        state.position = node.position();
        state.storage.version = node.version();
        state.storage.size = node.size();
        if (node.iteratorClass() != null) {
            state.iteratorClass = node.iteratorClass();
        }
        return state;
    }
}
