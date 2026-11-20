package dev.phpruntime.snapshot;

import dev.phpruntime.PhpContext;

import java.util.List;
import java.util.Map;

/**
 * The state of one snapshot-mode execution: a full run that only fingerprints the boundary, a
 * template run that captures and aborts, or a restored run that fills its context and reports
 * the fingerprint where the skipped boot include returns.
 */
public final class SnapshotRun {

    enum Mode {
        FULL,
        TEMPLATE,
        RESTORED
    }

    private final Mode mode;
    private final String bootPath;
    private final Snapshot restoreFrom;

    private boolean bootInProgress;
    private boolean done;
    private SnapshotCapture.PreBoot preBoot;
    private Snapshot snapshot;
    private List<String> refusal;
    private Map<String, String> fingerprint;

    private SnapshotRun(Mode mode, String bootPath, Snapshot restoreFrom) {
        this.mode = mode;
        this.bootPath = bootPath;
        this.restoreFrom = restoreFrom;
    }

    public static SnapshotRun full(String bootPath) {
        return new SnapshotRun(Mode.FULL, bootPath, null);
    }

    public static SnapshotRun template(String bootPath) {
        return new SnapshotRun(Mode.TEMPLATE, bootPath, null);
    }

    public static SnapshotRun restored(String bootPath, Snapshot snapshot) {
        return new SnapshotRun(Mode.RESTORED, bootPath, snapshot);
    }

    /** Whether this include is the boot file and starts a boundary that needs reporting on return. */
    public boolean beginBootInclude(PhpContext ctx, String canonicalPath) {
        if (done || bootInProgress || !bootPath.equals(canonicalPath)) {
            return false;
        }
        bootInProgress = true;
        if (mode == Mode.TEMPLATE) {
            preBoot = SnapshotCapture.beginBoot(ctx);
        }
        return true;
    }

    /** The boot include returned (or was skipped because it was already included). */
    void endBootInclude(PhpContext ctx) {
        if (!bootInProgress) {
            return;
        }
        bootInProgress = false;
        done = true;
        if (mode == Mode.TEMPLATE) {
            SnapshotCapture.PreBoot pre = preBoot;
            preBoot = null;
            try {
                snapshot = SnapshotCapture.capture(ctx, pre);
            } catch (SnapshotRefused refused) {
                refusal = refused.reasons();
            }
            throw new SnapshotAbort();
        }
        fingerprint = StateFingerprint.fingerprint(ctx);
    }

    /** Called once for the entry script, after its request globals exist and before its body. */
    void entryStarted(PhpContext ctx) {
        if (mode == Mode.RESTORED) {
            SnapshotCopier.restore(ctx, restoreFrom);
        }
    }

    /** The captured snapshot, or null when capture was refused or the boot include was not reached. */
    public Snapshot snapshot() {
        return snapshot;
    }

    /** Every refusal reason, or null when capture succeeded. */
    public List<String> refusal() {
        return refusal;
    }

    /** The boundary fingerprint for a full or restored run, or null before the boundary. */
    public Map<String, String> fingerprint() {
        return fingerprint;
    }

    public boolean bootReached() {
        return done;
    }

    /** Whether this run captures: the only mode that records database session setup. */
    public boolean isTemplate() {
        return mode == Mode.TEMPLATE;
    }

    public String bootPath() {
        return bootPath;
    }
}
