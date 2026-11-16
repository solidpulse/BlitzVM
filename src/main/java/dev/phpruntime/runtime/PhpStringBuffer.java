package dev.phpruntime.runtime;

/** Location-owned append storage. Only immutable snapshots may be read as PHP values. */
public final class PhpStringBuffer {
    private final StringBuilder bytes;
    private String snapshot;

    public PhpStringBuffer(String initial) {
        bytes = new StringBuilder(initial);
        snapshot = initial;
    }

    public void append(String suffix) {
        if (!suffix.isEmpty()) {
            bytes.append(suffix);
            snapshot = null;
        }
    }

    public String asString() {
        if (snapshot == null) { snapshot = bytes.toString(); }
        return snapshot;
    }

    public static Object flatten(Object value) {
        return value instanceof PhpStringBuffer buffer ? buffer.asString() : value;
    }
}
