package dev.phpruntime.parser;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import dev.phpruntime.nodes.var.GlobalVarNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.nodes.var.LocalVarNode;
import dev.phpruntime.nodes.var.VarKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Variable resolution at parse time. Function locals get frame slots, so reading
 * {@code $i} compiles to a frame load. Top-level variables, {@code global} imports
 * and superglobals live in the context's global symbol table.
 */
final class Scope {

    record Capture(String name, int targetSlot, VarKind targetKind, LValueNode outerRead, boolean warnIfUndefined, boolean byRef) {
    }

    private static final Set<String> SUPERGLOBALS = Set.of("_GET", "_POST", "_SERVER", "_REQUEST", "_COOKIE", "_ENV", "_FILES", "GLOBALS");

    private final boolean global;
    private final Scope outer;
    private final boolean isArrow;
    private final FrameDescriptor.Builder frame = FrameDescriptor.newBuilder();
    private final Map<String, Integer> slots = new HashMap<>();
    private final Map<Integer, VarKind> kinds = new HashMap<>();
    private final Set<String> imported = new HashSet<>();
    private final Set<String> ownParams = new HashSet<>();
    private final Map<String, Integer> capturedVars = new HashMap<>();
    private final List<Capture> captures = new ArrayList<>();
    private final int thisSlot;
    private final int calledClassSlot;
    private final int closureObjSlot;

    Scope(boolean global) {
        this(global, false, null, false, false);
    }

    private Scope(boolean global, boolean hasThis, Scope outer, boolean isClosure, boolean isArrow) {
        this.global = global;
        this.outer = outer;
        this.isArrow = isArrow;
        if (hasThis) {
            this.thisSlot = slot("this");
            this.calledClassSlot = slot("@calledClass");
        } else {
            this.thisSlot = -1;
            this.calledClassSlot = isClosure ? slot("@calledClass") : -1;
        }
        this.closureObjSlot = isClosure ? slot("@closureObj") : -1;
    }

    static Scope forMethod() {
        return new Scope(false, true, null, false, false);
    }

    static Scope forClosure(Scope outer, boolean bindThis, boolean isArrow) {
        return new Scope(false, bindThis, outer, true, isArrow);
    }

    int thisSlot() {
        return thisSlot;
    }

    int calledClassSlot() {
        return calledClassSlot;
    }

    int closureObjSlot() {
        return closureObjSlot;
    }

    /** Frame argument prefix length for this function/method/closure, or -1 in global scope. */
    int argOffset() {
        if (global) {
            return -1;
        }
        if (closureObjSlot >= 0) {
            return 3;
        }
        return thisSlot >= 0 ? 2 : 0;
    }

    void declareParam(String name) {
        ownParams.add(name);
    }

    void markCell(String name) {
        kind(slot(name)).markCell();
    }

    void addExplicitCapture(String name, boolean byRef, int line) {
        if (capturedVars.containsKey(name) || outer == null) {
            return;
        }
        LValueNode outerTarget = outer.variable(name);
        outerTarget.at(line);
        if (byRef) {
            if (outerTarget instanceof LocalVarNode) {
                outer.markCell(name);
            }
            markCell(name);
        }
        int localSlot = slot(name);
        capturedVars.put(name, localSlot);
        captures.add(new Capture(name, localSlot, kind(localSlot), outerTarget, !byRef, byRef));
    }

    List<Capture> captures() {
        return captures;
    }

    LValueNode variable(String name) {
        if (global || SUPERGLOBALS.contains(name) || imported.contains(name)) {
            return new GlobalVarNode(name);
        }
        if (isArrow && !name.equals("this") && !ownParams.contains(name) && !capturedVars.containsKey(name) && outer != null) {
            LValueNode outerTarget = outer.variable(name);
            int localSlot = slot(name);
            capturedVars.put(name, localSlot);
            captures.add(new Capture(name, localSlot, kind(localSlot), outerTarget, false, false));
            return new LocalVarNode(localSlot, name, kind(localSlot));
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
