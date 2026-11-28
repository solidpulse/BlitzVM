package dev.phpruntime.runtime;

/** Iteration state for the InternalIterator a WeakMap::getIterator() hands to foreach. */
public final class WeakMapIteratorState implements PhpNativeState {
    private final WeakMapState map;
    public int position;

    public WeakMapIteratorState(WeakMapState map) {
        this.map = map;
    }

    public void rewind() {
        position = 0;
    }

    public boolean valid() {
        return position < map.size();
    }

    public Object key() {
        return valid() ? map.keyAt(position) : PhpNull.INSTANCE;
    }

    public Object current() {
        return valid() ? map.valueAt(position) : PhpNull.INSTANCE;
    }

    public void next() {
        position++;
    }

    @Override
    public PhpNativeState copy() {
        WeakMapIteratorState copy = new WeakMapIteratorState(map);
        copy.position = position;
        return copy;
    }
}
