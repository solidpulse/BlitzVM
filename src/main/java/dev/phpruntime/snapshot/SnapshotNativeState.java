package dev.phpruntime.snapshot;

import java.util.function.Function;

/**
 * Native Java state that a boot snapshot can carry. A state implements this when it holds
 * request objects (a reflection target, a closure) next to engine-wide metadata: it describes
 * itself with {@code map} applied to every request object, so a {@link Snapshot} never keeps
 * a template-run object, and the descriptor it returns implements {@link RestoredNativeState}.
 */
public interface SnapshotNativeState {

    /**
     * Returns a descriptor holding only engine-wide values and the results of {@code map} for
     * request objects. Must not keep {@code this} or anything the map produced.
     */
    Object snapshotNativeState(Function<Object, Object> map);
}
