package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import dev.phpruntime.PhpContext;
import dev.phpruntime.snapshot.RestoredNativeState;
import dev.phpruntime.snapshot.SnapshotNativeState;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * WeakMap's native storage: object identity keys held weakly, values held strongly, iteration
 * in insertion order. A key whose referent has been collected is dropped when the map is next
 * inspected, so no operation ever observes a cleared entry.
 */
public final class WeakMapState implements PhpNativeState, SnapshotNativeState {
    private final LinkedHashMap<Key, Object> entries = new LinkedHashMap<>();
    private final ReferenceQueue<PhpObject> queue = new ReferenceQueue<>();

    private static final class Key extends WeakReference<PhpObject> {
        private final int hash;

        Key(PhpObject referent, ReferenceQueue<PhpObject> queue) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
        }

        Key(PhpObject referent) {
            super(referent);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            PhpObject mine = get();
            return mine != null && mine == key.get();
        }
    }

    @TruffleBoundary
    private void expunge() {
        Object collected;
        while ((collected = queue.poll()) != null) {
            entries.remove(collected);
        }
    }

    @TruffleBoundary
    private Key find(PhpObject key) {
        for (Key candidate : entries.keySet()) {
            if (candidate.get() == key) {
                return candidate;
            }
        }
        return null;
    }

    @TruffleBoundary
    public void put(PhpObject key, Object value) {
        expunge();
        Key existing = find(key);
        if (existing != null) {
            entries.put(existing, PhpOps.copyOnAssign(value));
        } else {
            entries.put(new Key(key, queue), PhpOps.copyOnAssign(value));
        }
    }

    @TruffleBoundary
    public Object get(PhpObject key) {
        expunge();
        Key existing = find(key);
        return existing == null ? null : PhpOps.copyOnAssign(entries.get(existing));
    }

    @TruffleBoundary
    public boolean exists(PhpObject key) {
        expunge();
        Key existing = find(key);
        if (existing == null) {
            return false;
        }
        Object value = entries.get(existing);
        return value != null && value != PhpNull.INSTANCE;
    }

    @TruffleBoundary
    public void remove(PhpObject key) {
        expunge();
        Key existing = find(key);
        if (existing != null) {
            entries.remove(existing);
        }
    }

    @TruffleBoundary
    public int size() {
        expunge();
        return entries.size();
    }

    @TruffleBoundary
    public Object keyAt(int position) {
        expunge();
        return entryAt(position).getKey().get();
    }

    @TruffleBoundary
    public Object valueAt(int position) {
        expunge();
        return PhpOps.copyOnAssign(entryAt(position).getValue());
    }

    private Map.Entry<Key, Object> entryAt(int position) {
        if (position < 0 || position >= entries.size()) {
            throw new IllegalArgumentException("WeakMap iteration position " + position);
        }
        var iterator = entries.entrySet().iterator();
        for (int i = 0; i < position; i++) {
            iterator.next();
        }
        return iterator.next();
    }

    @Override
    public PhpNativeState copy() {
        WeakMapState copy = new WeakMapState();
        expunge();
        for (Map.Entry<Key, Object> entry : entries.entrySet()) {
            PhpObject key = entry.getKey().get();
            if (key != null) {
                copy.entries.put(new Key(key, copy.queue), PhpOps.copyOnAssign(entry.getValue()));
            }
        }
        return copy;
    }

    @Override
    public Object snapshotNativeState(Function<Object, Object> map) {
        List<Object[]> pairs = new ArrayList<>();
        expunge();
        for (Map.Entry<Key, Object> entry : entries.entrySet()) {
            PhpObject key = entry.getKey().get();
            if (key != null) {
                pairs.add(new Object[]{map.apply(key), map.apply(entry.getValue())});
            }
        }
        return new WeakMapSnapshot(pairs);
    }

    private record WeakMapSnapshot(List<Object[]> pairs) implements RestoredNativeState {
        @Override
        public PhpNativeState restoreNativeState(PhpContext ctx, Function<Object, Object> unmap) {
            WeakMapState state = new WeakMapState();
            for (Object[] pair : pairs) {
                state.put((PhpObject) unmap.apply(pair[0]), unmap.apply(pair[1]));
            }
            return state;
        }
    }
}
