package dev.phpruntime.parser;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import dev.phpruntime.nodes.var.GlobalVarNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.nodes.var.LocalVarNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Variable resolution at parse time. Function locals get frame slots, so reading
 * {@code $i} compiles to a frame load. Top-level variables, {@code global} imports
 * and superglobals live in the context's global symbol table.
 */
final class Scope {

    private static final Set<String> SUPERGLOBALS = Set.of("_GET", "_POST", "_SERVER", "_REQUEST", "_COOKIE", "_ENV", "_FILES", "GLOBALS");

    private final boolean global;
    private final FrameDescriptor.Builder frame = FrameDescriptor.newBuilder();
    private final Map<String, Integer> slots = new HashMap<>();
    private final Set<String> imported = new HashSet<>();

    Scope(boolean global) {
        this.global = global;
    }

    LValueNode variable(String name) {
        if (global || SUPERGLOBALS.contains(name) || imported.contains(name)) {
            return new GlobalVarNode(name);
        }
        return new LocalVarNode(slot(name), name);
    }

    int slot(String name) {
        return slots.computeIfAbsent(name, n -> frame.addSlot(FrameSlotKind.Illegal, n, null));
    }

    void importGlobal(String name) {
        imported.add(name);
    }

    boolean isGlobal() {
        return global;
    }

    FrameDescriptor build() {
        return frame.build();
    }
}
