package dev.phpruntime.runtime;

/** Location-owned append storage. Only immutable snapshots may be read as PHP values. */
public final class PhpStringBuffer {
    private final StringBuilder bytes;
    private String snapshot;

    // JDK StringBuilder code must stay out of partial evaluation: inlined, its bounds-check
    // error paths pull in Formatter, Locale and reflection and the compilation bails out.
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public PhpStringBuffer(String initial) {
        bytes = new StringBuilder(initial);
        snapshot = initial;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public void append(String suffix) {
        if (!suffix.isEmpty()) {
            bytes.append(suffix);
            snapshot = null;
        }
    }

    public String asString() {
        String s = snapshot;
        return s != null ? s : snapshot();
    }

    /**
     * Behind a boundary: every reference read goes through {@link #flatten}, and an inlined
     * StringBuilder.toString() added about 3 KB of machine code to each of them.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private String snapshot() {
        snapshot = bytes.toString();
        return snapshot;
    }

    public static Object flatten(Object value) {
        return value instanceof PhpStringBuffer buffer ? buffer.asString() : value;
    }
}
