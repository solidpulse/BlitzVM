package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.util.LinkedHashMap;
import java.util.Map;

/** A function invocation's symbol table, retained in its frame (also across generator yields). */
public final class PhpSymbolTable {
    public final PhpScopeLayout layout;
    public final PhpReference[] cells;
    public final Object thisValue;
    public final PhpClass calledClass;
    public final PhpClass lexicalClass;
    private final Map<String, PhpReference> extra = new LinkedHashMap<>();

    public PhpSymbolTable(PhpScopeLayout layout, Object thisValue, PhpClass calledClass, PhpClass lexicalClass) {
        this.layout = layout;
        this.cells = new PhpReference[layout.cellCount()];
        this.thisValue = thisValue;
        this.calledClass = calledClass;
        this.lexicalClass = lexicalClass;
    }

    @TruffleBoundary
    public PhpReference cell(String name, boolean create) {
        Integer slot = layout.slot(name);
        if (slot != null) {
            PhpReference cell = cells[slot];
            if (cell == null && create) {
                cells[slot] = cell = new PhpReference(null);
                if (!layout.locals().containsKey(name)) { extra.put(name, cell); }
            }
            return cell;
        }
        PhpReference cell = extra.get(name);
        if (cell == null && create) { extra.put(name, cell = new PhpReference(null)); }
        return cell;
    }

    @TruffleBoundary
    public void bind(String name, PhpReference cell) {
        Integer slot = layout.slot(name);
        if (slot != null) { cells[slot] = cell; }
        if (!layout.locals().containsKey(name)) { extra.put(name, cell); }
    }

    @TruffleBoundary
    public void unset(String name) {
        Integer slot = layout.slot(name);
        if (slot != null) { cells[slot] = null; }
        extra.remove(name);
    }

    @TruffleBoundary
    public PhpArray snapshot() {
        PhpArray result = new PhpArray();
        for (var entry : layout.locals().entrySet()) {
            PhpReference cell = cells[entry.getValue()];
            if (cell != null && cell.value != null) {
                result.put(entry.getKey(), PhpOps.copyOnAssign(cell.value));
            }
        }
        for (var entry : extra.entrySet()) {
            PhpReference cell = entry.getValue();
            if (cell.value != null) { result.put(entry.getKey(), PhpOps.copyOnAssign(cell.value)); }
        }
        return result;
    }
}
