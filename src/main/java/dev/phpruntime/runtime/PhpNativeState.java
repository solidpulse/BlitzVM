package dev.phpruntime.runtime;

import java.util.Map;

/**
 * Native Java state attached to a {@link PhpObject} (e.g. timelib structs for DateTime/DateTimeZone).
 *
 * <p>Provides hooks for {@code clone}, {@code var_dump}/{@code print_r}/{@code json_encode}/
 * {@code (array)} property inspection, and {@code ==}/{@code <}/{@code >} object comparison.
 */
public interface PhpNativeState {

    /** Returns an independent copy of this native state for {@code clone}. */
    PhpNativeState copy();

    /**
     * Returns synthetic properties to include in {@code var_dump}, {@code print_r},
     * {@code json_encode}, and {@code (array)} casts, or {@code null} if none.
     */
    default Map<String, Object> debugProperties(PhpObject self) {
        return null;
    }

    /** Whether this object supports custom comparison via {@link #compareTo(PhpObject)}. */
    default boolean supportsCompare() {
        return false;
    }

    /** Compares {@code this} object to {@code other}; returns {@code <0}, {@code 0}, or {@code >0}. */
    default int compareTo(PhpObject other) {
        return 0;
    }
}
