package dev.phpruntime.runtime;

import com.oracle.truffle.api.RootCallTarget;

import java.util.List;

/**
 * One declared parameter, for reflection. The hot-path parameter arrays stay on
 * {@link dev.phpruntime.nodes.PhpFunctionRootNode}; this record carries what only
 * reflection needs.
 */
public record PhpParameter(String name, PhpType type, List<PhpAttribute> attributes,
                           boolean promoted, boolean variadic, boolean byRef,
                           Object defaultValue, RootCallTarget defaultTarget) {

    public PhpParameter {
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }

    public boolean hasDefault() {
        return defaultTarget != null || defaultValue != null;
    }
}
