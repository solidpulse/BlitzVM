package dev.phpruntime.runtime;

import com.oracle.truffle.api.RootCallTarget;

import java.util.List;

/**
 * One argument of an attribute: its value is folded at parse time when it is a
 * literal, otherwise the expression is compiled to a root call target that the
 * Reflection API evaluates on demand.
 */
public record PhpAttribute(String name, List<Argument> arguments) {

    public record Argument(String name, Object value, RootCallTarget target) {
    }

    public PhpAttribute {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    /** The named arguments only, in declaration order. */
    public List<Argument> namedArguments() {
        return arguments.stream().filter(a -> a.name() != null).toList();
    }
}
