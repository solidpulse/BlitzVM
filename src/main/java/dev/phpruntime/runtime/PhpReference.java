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

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    public PhpReference() {
        this(PhpNull.INSTANCE);
    }

    public PhpReference(Object value) {
        this.value = value;
    }
}
