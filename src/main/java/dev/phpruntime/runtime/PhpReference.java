package dev.phpruntime.runtime;

/**
 * Single-value mutable cell for PHP {@code static} locals and closure {@code use (&$x)}
 * by-reference captures. Dynamic scopes also use unaliased storage cells; ordinary
 * non-cell local variables remain unboxed in frame slots.
 */
public final class PhpReference {

    private Object value;
    /** Symbol-table storage cells are not PHP references until they are explicitly aliased. */
    private boolean aliased = true;

    public static PhpReference local(Object value) {
        PhpReference cell = new PhpReference(value);
        cell.aliased = false;
        return cell;
    }

    public PhpReference alias() { aliased = true; return this; }
    public boolean isAliased() { return aliased; }

    /** Ordinary reads never expose location-owned append storage. */
    public Object getValue() { return PhpStringBuffer.flatten(value); }

    /** The stored value, possibly an append buffer the caller must flatten. */
    public Object rawValue() { return value; }
    public void setValue(Object value) { this.value = PhpStringBuffer.flatten(value); }

    /** Taking an existing reference is not a value read and must not copy its append prefix. */
    public void ensureDefined() { if (value == null) { value = PhpNull.INSTANCE; } }

    /** Only .= may create or mutate a buffer, and it stays in this cell. */
    public String appendString(String suffix, boolean resultNeeded) {
        PhpStringBuffer buffer;
        if (value instanceof PhpStringBuffer existing) { buffer = existing; }
        else {
            buffer = new PhpStringBuffer(value instanceof String s ? s
                    : PhpOps.toStr(value == null ? PhpNull.INSTANCE : value));
            value = buffer;
        }
        buffer.append(suffix);
        return resultNeeded ? buffer.asString() : null;
    }

    public PhpReference() {
        this(PhpNull.INSTANCE);
    }

    public PhpReference(Object value) {
        setValue(value);
    }
}
