package dev.phpruntime.snapshot;

import java.util.List;

/** Capture was refused; carries every reason found, most useful reason first. */
final class SnapshotRefused extends RuntimeException {

    private final List<String> reasons;

    SnapshotRefused(List<String> reasons) {
        super(reasons.isEmpty() ? "capture refused" : reasons.get(0), null, false, false);
        this.reasons = List.copyOf(reasons);
    }

    List<String> reasons() {
        return reasons;
    }
}
