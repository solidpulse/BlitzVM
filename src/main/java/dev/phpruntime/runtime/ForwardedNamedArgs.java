package dev.phpruntime.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Named arguments a forwarding builtin or called-through method received but does not
 * declare itself, in call order. The binder appends one to the callee's argument array;
 * the dispatch strips it before the implementation runs and the implementation reads it
 * from the {@link PhpContext} to bind the target callable.
 */
public final class ForwardedNamedArgs {

    private final Map<String, Object> values;

    public ForwardedNamedArgs(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public Map<String, Object> values() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }
}
