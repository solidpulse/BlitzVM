package dev.phpruntime.runtime;

import dev.phpruntime.PhpContext;

/**
 * Single-value mutable cell for PHP {@code static} locals and closure {@code use (&$x)}
 * by-reference captures. Dynamic scopes also use unaliased storage cells; ordinary
 * non-cell local variables remain unboxed in frame slots.
 */
public final class PhpReference {

    private Object value;
    /** Symbol-table storage cells are not PHP references until they are explicitly aliased. */
    private boolean aliased = true;
    /** A typed property's cell checks every write, as assigning through the property would. */
    private CellCheck cellCheck;

    /** Checks a value written through a typed property's reference cell. */
    public static final class CellCheck {
        private final DeclaredType type;
        private final PhpContext context;
        private final boolean strict;
        private final PhpClass selfClass;
        private final String owner;
        private final String property;

        public CellCheck(DeclaredType type, PhpContext context, boolean strict, PhpClass selfClass,
                         String owner, String property) {
            this.type = type;
            this.context = context;
            this.strict = strict;
            this.selfClass = selfClass;
            this.owner = owner;
            this.property = property;
        }

        public Object check(Object value) {
            return type.checkProperty(value, context, null, strict, selfClass, owner, property);
        }
    }

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

    public void setValue(Object value) {
        Object checked = cellCheck == null ? value : cellCheck.check(value);
        this.value = PhpStringBuffer.flatten(checked);
    }

    /** Makes this cell the cell of a typed property; must be set before the first write. */
    public void setCellCheck(CellCheck check) {
        this.cellCheck = check;
    }

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
