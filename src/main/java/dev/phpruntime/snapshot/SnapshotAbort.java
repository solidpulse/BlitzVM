package dev.phpruntime.snapshot;

import com.oracle.truffle.api.nodes.ControlFlowException;

/**
 * Ends a template run at the end of the boot include. It is an unwind exception, like
 * {@code exit}: PHP {@code catch} and {@code finally} blocks do not see it, and the entry root
 * catches it after the guest stack has unwound.
 */
public final class SnapshotAbort extends ControlFlowException {
}
