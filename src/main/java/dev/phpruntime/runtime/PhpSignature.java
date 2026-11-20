package dev.phpruntime.runtime;

import java.util.List;

/**
 * The declared signature of a function, method, or closure: parameters (with their
 * types, attributes and defaults), the return type, and the function's own
 * attributes.
 */
public record PhpSignature(List<PhpParameter> parameters, PhpType returnType,
                           List<PhpAttribute> attributes, int endLine, String sourceFile, int startLine) {

    public static final PhpSignature EMPTY = new PhpSignature(List.of(), null, List.of(), -1, null, -1);

    public PhpSignature {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        attributes = attributes == null ? List.of() : List.copyOf(attributes);
    }

    public PhpSignature(List<PhpParameter> parameters, PhpType returnType, List<PhpAttribute> attributes) {
        this(parameters, returnType, attributes, -1, null, -1);
    }

    public PhpSignature(List<PhpParameter> parameters, PhpType returnType, List<PhpAttribute> attributes,
                        int endLine) {
        this(parameters, returnType, attributes, endLine, null, -1);
    }

    /** A copy carrying the line of the declaration's closing brace, for getEndLine(). */
    public PhpSignature withEndLine(int line) {
        return new PhpSignature(parameters, returnType, attributes, line, sourceFile, startLine);
    }

    /** A copy carrying the declaration's source file and starting line, for abstract methods. */
    public PhpSignature withSource(String file, int line) {
        return new PhpSignature(parameters, returnType, attributes, endLine, file, line);
    }
}
