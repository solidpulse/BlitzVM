package dev.phpruntime.parser;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import dev.phpruntime.nodes.var.GlobalVarNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.nodes.var.LocalVarNode;
import dev.phpruntime.nodes.var.VarKind;

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
    private final Map<Integer, VarKind> kinds = new HashMap<>();
    private final Set<String> imported = new HashSet<>();
    private final int thisSlot;
    private final int calledClassSlot;

    Scope(boolean global) {
        this(global, false);
    }

    private Scope(boolean global, boolean method) {
        this.global = global;
        if (method) {
            this.thisSlot = slot("this");
            this.calledClassSlot = slot("@calledClass");
        } else {
            this.thisSlot = -1;
            this.calledClassSlot = -1;
        }
    }

    static Scope forMethod() {
        return new Scope(false, true);
    }

    int thisSlot() {
        return thisSlot;
    }

    int calledClassSlot() {
        return calledClassSlot;
    }

    LValueNode variable(String name) {
        if (global || SUPERGLOBALS.contains(name) || imported.contains(name)) {
            return new GlobalVarNode(name);
        }
        int slot = slot(name);
        return new LocalVarNode(slot, name, kind(slot));
    }

    VarKind kind(int slot) {
        return kinds.computeIfAbsent(slot, s -> new VarKind());
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
