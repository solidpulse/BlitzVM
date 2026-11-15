package dev.phpruntime.runtime;

/**
 * Single-value mutable cell for PHP {@code static} locals and closure {@code use (&$x)}
 * by-reference captures. Non-cell local variables remain unboxed in frame slots.
 */
public final class PhpReference {

    public Object value;

    public PhpReference() {
        this(PhpNull.INSTANCE);
    }

    public PhpReference(Object value) {
        this.value = value;
    }
}
