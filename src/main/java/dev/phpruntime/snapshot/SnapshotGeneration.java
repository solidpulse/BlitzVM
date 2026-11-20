package dev.phpruntime.snapshot;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A process-wide counter of database writes. Database builtins call {@link #bump()} for every
 * statement that may change data; a snapshot records {@link #current()} when it is captured and
 * is stale once the counter has moved on.
 */
public final class SnapshotGeneration {

    private static final AtomicLong GENERATION = new AtomicLong();

    private SnapshotGeneration() {
    }

    public static long current() {
        return GENERATION.get();
    }

    public static void bump() {
        GENERATION.incrementAndGet();
    }
}
