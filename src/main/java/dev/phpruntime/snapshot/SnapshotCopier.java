package dev.phpruntime.snapshot;

import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.builtins.ContextState;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClosure;
import dev.phpruntime.runtime.PhpNativeState;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpResource;
import dev.phpruntime.runtime.SplArrayState;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

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

        for (Map.Entry<String, PhpClass> entry : snapshot.classes.entrySet()) {
            ctx.restoreClass(entry.getKey(), entry.getValue());
        }
        for (var fn : snapshot.functions) {
            ctx.restoreFunction(fn);
        }
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
        return new PhpClass.RequestState.StateSnapshot(statics, constants, defaults);
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
        PhpArray array = new PhpArray();
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
        DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
        for (int i = 0; i < node.keys.length; i++) {
            Object raw = node.values[i];
            put.execute(object, node.keys[i], raw == PhpClass.UNINITIALIZED ? raw : copyValue(raw));
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
        if (node.iteratorClass() != null) {
            state.iteratorClass = node.iteratorClass();
        }
        return state;
    }
}
