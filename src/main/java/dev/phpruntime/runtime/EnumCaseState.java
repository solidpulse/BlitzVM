package dev.phpruntime.runtime;

import java.util.Map;

/** The immutable native state of an enum case object: the enum and case names and backing value. */
public final class EnumCaseState implements PhpNativeState {

    private final String enumName;
    private final String caseName;
    private final boolean backed;
    private final Object value;

    public EnumCaseState(String enumName, String caseName, boolean backed, Object value) {
        this.enumName = enumName;
        this.caseName = caseName;
        this.backed = backed;
        this.value = value;
    }

    public String enumName() {
        return enumName;
    }

    public String caseName() {
        return caseName;
    }

    public boolean backed() {
        return backed;
    }

    /** The backing value of a backed enum case, or null for a pure enum. */
    public Object value() {
        return value;
    }

    @Override
    public PhpNativeState copy() {
        return this;
    }

    @Override
    public Map<String, Object> debugProperties(PhpObject self) {
        return null;
    }
}
