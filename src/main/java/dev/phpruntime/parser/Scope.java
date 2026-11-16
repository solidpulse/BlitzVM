package dev.phpruntime.parser;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.var.GlobalVarNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.nodes.var.LocalVarNode;
import dev.phpruntime.nodes.var.VarKind;
import dev.phpruntime.nodes.var.DynamicVarNode;
import dev.phpruntime.nodes.var.IncludedVarNode;
import dev.phpruntime.nodes.expr.ConstNode;
import dev.phpruntime.runtime.PhpScopeLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Variable resolution at parse time. Function locals get frame slots, so reading
 * {@code $i} compiles to a frame load. Top-level variables and superglobals live in
 * the context's global symbol table; {@code global $x} inside a function binds a cell.
 * Bodies requiring by-name access are marked while parsing and specialized once the
 * whole body is known. Literal compact reads alone do not require a symbol table.
 */
final class Scope {

    record Capture(String name, int targetSlot, VarKind targetKind, LValueNode outerRead, boolean warnIfUndefined, boolean byRef) {
    }

    private static final Set<String> SUPERGLOBALS = Set.of("_GET", "_POST", "_SERVER", "_REQUEST", "_COOKIE", "_ENV", "_FILES", "_SESSION", "GLOBALS");

    private final PhpLanguage language;
    private final boolean global;
    private final Scope outer;
    private final boolean isArrow;
    private final FrameDescriptor.Builder frame = FrameDescriptor.newBuilder();
    private final Map<String, Integer> slots = new java.util.LinkedHashMap<>();
    private final Map<Integer, VarKind> kinds = new HashMap<>();
    private final Set<String> ownParams = new HashSet<>();
    private final List<String> paramOrder = new ArrayList<>();
    private final Map<String, Integer> capturedVars = new HashMap<>();
    private final List<Capture> captures = new ArrayList<>();
    private final int thisSlot;
    private final int calledClassSlot;
    private final int closureObjSlot;
    private final int traitSelfSlot;
    private GotoScope gotoScope;
    private int generatorSlot = -1;
    private int hiddenId;
    private boolean usesThis;
    private int tableSlot = -1;
    private boolean included;
    private final Map<String, Integer> literalSlots = new HashMap<>();
    private final Set<String> globalNames = new java.util.LinkedHashSet<>();

    LValueNode compactVariable(String name) {
        if (global) {
            return name.equals("GLOBALS") ? new DynamicVarNode(new ConstNode(name), -1)
                    : new GlobalVarNode(name, language.globalId(name));
        }
        if (included) {
            if (name.equals("this") || SUPERGLOBALS.contains(name)) { return new DynamicVarNode(new ConstNode(name), tableSlot); }
            return variable(name);
        }
        Integer existing = slots.get(name);
        int slot = existing != null ? existing : literalSlots.computeIfAbsent(name,
                n -> frame.addSlot(FrameSlotKind.Illegal, n, null));
        return new LocalVarNode(slot, name, kind(slot));
    }

    static Scope forInclude(PhpLanguage language) {
        Scope scope = new Scope(language, false, true, null, false, false, true);
        scope.included = true;
        scope.dynamicSlot();
        return scope;
    }

    int dynamicSlot() {
        if (global) { return -1; }
        if (closureObjSlot >= 0) { usesThis = true; }
        if (tableSlot < 0) { tableSlot = slot("@symbols"); }
        return tableSlot;
    }

    boolean isFileScope() { return global || included; }

    Scope(PhpLanguage language, boolean global) {
        this(language, global, false, null, false, false, false);
    }

    private Scope(PhpLanguage language, boolean global, boolean hasThis, Scope outer, boolean isClosure, boolean isArrow,
                  boolean isTraitMethod) {
        this.language = language;
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
        boolean needsTraitSelf = isTraitMethod || isClosure;
        this.traitSelfSlot = needsTraitSelf ? slot("@traitSelf") : -1;
    }

    static Scope forMethod(PhpLanguage language) {
        return new Scope(language, false, true, null, false, false, false);
    }

    static Scope forTraitMethod(PhpLanguage language) {
        return new Scope(language, false, true, null, false, false, true);
    }

    static Scope forClosure(PhpLanguage language, Scope outer, boolean bindThis, boolean isArrow) {
        return new Scope(language, false, bindThis, outer, true, isArrow, false);
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

    int traitSelfSlot() {
        return traitSelfSlot;
    }

    /** Frame argument prefix length for this function/method/closure, or -1 in global scope. */
    int argOffset() {
        if (global) {
            return -1;
        }
        if (closureObjSlot >= 0 || traitSelfSlot >= 0) {
            return 3;
        }
        return thisSlot >= 0 ? 2 : 0;
    }

    void declareParam(String name) {
        ownParams.add(name);
        paramOrder.add(name);
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
        if (name.equals("this") && closureObjSlot >= 0) {
            usesThis = true;
        }
        if ("GLOBALS".equals(name)) {
            return new GlobalVarNode(name, -1);
        }
        if (global || SUPERGLOBALS.contains(name)) {
            if (global && !SUPERGLOBALS.contains(name)) { globalNames.add(name); }
            return new GlobalVarNode(name, language.globalId(name));
        }
        if (included) {
            if (name.equals("this")) { return new DynamicVarNode(new ConstNode(name), tableSlot, true); }
            return new IncludedVarNode(slot(name), name);
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
        return slots.computeIfAbsent(name, n -> {
            Integer reserved = literalSlots.remove(n);
            return reserved != null ? reserved : frame.addSlot(FrameSlotKind.Illegal, n, null);
        });
    }

    int intSlot(String name) {
        return slots.computeIfAbsent(name, n -> frame.addSlot(FrameSlotKind.Int, n, null));
    }

    int generatorSlot() {
        if (generatorSlot < 0) {
            generatorSlot = objectSlot();
        }
        return generatorSlot;
    }

    boolean isGenerator() {
        return generatorSlot >= 0;
    }

    boolean usesThis() {
        return usesThis;
    }

    int resumeSlot() {
        return frame.addSlot(FrameSlotKind.Int, "@resume" + hiddenId++, null);
    }

    int objectSlot() {
        return frame.addSlot(FrameSlotKind.Object, "@saved" + hiddenId++, null);
    }

    GotoScope gotoScope() {
        if (gotoScope == null) {
            gotoScope = new GotoScope();
        }
        return gotoScope;
    }

    void validateGotos() {
        if (gotoScope != null) {
            gotoScope.validate();
        }
    }

    static final class GotoScope {
        private record LabelInfo(int id, List<Integer> loopPath, List<Integer> finallyPath, int line) {}
        private record GotoUse(String name, List<Integer> loopPath, List<Integer> finallyPath, int line) {}

        private final Map<String, Integer> labelIds = new HashMap<>();
        private final Map<String, LabelInfo> labels = new HashMap<>();
        private final List<GotoUse> gotos = new ArrayList<>();
        private final List<Integer> loopStack = new ArrayList<>();
        private final List<Integer> finallyStack = new ArrayList<>();
        private int nextLabelId;
        private int nextLoopId;
        private int nextFinallyId;
        private int nextDispatchId;

        int nextDispatchId() {
            return nextDispatchId++;
        }

        void enterLoop() {
            loopStack.add(nextLoopId++);
        }

        void exitLoop() {
            loopStack.remove(loopStack.size() - 1);
        }

        void enterFinally() {
            finallyStack.add(nextFinallyId++);
        }

        void exitFinally() {
            finallyStack.remove(finallyStack.size() - 1);
        }

        int defineLabel(String name, int line) {
            if (labels.containsKey(name)) {
                throw new PhpParseError("Label '" + name + "' already defined", line);
            }
            int id = labelIds.computeIfAbsent(name, k -> nextLabelId++);
            labels.put(name, new LabelInfo(id, List.copyOf(loopStack), List.copyOf(finallyStack), line));
            return id;
        }

        int useGoto(String name, int line) {
            gotos.add(new GotoUse(name, List.copyOf(loopStack), List.copyOf(finallyStack), line));
            return labelIds.computeIfAbsent(name, k -> nextLabelId++);
        }

        void validate() {
            for (GotoUse g : gotos) {
                LabelInfo lbl = labels.get(g.name());
                if (lbl == null) {
                    throw new PhpParseError("'goto' to undefined label '" + g.name() + "'", g.line());
                }
                if (!isPrefix(lbl.loopPath(), g.loopPath())) {
                    throw new PhpParseError("'goto' into loop or switch statement is disallowed", g.line());
                }
                if (!isPrefix(g.finallyPath(), lbl.finallyPath())) {
                    throw new PhpParseError("jump out of a finally block is disallowed", g.line());
                }
                if (!isPrefix(lbl.finallyPath(), g.finallyPath())) {
                    throw new PhpParseError("jump into a finally block is disallowed", g.line());
                }
            }
        }

        private static boolean isPrefix(List<Integer> prefix, List<Integer> full) {
            if (prefix.size() > full.size()) {
                return false;
            }
            for (int i = 0; i < prefix.size(); i++) {
                if (!prefix.get(i).equals(full.get(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    boolean isGlobal() {
        return global;
    }

    FrameDescriptor build() {
        if (global) { frame.info(new dev.phpruntime.runtime.PhpGlobalLayout(globalNames.toArray(new String[0]))); }
        if (tableSlot >= 0) {
            Map<String, Integer> locals = new java.util.LinkedHashMap<>();
            for (String param : paramOrder) { locals.put(param, slots.get(param)); }
            for (var entry : slots.entrySet()) {
                if (!entry.getKey().startsWith("@") && !entry.getKey().equals("this")) {
                    locals.put(entry.getKey(), entry.getValue());
                }
            }
            int cellCount = Math.max(slots.values().stream().mapToInt(Integer::intValue).max().orElse(-1),
                    literalSlots.values().stream().mapToInt(Integer::intValue).max().orElse(-1)) + 1;
            frame.info(new PhpScopeLayout(tableSlot, cellCount, locals, literalSlots, thisSlot, calledClassSlot, traitSelfSlot, included));
        }
        return frame.build();
    }
}
