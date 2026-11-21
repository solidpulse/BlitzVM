package dev.phpruntime.snapshot;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpNativeState;

import java.util.function.Function;

/**
 * A captured native state that rebuilds itself in a restoring request. {@code unmap} turns the
 * descriptors produced when the snapshot was captured into this request's copies, through the
 * copier's map, so object identity is the same as everywhere else in the restored graph.
 */
public interface RestoredNativeState {

    PhpNativeState restoreNativeState(PhpContext ctx, Function<Object, Object> unmap);
}
