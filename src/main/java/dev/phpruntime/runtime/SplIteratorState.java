package dev.phpruntime.runtime;

import java.util.ArrayList;
import java.util.List;

/** Native wrapper cursors. Child/parent iterators stay request-local, not on method ASTs. */
public final class SplIteratorState implements PhpNativeState {
    public static final class Level {
        public PhpObject iterator;
        public int phase;

        public Level(PhpObject iterator) {
            this.iterator = iterator;
        }
    }

    public final PhpObject inner;
    public final boolean recursive;
    public int mode;
    public int flags;
    public int maxDepth = -1;
    public final List<Level> levels = new ArrayList<>();
    public int currentDepth = -1;

    public SplIteratorState(PhpObject inner, boolean recursive, int mode, int flags) {
        this.inner = inner;
        this.recursive = recursive;
        this.mode = mode;
        this.flags = flags;
    }

    public PhpObject currentIterator() {
        return currentDepth >= 0 && currentDepth < levels.size() ? levels.get(currentDepth).iterator : inner;
    }

    @Override
    public PhpNativeState copy() {
        SplIteratorState copy = new SplIteratorState(inner, recursive, mode, flags);
        copy.maxDepth = maxDepth;
        for (Level level : levels) {
            Level newLevel = new Level(level.iterator);
            newLevel.phase = level.phase;
            copy.levels.add(newLevel);
        }
        copy.currentDepth = currentDepth;
        return copy;
    }
}
