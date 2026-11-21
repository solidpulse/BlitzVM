package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import dev.phpruntime.PhpContext;
import dev.phpruntime.snapshot.RestoredNativeState;
import dev.phpruntime.snapshot.SnapshotNativeState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Object identity, not object equality, is the key for SplObjectStorage. */
public final class SplObjectStorageState implements PhpNativeState, SnapshotNativeState {
    public final LinkedHashMap<PhpObject, Object> objects = new LinkedHashMap<>();
    public int position;
    private List<PhpObject> keys;

    public void attach(PhpObject object, Object info) {
        if (!objects.containsKey(object)) { keys = null; }
        objects.put(object, PhpOps.copyOnAssign(info));
    }

    public void detach(PhpObject object) {
        objects.remove(object);
        keys = null;
    }

    @TruffleBoundary
    public PhpObject current() {
        if (keys == null) { keys = new ArrayList<>(objects.keySet()); }
        return position >= 0 && position < keys.size() ? keys.get(position) : null;
    }

    public void clear() {
        objects.clear();
        keys = null;
        position = 0;
    }

    @Override
    public PhpNativeState copy() {
        SplObjectStorageState copy = new SplObjectStorageState();
        for (var entry : objects.entrySet()) { copy.attach(entry.getKey(), entry.getValue()); }
        copy.position = position;
        return copy;
    }

    @Override
    public Object snapshotNativeState(Function<Object, Object> map) {
        List<Object[]> pairs = new ArrayList<>(objects.size());
        for (Map.Entry<PhpObject, Object> entry : objects.entrySet()) {
            pairs.add(new Object[]{map.apply(entry.getKey()), map.apply(entry.getValue())});
        }
        return new StorageSnapshot(pairs, position);
    }

    private record StorageSnapshot(List<Object[]> pairs, int position) implements RestoredNativeState {
        @Override
        public PhpNativeState restoreNativeState(PhpContext ctx, Function<Object, Object> unmap) {
            SplObjectStorageState state = new SplObjectStorageState();
            for (Object[] pair : pairs) {
                state.attach((PhpObject) unmap.apply(pair[0]), unmap.apply(pair[1]));
            }
            state.position = position;
            return state;
        }
    }

    @Override
    public Map<String, Object> debugProperties(PhpObject self) {
        PhpArray storage = new PhpArray();
        for (var entry : objects.entrySet()) {
            PhpArray pair = new PhpArray();
            pair.put("obj", entry.getKey());
            pair.put("inf", entry.getValue());
            storage.append(pair);
        }
        return Map.of("\0SplObjectStorage\0storage", storage);
    }
}
