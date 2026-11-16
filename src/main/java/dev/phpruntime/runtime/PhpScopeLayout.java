package dev.phpruntime.runtime;

import java.util.Map;
import java.util.LinkedHashMap;

/** Immutable, parse-time metadata. No invocation state is retained by a shared AST. */
public record PhpScopeLayout(int tableSlot, int cellCount, Map<String, Integer> locals,
                             int thisSlot, int calledClassSlot, int lexicalClassSlot, boolean included) {
    public PhpScopeLayout {
        locals = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(locals));
    }
}
