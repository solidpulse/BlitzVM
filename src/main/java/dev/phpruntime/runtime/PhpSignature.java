package dev.phpruntime.runtime;

import java.util.List;

/**
 * The declared signature of a function, method, or closure: parameters (with their
 * types, attributes and defaults), the return type, and the function's own
 * attributes.
 */
public record PhpSignature(List<PhpParameter> parameters, PhpType returnType,
                           List<PhpAttribute> attributes) {

    public static final PhpSignature EMPTY = new PhpSignature(List.of(), null, List.of());

    public PhpSignature {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }
}
