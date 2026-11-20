package dev.phpruntime.snapshot;

import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.builtins.ContextStateSnapshot;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpRootNode;
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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A deterministic hash of request state, per component. It is the Java equivalent of
 * {@code blitz-wp/tools/snapshot-probe.php}: shared objects are numbered in visit order, so two
 * runs that built the same graph hash the same even when their object allocations differed.
 */
final class StateFingerprint {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private long hash = FNV_OFFSET;
    private final IdentityHashMap<Object, Integer> visited = new IdentityHashMap<>();
    private int nextVisit;

    private void mix(int value) {
        hash ^= (value & 0xff);
        hash *= FNV_PRIME;
        hash ^= ((value >>> 8) & 0xff);
        hash *= FNV_PRIME;
        hash ^= ((value >>> 16) & 0xff);
        hash *= FNV_PRIME;
        hash ^= ((value >>> 24) & 0xff);
        hash *= FNV_PRIME;
    }

    private void mix(long value) {
        for (int i = 0; i < 8; i++) {
            mix((int) (value >>> (i * 8)));
        }
    }

    private void text(String s) {
        mix(s.length());
        for (int i = 0; i < s.length(); i++) {
            mix(s.charAt(i));
        }
    }

    private int visit(Object value) {
        Integer id = visited.get(value);
        if (id == null) {
            id = nextVisit++;
            visited.put(value, id);
        }
        return id;
    }

    String digest() {
        return Long.toHexString(hash);
    }

    void value(Object v) {
        if (v == null) {
            mix('n');
            return;
        }
        if (v == PhpNull.INSTANCE) {
            mix('N');
            return;
        }
        if (v instanceof PhpStringBuffer buffer) {
            value(buffer.asString());
            return;
        }
        if (v instanceof Boolean b) {
            mix(b ? 'T' : 'F');
            return;
        }
        if (v instanceof Long n) {
            mix('i');
            mix(n);
            return;
        }
        if (v instanceof Integer n) {
            mix('i');
            mix((long) n);
            return;
        }
        if (v instanceof Double d) {
            mix('d');
            mix(Double.doubleToLongBits(d));
            return;
        }
        if (v instanceof String s) {
            mix('s');
            text(s);
            return;
        }
        if (v instanceof PhpClass cls) {
            mix('C');
            text(cls.template().lowerName());
            return;
        }
        if (v instanceof PhpFunction fn) {
            mix('F');
            text(fn.lowerName());
            return;
        }
        if (v instanceof PhpFunctionRootNode root) {
            mix('R');
            text(root.qualifiedCallableName());
            return;
        }
        if (v instanceof PhpRootNode root) {
            mix('R');
            text(root.getName());
            return;
        }
        if (v instanceof PhpResource resource) {
            mix('E');
            mix(resource.id());
            text(resource.type());
            mix(resource.isClosed() ? 'c' : 'o');
            return;
        }
        if (v instanceof PhpReference ref) {
            mix('r');
            int id = visit(ref);
            mix(id);
            if (id != nextVisit - 1) {
                return;
            }
            mix(ref.isAliased() ? 'a' : 'v');
            value(ref.getValue());
            return;
        }
        if (v instanceof PhpArray array) {
            mix('a');
            int id = visit(array);
            mix(id);
            if (id != nextVisit - 1) {
                return;
            }
            mix(array.size());
            for (Map.Entry<Object, Object> entry : array.rawEntries()) {
                value(entry.getKey());
                value(entry.getValue());
            }
            return;
        }
        if (v instanceof PhpObject object) {
            mix('o');
            int id = visit(object);
            mix(id);
            if (id != nextVisit - 1) {
                return;
            }
            text(object.phpClass().template().lowerName());
            object(object);
            return;
        }
        if (v instanceof PhpClosure closure) {
            mix('c');
            int id = visit(closure);
            mix(id);
            if (id != nextVisit - 1) {
                return;
            }
            text(closure.kind().name());
            text(targetName(closure.callTarget()));
            value(closure.boundThis());
            value(closure.calledClass());
            value(closure.traitSelf());
            Object[] captures = closure.captures();
            mix(captures == null ? -1 : captures.length);
            if (captures != null) {
                for (Object capture : captures) {
                    value(capture);
                }
            }
            if (closure.magicMethodName() != null) {
                text(closure.magicMethodName());
            }
            return;
        }
        if (v instanceof PhpGenerator generator) {
            mix('g');
            mix(generator.isClosed() ? 'c' : 'l');
            return;
        }
        if (v instanceof PhpNativeState nativeState) {
            mix('h');
            text(nativeState.getClass().getName());
            return;
        }
        mix('?');
        text(v.getClass().getName());
    }

    private void object(PhpObject object) {
        PhpClosure closure = object.closure();
        if (closure != null) {
            value(closure);
        }
        PhpNativeState nativeState = object.nativeState();
        if (nativeState != null) {
            mix('h');
            text(nativeState.getClass().getName());
            if (nativeState instanceof dev.phpruntime.runtime.SplArrayState arrayState) {
                value(arrayState.storage.array);
                value(arrayState.storage.object);
            } else {
                Map<String, Object> debug = nativeState.debugProperties(object);
                if (debug != null) {
                    for (Map.Entry<String, Object> entry : new TreeMap<>(debug).entrySet()) {
                        text(entry.getKey());
                        value(entry.getValue());
                    }
                }
            }
        }
        Object[] keys = DynamicObject.GetKeyArrayNode.getUncached().execute(object);
        List<String> names = new ArrayList<>(keys.length);
        for (Object key : keys) {
            names.add(String.valueOf(key));
        }
        Collections.sort(names);
        for (String name : names) {
            Object raw = DynamicObject.GetNode.getUncached().execute(object, name, PhpNull.INSTANCE);
            text(name);
            if (raw == PhpClass.UNINITIALIZED) {
                mix('u');
            } else {
                value(raw);
            }
        }
    }

    private static String targetName(com.oracle.truffle.api.RootCallTarget target) {
        if (target == null) {
            return "";
        }
        com.oracle.truffle.api.nodes.RootNode root = target.getRootNode();
        String name = root != null ? root.getName() : "";
        com.oracle.truffle.api.source.SourceSection section = root != null ? root.getSourceSection() : null;
        if (section != null) {
            name += "@" + section.getStartLine();
        }
        return name;
    }

    // ---------------------------------------------------------------- entry points

    /** A digest of one value with its own visit-order numbering. */
    static String digest(Object value) {
        StateFingerprint f = new StateFingerprint();
        f.value(value);
        return f.digest();
    }

    static String classStateDigest(PhpClass.RequestState state) {
        StateFingerprint f = new StateFingerprint();
        f.classState(state);
        return f.digest();
    }

    private void classState(PhpClass.RequestState state) {
        PhpClass.RequestState.StateSnapshot snap = state.captureState();
        for (Map.Entry<String, PhpClass.RequestState.StaticValue> entry
                : new TreeMap<>(snap.statics()).entrySet()) {
            text(entry.getKey());
            mix(entry.getValue().initialized() ? 'i' : 'u');
            value(entry.getValue().value());
        }
        if (snap.constants() != null) {
            for (Map.Entry<String, Object> entry : new TreeMap<>(snap.constants()).entrySet()) {
                text(entry.getKey());
                value(entry.getValue());
            }
        }
        Object[] defaults = snap.defaultValues();
        if (defaults != null) {
            mix(defaults.length);
            for (Object value : defaults) {
                value(value);
            }
        }
    }

    /** The full boundary fingerprint, one component per piece of state. */
    static Map<String, String> fingerprint(PhpContext ctx) {
        Map<String, String> out = new TreeMap<>();

        List<PhpContext.SnapshotGlobal> globals = new ArrayList<>(ctx.snapshotGlobals());
        globals.sort(java.util.Comparator.comparing(PhpContext.SnapshotGlobal::name));
        for (PhpContext.SnapshotGlobal global : globals) {
            out.put("global:" + global.name(), digest(global.cell().getValue()));
        }

        for (Map.Entry<String, Object> entry : new TreeMap<>(ctx.snapshotConstants()).entrySet()) {
            out.put("constant:" + entry.getKey(), digest(entry.getValue()));
        }

        for (Map.Entry<PhpClass, PhpClass.RequestState> entry : ctx.snapshotClassStates()) {
            out.put("class:" + entry.getKey().template().lowerName(),
                    classStateDigest(entry.getValue()));
        }

        Map<String, String> statics = new TreeMap<>();
        for (Map.Entry<Object, Map<String, PhpReference>> entry : ctx.snapshotStaticLocals()) {
            StateFingerprint f = new StateFingerprint();
            for (Map.Entry<String, PhpReference> cell : new TreeMap<>(entry.getValue()).entrySet()) {
                f.text(cell.getKey());
                f.value(cell.getValue());
            }
            statics.put("static-locals:" + ownerName(ctx, entry.getKey()), f.digest());
        }
        out.putAll(statics);

        StateFingerprint autoloaders = new StateFingerprint();
        for (Object callable : ctx.snapshotAutoloaders()) {
            autoloaders.value(callable);
        }
        out.put("autoloaders", autoloaders.digest());

        StateFingerprint shutdown = new StateFingerprint();
        for (PhpContext.ShutdownEntry entry : ctx.snapshotShutdownFunctions()) {
            shutdown.value(entry.callable());
            shutdown.value(entry.args());
        }
        out.put("shutdown-functions", shutdown.digest());

        StateFingerprint errors = new StateFingerprint();
        for (PhpContext.ErrorHandlerEntry entry : ctx.snapshotErrorHandlers()) {
            errors.value(entry.callable());
            errors.mix(entry.errorLevels());
        }
        out.put("error-handlers", errors.digest());

        StateFingerprint exceptions = new StateFingerprint();
        for (Object handler : ctx.snapshotExceptionHandlers()) {
            exceptions.value(handler);
        }
        out.put("exception-handlers", exceptions.digest());

        ContextStateSnapshot state = dev.phpruntime.builtins.ContextState.snapshot(ctx);
        StateFingerprint timezone = new StateFingerprint();
        timezone.text(state.timezoneName());
        out.put("timezone", timezone.digest());
        StateFingerprint mb = new StateFingerprint();
        mb.text(state.mbEncoding());
        mb.value(state.mbSubstitute());
        for (String name : state.mbDetectOrder()) {
            mb.text(name);
        }
        out.put("mbstring", mb.digest());

        List<String> included = new ArrayList<>(ctx.snapshotIncludedFiles());
        Collections.sort(included);
        StateFingerprint includes = new StateFingerprint();
        for (String path : included) {
            includes.text(path);
        }
        out.put("included-files", includes.digest());

        return out;
    }

    /** Stable names for static-local owners: the context, a function root, or a closure object. */
    static String ownerName(PhpContext ctx, Object owner) {
        if (owner == ctx) {
            return "context";
        }
        if (owner instanceof PhpObject object) {
            return "closure#" + object.id();
        }
        if (owner instanceof PhpFunctionRootNode root) {
            return "function:" + root.qualifiedCallableName();
        }
        if (owner instanceof PhpFunction fn) {
            return "function:" + fn.lowerName();
        }
        return "owner:" + owner.getClass().getName();
    }

    private StateFingerprint() {
    }
}
