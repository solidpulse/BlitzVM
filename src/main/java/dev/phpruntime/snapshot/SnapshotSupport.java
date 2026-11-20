package dev.phpruntime.snapshot;

import dev.phpruntime.PhpContext;

/**
 * The only entry points into the snapshot machinery from the rest of the runtime. Ordinary
 * requests never set a pending run, so {@link PhpContext#snapshotRun()} stays null and the
 * include and entry hooks cost one field read.
 */
public final class SnapshotSupport {

    private static final ThreadLocal<SnapshotRun> PENDING = new ThreadLocal<>();

    private SnapshotSupport() {
    }

    /** Attaches the run to the next {@link PhpContext} created on this thread. */
    public static void setPending(SnapshotRun run) {
        PENDING.set(run);
    }

    public static void clearPending() {
        PENDING.remove();
    }

    /** Called by the language when a context is created, once per request. */
    public static void attachNewContext(PhpContext ctx) {
        SnapshotRun run = PENDING.get();
        if (run != null) {
            PENDING.remove();
            ctx.attachSnapshotRun(run);
        }
    }

    /** Called after the entry script's globals and included file are established. */
    public static void entryStarted(PhpContext ctx) {
        SnapshotRun run = ctx.snapshotRun();
        if (run != null) {
            run.entryStarted(ctx);
        }
    }

    /** Called by an include node when the boot include has returned. */
    public static void bootIncludeReturned(PhpContext ctx) {
        SnapshotRun run = ctx.snapshotRun();
        if (run != null) {
            run.endBootInclude(ctx);
        }
    }
}
