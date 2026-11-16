package dev.phpruntime.runtime;

import java.util.Map;
import java.util.LinkedHashMap;

/** Immutable, parse-time metadata. No invocation state is retained by a shared AST. */
public record PhpScopeLayout(int tableSlot, int cellCount, Map<String, Integer> locals, Map<String, Integer> literalSlots,
                             int thisSlot, int calledClassSlot, int lexicalClassSlot, boolean included) {
    public PhpScopeLayout {
        locals = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(locals));
        literalSlots = Map.copyOf(literalSlots);
    }

    public Integer slot(String name) {
        Integer slot = locals.get(name);
        return slot != null ? slot : literalSlots.get(name);
    }
}
