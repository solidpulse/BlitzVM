package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A function invocation's symbol table, retained in its frame (also across generator yields).
 * Statically named locals use direct indices. Extra names retain binding indirection so
 * includes can keep invocation-owned handles without caching cells that rebinding replaces.
 */
public final class PhpSymbolTable {
    public final PhpScopeLayout layout;
    public final PhpReference[] cells;
    public final Object thisValue;
    public final PhpClass calledClass;
    public final PhpClass lexicalClass;
    private static final class Binding { PhpReference cell; }
    private final Map<String, Binding> extra = new LinkedHashMap<>();
    private final java.util.Set<String> dynamicOrder = new java.util.LinkedHashSet<>();

    /** Invocation-owned indirection; rebinding updates it rather than leaving included reads stale. */
    public static final class Access {
        private final PhpSymbolTable table;
        private final int slot;
        private final Binding binding;
        private final String dynamicName;
        private Access(PhpSymbolTable table, int slot, Binding binding, String dynamicName) {
            this.table = table; this.slot = slot; this.binding = binding; this.dynamicName = dynamicName;
        }
        public PhpReference get() { return slot >= 0 ? table.cells[slot] : binding.cell; }
        public void bind(PhpReference cell) {
            if (dynamicName != null) { table.define(dynamicName, cell != null); }
            if (slot >= 0) { table.cells[slot] = cell; }
            else { binding.cell = cell; }
        }
        public Object read() { PhpReference cell = get(); return cell == null ? null : cell.getValue(); }
        public void write(Object value) {
            PhpReference cell = get();
            if (cell == null) { bind(PhpReference.local(value)); }
            else { cell.setValue(value); }
        }
        public String appendString(String suffix, boolean resultNeeded) {
            PhpReference cell = get();
            if (cell == null) { bind(cell = PhpReference.local(null)); }
            return cell.appendString(suffix, resultNeeded);
        }
        public PhpReference obtain() {
            PhpReference cell = get();
            if (cell == null) { bind(cell = new PhpReference(PhpNull.INSTANCE)); }
            else { cell.ensureDefined(); }
            return cell.alias();
        }
    }

    @TruffleBoundary
    public Access access(String name) {
        Integer slot = layout.locals().get(name);
        if (slot != null) { return new Access(this, slot, null, null); }
        // Literal compact reserves a direct slot without giving it compiled-variable order.
        Integer literal = layout.literalSlots().get(name);
        Binding binding = extra.computeIfAbsent(name, n -> new Binding());
        if (literal != null) {
            return new Access(this, literal, binding, name);
        }
        return new Access(this, -1, binding, name);
    }

    @TruffleBoundary
    private void define(String name, boolean defined) {
        if (defined) { dynamicOrder.add(name); }
        else { dynamicOrder.remove(name); }
    }

    @TruffleBoundary
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
                cells[slot] = cell = PhpReference.local(null);
                if (!layout.locals().containsKey(name)) {
                    extra.computeIfAbsent(name, n -> new Binding()).cell = cell;
                    dynamicOrder.add(name);
                }
            }
            return cell;
        }
        Binding binding = extra.get(name);
        if (binding == null && create) { extra.put(name, binding = new Binding()); }
        PhpReference cell = binding == null ? null : binding.cell;
        if (cell == null && create) { binding.cell = cell = PhpReference.local(null); dynamicOrder.add(name); }
        return cell;
    }

    @TruffleBoundary
    public void bind(String name, PhpReference cell) {
        cell.alias();
        Integer slot = layout.slot(name);
        if (slot != null) { cells[slot] = cell; }
        if (!layout.locals().containsKey(name)) {
            extra.computeIfAbsent(name, n -> new Binding()).cell = cell;
            dynamicOrder.add(name);
        }
    }

    @TruffleBoundary
    public void unset(String name) {
        Integer slot = layout.slot(name);
        if (slot != null) { cells[slot] = null; }
        Binding binding = extra.get(name);
        if (binding != null) { binding.cell = null; }
        dynamicOrder.remove(name);
    }

    @TruffleBoundary
    public PhpArray snapshot() {
        PhpArray result = new PhpArray();
        for (var entry : layout.locals().entrySet()) {
            PhpReference cell = cells[entry.getValue()];
            if (cell != null && cell.getValue() != null) {
                if (cell.isAliased()) { result.putRef(entry.getKey(), cell); }
                else { result.put(entry.getKey(), PhpOps.copyOnAssign(cell.getValue())); }
            }
        }
        for (String name : dynamicOrder) {
            Integer literal = layout.literalSlots().get(name);
            PhpReference cell = literal == null ? extra.get(name).cell : cells[literal];
            if (cell != null && cell.getValue() != null) {
                if (cell.isAliased()) { result.putRef(name, cell); }
                else { result.put(name, PhpOps.copyOnAssign(cell.getValue())); }
            }
        }
        return result;
    }
}
