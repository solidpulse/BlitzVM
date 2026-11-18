package dev.phpruntime.bytecode;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLabel;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.IndexReadNode;
import dev.phpruntime.nodes.var.LocalVarNode;
import dev.phpruntime.nodes.var.VarKind;
import dev.phpruntime.runtime.PhpNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compiles a function's AST to bytecode. Each node class emits itself through
 * {@link PhpNode#emit}; a node that cannot yet throws {@link Unsupported} and the function
 * keeps running on the AST.
 *
 * <p>{@code -Dblitz.bytecode=off|auto|all}: off compiles nothing; auto (the default) compiles
 * what it can; all fails on the first unsupported node. {@code -Dblitz.bytecode.report=true}
 * prints, at exit, how many functions compiled and the most common unsupported nodes.
 */
public final class BytecodeEmitter {

    private static final String MODE = System.getProperty("blitz.bytecode", "auto");
    private static final boolean REPORT = Boolean.getBoolean("blitz.bytecode.report");
    private static final boolean DUMP = Boolean.getBoolean("blitz.bytecode.dump");
    private static final AtomicInteger COMPILED = new AtomicInteger();
    private static final Map<String, AtomicInteger> UNSUPPORTED = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        if (REPORT) {
            Runtime.getRuntime().addShutdownHook(new Thread(BytecodeEmitter::printReport));
        }
    }

    /** Thrown by a node that has no bytecode form yet. */
    public static final class Unsupported extends RuntimeException {
        Unsupported(String what) {
            super(what, null, false, false);
        }
    }

    private record Loop(BytecodeLabel breakLabel, BytecodeLabel continueLabel) {
    }

    private final PhpBytecodeRootNodeGen.Builder b;
    private final PhpLanguage language;
    private final PhpFunctionRootNode fn;
    private final Source source;
    private final Map<Integer, BytecodeLocal> locals = new HashMap<>();
    private final Map<Integer, PhpBytecodeRootNode.VarInfo> infos = new HashMap<>();
    private final Deque<Loop> loops = new ArrayDeque<>();
    private int currentLine = -1;

    private BytecodeLocal hoisted;

    private BytecodeEmitter(PhpBytecodeRootNodeGen.Builder b, PhpLanguage language, PhpFunctionRootNode fn) {
        this(b, language, fn, fn.source());
    }

    private BytecodeEmitter(PhpBytecodeRootNodeGen.Builder b, PhpLanguage language, PhpFunctionRootNode fn, Source source) {
        this.b = b;
        this.language = language;
        this.fn = fn;
        this.source = source;
    }

    /** The call target for a file's top-level code: bytecode when it compiles, else the AST root's. */
    public static RootCallTarget mainTarget(PhpLanguage language, dev.phpruntime.nodes.PhpMainRootNode main) {
        if (MODE.equals("off") || main.usesSymbolTable()) {
            return main.getCallTarget();
        }
        try {
            PhpBytecodeRootNode root = PhpBytecodeRootNodeGen.create(language, BytecodeConfig.WITH_SOURCE,
                    builder -> new BytecodeEmitter(builder, language, null, main.source()).emitMain(main)).getNode(0);
            root.initMain(main);
            if (DUMP) {
                System.err.println(root.dump());
            }
            COMPILED.incrementAndGet();
            return root.getCallTarget();
        } catch (Unsupported u) {
            if (REPORT) {
                UNSUPPORTED.computeIfAbsent("(file) " + u.getMessage(), k -> new AtomicInteger()).incrementAndGet();
            }
            return main.getCallTarget();
        } catch (RuntimeException builderError) {
            if (MODE.equals("all")) {
                throw builderError;
            }
            UNSUPPORTED.computeIfAbsent("EMITTER BUG " + main.getName() + ": " + builderError.getMessage(),
                    k -> new AtomicInteger()).incrementAndGet();
            return main.getCallTarget();
        }
    }

    private void emitMain(dev.phpruntime.nodes.PhpMainRootNode main) {
        b.beginSource(source);
        b.beginRoot();
        b.beginIfThen();
        b.emitIsEntry();
        b.beginReturn();
        b.emitRunEntry();
        b.endReturn();
        b.endIfThen();
        hoisted = b.createLocal("#hoisted", null);
        b.beginStoreLocal(hoisted);
        b.emitMainPrologue();
        b.endStoreLocal();
        stmt(main.body());
        b.beginReturn();
        b.emitMainResult();
        b.endReturn();
        b.endRoot();
        b.endSource();
    }

    /** The hoisted-class flags of the file being compiled (PHP null inside a function). */
    public void loadHoisted() {
        if (hoisted == null) {
            b.emitLoadConstant(PhpNull.INSTANCE);
        } else {
            b.emitLoadLocal(hoisted);
        }
    }

    /** The call target to use for {@code fn}: bytecode when it compiles, else the AST root's. */
    public static RootCallTarget target(PhpLanguage language, PhpFunctionRootNode fn) {
        RootCallTarget bc = compile(language, fn);
        return bc != null ? bc : fn.getCallTarget();
    }

    static RootCallTarget compile(PhpLanguage language, PhpFunctionRootNode fn) {
        if (MODE.equals("off")) {
            return null;
        }
        try {
            if (fn.isGenerator()) {
                throw new Unsupported("generator");
            }
            if (fn.returnsRef()) {
                throw new Unsupported("return by reference");
            }
            PhpBytecodeRootNode root = PhpBytecodeRootNodeGen.create(language, BytecodeConfig.WITH_SOURCE,
                    builder -> new BytecodeEmitter(builder, language, fn).emitRoot()).getNode(0);
            root.init(fn);
            if (DUMP) {
                System.err.println(root.dump());
            }
            COMPILED.incrementAndGet();
            return root.getCallTarget();
        } catch (Unsupported u) {
            if (REPORT) {
                UNSUPPORTED.computeIfAbsent(u.getMessage(), k -> new AtomicInteger()).incrementAndGet();
            }
            if (MODE.equals("all")) {
                throw new IllegalStateException("bytecode: " + fn.getName() + ": unsupported " + u.getMessage());
            }
            return null;
        } catch (RuntimeException builderError) {
            // An emitter bug (the builder rejects the operation tree): keep the AST, report it.
            if (MODE.equals("all")) {
                throw builderError;
            }
            UNSUPPORTED.computeIfAbsent("EMITTER BUG " + fn.getName() + ": " + builderError.getMessage(),
                    k -> new AtomicInteger()).incrementAndGet();
            return null;
        }
    }

    private static void printReport() {
        int failed = UNSUPPORTED.values().stream().mapToInt(AtomicInteger::get).sum();
        System.err.println("[bytecode] compiled " + COMPILED.get() + ", on the AST " + failed);
        UNSUPPORTED.entrySet().stream()
                .sorted((x, y) -> y.getValue().get() - x.getValue().get())
                .limit(40)
                .forEach(e -> System.err.println("[bytecode]   " + e.getValue().get() + "  " + e.getKey()));
    }

    // ------------------------------------------------------------------ root

    private void emitRoot() {
        b.beginSource(source);
        b.beginRoot();
        SourceSection whole = fn.getSourceSection();
        if (whole != null) {
            // Everything on the declaration's line reports that line; deeper nodes nest their own.
            currentLine = whole.getStartLine();
            b.beginSourceSection(whole.getCharIndex(), whole.getCharLength());
        }
        b.emitEnter();
        bindSpecialSlots();
        bindParameters();
        if (fn.usesSymbolTable()) {
            initSymbols();
        }
        stmt(fn.body());
        b.beginReturn();
        b.emitLoadConstant(PhpNull.INSTANCE);
        b.endReturn();
        if (whole != null) {
            b.endSourceSection();
        }
        b.endRoot();
        b.endSource();
    }

    private void bindSpecialSlots() {
        if (fn.thisSlot() >= 0) {
            storeArgument(fn.thisSlot(), 0);
        }
        if (fn.calledClassSlot() >= 0) {
            storeArgument(fn.calledClassSlot(), 1);
        }
        if (fn.closureObjSlot() >= 0) {
            storeArgument(fn.closureObjSlot(), 2);
            if (fn.traitSelfSlot() >= 0) {
                b.beginStoreLocal(local(fn.traitSelfSlot(), "#traitself"));
                b.emitClosureTraitSelf();
                b.endStoreLocal();
            }
            int[] slots = fn.captureSlots();
            for (int i = 0; i < slots.length; i++) {
                PhpBytecodeRootNode.VarInfo info = info(slots[i], fn.captureKinds()[i], "#capture" + i);
                b.emitBindCapture(accessor(slots[i], info.name()), info, i, fn.captureByRef()[i]);
            }
        } else if (fn.traitSelfSlot() >= 0) {
            storeArgument(fn.traitSelfSlot(), 2);
        }
    }

    /**
     * Dynamic scope ({@code $$x}, {@code extract}, include into the function): every variable
     * lives in a {@code PhpSymbolTable}, built here from the bound parameters and captures as
     * the AST's prologue does, and held in the table local. Variable nodes are places on it.
     */
    private void initSymbols() {
        b.beginStoreLocal(tableLocal());
        b.beginInitSymbols();
        loadSlotOr(fn.thisSlot(), PhpNull.INSTANCE);
        loadSlotOr(fn.calledClassSlot(), PhpNull.INSTANCE);
        loadSlotOr(fn.traitSelfSlot(), PhpNull.INSTANCE);
        for (int slot : fn.captureSlots()) {
            b.emitLoadLocal(locals.get(slot));
        }
        for (int slot : fn.paramSlots()) {
            b.emitLoadLocal(locals.get(slot));
        }
        b.endInitSymbols();
        b.endStoreLocal();
    }

    private void loadSlotOr(int slot, Object fallback) {
        if (slot >= 0 && locals.containsKey(slot)) {
            b.emitLoadLocal(locals.get(slot));
        } else {
            b.emitLoadConstant(fallback);
        }
    }

    /** The local at {@code slot} (a special slot the root may not have), or PHP null. */
    public void loadSlotOrNull(int slot) {
        loadSlotOr(slot, PhpNull.INSTANCE);
    }

    /** The local holding the symbol table of a dynamic-scope function. */
    public BytecodeLocal tableLocal() {
        if (fn == null || !fn.usesSymbolTable()) {
            throw new Unsupported("symbol table outside a dynamic-scope function");
        }
        return local(fn.scopeLayout().tableSlot(), "#symbols");
    }

    /** A variable's value without the undefined warning, or {@code PhpBytecodeRootNode.UNDEFINED}. */
    public void rawRead(dev.phpruntime.nodes.var.LValueNode v) {
        if (v instanceof LocalVarNode lv) {
            b.emitReadVarRaw(accessor(lv));
        } else if (isPlace(v)) {
            place(v, PhpBytecodeRootNode.P_RAW, 0, null);
        } else {
            throw new Unsupported("raw read of " + v.getClass().getSimpleName());
        }
    }

    /** The symbol table for a node's {@code tableSlot}: the table local, or null for globals. */
    public void loadTable(int tableSlot) {
        if (tableSlot < 0) {
            b.emitLoadConstant(PhpNull.INSTANCE);
        } else {
            b.emitLoadLocal(tableLocal());
        }
    }

    private void storeArgument(int slot, int arg) {
        b.beginStoreLocal(local(slot, "#arg" + arg));
        b.emitLoadArgument(arg);
        b.endStoreLocal();
    }

    private void bindParameters() {
        int[] slots = fn.paramSlots();
        VarKind[] kinds = fn.paramKinds();
        PhpNode[] defaults = fn.defaults();
        int offset = fn.argOffset();
        int normal = fn.normalParamCount();
        for (int i = 0; i < normal; i++) {
            String name = fn.paramName(i) != null ? fn.paramName(i) : "#param" + i;
            PhpBytecodeRootNode.VarInfo info = info(slots[i], kinds[i], name);
            BytecodeLocal acc = accessor(slots[i], name);
            if (defaults[i] == null) {
                b.emitBindRequired(acc, info, offset + i, fn.isParamByRef(i), i);
                continue;
            }
            b.beginIfThen();
            b.beginNot();
            b.emitBindParam(acc, info, offset + i, fn.isParamByRef(i));
            b.endNot();
            b.beginStoreVar(acc, info);
            b.beginCopy();
            expr(defaults[i]);
            b.endCopy();
            b.endStoreVar();
            b.endIfThen();
        }
        if (fn.isVariadic()) {
            String name = fn.paramName(normal) != null ? fn.paramName(normal) : "#variadic";
            PhpBytecodeRootNode.VarInfo info = info(slots[normal], kinds[normal], name);
            b.emitBindVariadic(accessor(slots[normal], name), info, offset + normal, fn.isParamByRef(normal));
        }
    }

    // ------------------------------------------------------------------ helpers for nodes

    public PhpBytecodeRootNodeGen.Builder builder() {
        return b;
    }

    public Unsupported unsupported(PhpNode node) {
        return new Unsupported(node.getClass().getSimpleName());
    }

    public Unsupported unsupported(String what) {
        return new Unsupported(what);
    }

    /** Emits a node that produces a value. */
    public void expr(PhpNode node) {
        if (isGotoStructure(node)) {
            node.emit(this);
            return;
        }
        int line = node.line();
        if (line > 0 && line != currentLine && source != null && line <= source.getLineCount()) {
            SourceSection s = source.createSection(line);
            int saved = currentLine;
            currentLine = line;
            b.beginSourceSection(s.getCharIndex(), s.getCharLength());
            node.emit(this);
            b.endSourceSection();
            currentLine = saved;
        } else {
            node.emit(this);
        }
    }

    private static boolean isGotoStructure(PhpNode node) {
        return node instanceof dev.phpruntime.nodes.stmt.GotoDispatchNode
                || node instanceof dev.phpruntime.nodes.stmt.GotoIfNode;
    }

    /** Emits a node whose value, if any, is not used. */
    public void stmt(PhpNode node) {
        if (isGotoStructure(node)) {
            node.emitStatement(this);
            return;
        }
        int line = node.line();
        if (line > 0 && line != currentLine && source != null && line <= source.getLineCount()) {
            SourceSection s = source.createSection(line);
            int saved = currentLine;
            currentLine = line;
            b.beginSourceSection(s.getCharIndex(), s.getCharLength());
            node.emitStatement(this);
            b.endSourceSection();
            currentLine = saved;
        } else {
            node.emitStatement(this);
        }
    }

    /** A value read that must not warn (isset, ??): locals and nested index reads are silent. */
    public void ifSet(PhpNode node) {
        if (node instanceof LocalVarNode lv) {
            b.emitReadVarIfSet(accessor(lv), info(lv));
        } else if (node instanceof IndexReadNode ir) {
            ir.emitIfSet(this);
        } else if (node instanceof dev.phpruntime.nodes.var.IndexLValueNode il) {
            il.emitRead(this, 1);
        } else if (node instanceof dev.phpruntime.nodes.var.PropertyLValueNode
                || node instanceof dev.phpruntime.nodes.var.GlobalVarNode || isTableVar(node)) {
            place((dev.phpruntime.nodes.var.LValueNode) node, PhpBytecodeRootNode.P_READ_IF_SET, 0, null);
        } else {
            expr(node);
        }
    }

    public BytecodeLocal accessor(LocalVarNode lv) {
        return accessor(lv.slot(), lv.varName());
    }

    public PhpBytecodeRootNode.VarInfo info(LocalVarNode lv) {
        return info(lv.slot(), lv.kind(), lv.varName());
    }

    private BytecodeLocal local(int slot, String name) {
        return locals.computeIfAbsent(slot, s -> b.createLocal(name, null));
    }

    public BytecodeLocal accessor(int slot, String name) {
        return local(slot, name);
    }

    private PhpBytecodeRootNode.VarInfo info(int slot, VarKind kind, String name) {
        return infos.computeIfAbsent(slot, s -> new PhpBytecodeRootNode.VarInfo(kind, name));
    }

    public BytecodeLocal temp() {
        return b.createLocal();
    }

    private final java.util.List<BytecodeLocal> calleeLocals = new java.util.ArrayList<>();
    private int callDepth;

    /**
     * The local holding the callee of the call being emitted. Calls nested in another call's
     * arguments are deeper and get their own; calls at the same depth share one, which keeps
     * frames small.
     */
    public BytecodeLocal enterCall() {
        if (callDepth == calleeLocals.size()) {
            calleeLocals.add(b.createLocal("#callee" + callDepth, null));
        }
        return calleeLocals.get(callDepth++);
    }

    public void exitCall() {
        callDepth--;
    }

    // ------------------------------------------------------------------ places

    /**
     * Emits a non-local lvalue in {@code mode} (a {@code PhpBytecodeRootNode.P_*} constant): the
     * key operands in evaluation order, then {@code value} if the mode takes one.
     */
    /** The cached template and operand layout of a place, for operations that evaluate its keys. */
    public PhpBytecodeRootNode.PlaceSpec placeSpec(dev.phpruntime.nodes.var.LValueNode lv) {
        return form(lv).spec();
    }

    public void place(dev.phpruntime.nodes.var.LValueNode lv, int mode, int arg, Runnable value) {
        Form f = form(lv);
        if (f.local() != null) {
            b.beginVarPlace(accessor(f.local()), info(f.local()), f.spec(), mode, arg);
        } else if (f.global()) {
            b.beginGlobalPlace(f.spec(), mode, arg);
        } else if (f.staticProperty()) {
            b.beginStaticPlace(f.spec(), mode, arg);
        } else if (isGlobalsElement(f.spec())) {
            b.beginGlobalsPlace(f.spec(), mode, arg);
        } else {
            b.beginObjPlace(f.spec(), mode, arg);
        }
        operands(f);
        if (value != null) {
            value.run();
        }
        if (f.local() != null) {
            b.endVarPlace();
        } else if (f.global()) {
            b.endGlobalPlace();
        } else if (f.staticProperty()) {
            b.endStaticPlace();
        } else if (isGlobalsElement(f.spec())) {
            b.endGlobalsPlace();
        } else {
            b.endObjPlace();
        }
    }

    /** {@code target ??= value} on a place: keys are evaluated once, then read and written. */
    public void coalescePlace(dev.phpruntime.nodes.var.LValueNode target, Runnable value) {
        Form f = form(target);
        b.beginBlock();
        BytecodeLocal prepared = temp();
        b.beginStoreLocal(prepared);
        if (f.local() != null) {
            b.beginVarPlace(accessor(f.local()), info(f.local()), f.spec(), PhpBytecodeRootNode.P_KEYS, 0);
        } else if (f.global()) {
            b.beginGlobalPlace(f.spec(), PhpBytecodeRootNode.P_KEYS, 0);
        } else if (f.staticProperty()) {
            b.beginStaticPlace(f.spec(), PhpBytecodeRootNode.P_KEYS, 0);
        } else if (isGlobalsElement(f.spec())) {
            b.beginGlobalsPlace(f.spec(), PhpBytecodeRootNode.P_KEYS, 0);
        } else {
            b.beginObjPlace(f.spec(), PhpBytecodeRootNode.P_KEYS, 0);
        }
        operands(f);
        if (f.local() != null) {
            b.endVarPlace();
        } else if (f.global()) {
            b.endGlobalPlace();
        } else if (f.staticProperty()) {
            b.endStaticPlace();
        } else if (isGlobalsElement(f.spec())) {
            b.endGlobalsPlace();
        } else {
            b.endObjPlace();
        }
        b.endStoreLocal();
        b.beginCoalesce();
        b.beginPreparedReadIfSet(f.spec());
        b.emitLoadLocal(prepared);
        b.endPreparedReadIfSet();
        if (f.local() != null) {
            b.beginPreparedVarWrite(f.spec(), accessor(f.local()), info(f.local()));
        } else {
            b.beginPreparedWrite(f.spec());
        }
        b.emitLoadLocal(prepared);
        value.run();
        if (f.local() != null) {
            b.endPreparedVarWrite();
        } else {
            b.endPreparedWrite();
        }
        b.endCoalesce();
        b.endBlock();
    }

    /** An argument that is a non-local lvalue: by reference or by value, as the callee decides. */
    public void sendPlace(dev.phpruntime.nodes.var.LValueNode lv, int param, BytecodeLocal callee) {
        Form f = form(lv);
        if (f.local() != null) {
            b.beginSendVarPlace(accessor(f.local()), info(f.local()), f.spec(), param, callee);
        } else if (f.global()) {
            b.beginSendGlobalPlace(f.spec(), param, callee);
        } else if (f.staticProperty()) {
            b.beginSendStaticPlace(f.spec(), param, callee);
        } else if (isGlobalsElement(f.spec())) {
            b.beginSendGlobalsPlace(f.spec(), param, callee);
        } else {
            b.beginSendObjPlace(f.spec(), param, callee);
        }
        operands(f);
        if (f.local() != null) {
            b.endSendVarPlace();
        } else if (f.global()) {
            b.endSendGlobalPlace();
        } else if (f.staticProperty()) {
            b.endSendStaticPlace();
        } else if (isGlobalsElement(f.spec())) {
            b.endSendGlobalsPlace();
        } else {
            b.endSendObjPlace();
        }
    }

    /** Whether a place is a {@code $GLOBALS[...]} element or an index path rooted in one. */
    private static boolean isGlobalsElement(PhpBytecodeRootNode.PlaceSpec spec) {
        return spec.template() instanceof dev.phpruntime.nodes.var.GlobalsElementLValueNode
                || (spec.template() instanceof dev.phpruntime.nodes.var.IndexLValueNode il
                && il.base() instanceof dev.phpruntime.nodes.var.GlobalsElementLValueNode);
    }

    /** The reference cell of a local or a place, for {@code =&} / destructuring / foreach by reference. */
    public void cell(dev.phpruntime.nodes.var.LValueNode lv) {
        if (lv instanceof LocalVarNode local) {
            b.emitObtainVarCell(accessor(local), info(local));
        } else if (isPlace(lv)) {
            place(lv, PhpBytecodeRootNode.P_CELL, 0, null);
        } else {
            throw new Unsupported("reference to " + lv.getClass().getSimpleName());
        }
    }

    /**
     * A destructuring target of an assignment or foreach: the value or cell is the source,
     * then each element is written or bound as {@code DestructureLValueNode} does.
     */
    private void destructureFrom(dev.phpruntime.nodes.var.DestructureLValueNode target, Runnable value) {
        b.beginBlock();
        BytecodeLocal state = temp();
        b.beginStoreLocal(state);
        b.beginDestructureStartValue(target.hasAnyRef());
        value.run();
        b.endDestructureStartValue();
        b.endStoreLocal();
        destructureSteps(target, state);
        b.beginDestructureResult();
        b.emitLoadLocal(state);
        b.endDestructureResult();
        b.endBlock();
    }

    /** Emits the element steps of one destructuring pattern, using {@code state} as its source. */
    public void destructureSteps(dev.phpruntime.nodes.var.DestructureLValueNode target,
                                 BytecodeLocal state) {        PhpNode[] keys = target.keyNodes();
        dev.phpruntime.nodes.var.LValueNode[] targets = target.targetNodes();
        long[] autos = target.autoIndices();
        boolean[] refs = target.byRef();
        for (int i = 0; i < targets.length; i++) {
            dev.phpruntime.nodes.var.LValueNode t = targets[i];
            if (t == null) {
                b.beginDestructureSkipStep();
                b.emitLoadLocal(state);
                sourceKey(keys[i], autos[i]);
                b.endDestructureSkipStep();
                continue;
            }
            if (t instanceof dev.phpruntime.nodes.var.DestructureLValueNode nested) {
                BytecodeLocal child = temp();
                b.beginStoreLocal(child);
                b.beginDestructureNestedStep(nested.hasAnyRef(), refs[i]);
                b.emitLoadLocal(state);
                sourceKey(keys[i], autos[i]);
                b.endDestructureNestedStep();
                b.endStoreLocal();
                destructureSteps(nested, child);
                continue;
            }
            if (t instanceof LocalVarNode local) {
                b.beginDestructureLocalStep(accessor(local), info(local), refs[i]);
                b.emitLoadLocal(state);
                sourceKey(keys[i], autos[i]);
                b.endDestructureLocalStep();
                continue;
            }
            Form f = form(t);
            if (f.local() != null) {
                b.beginDestructureVarStep(accessor(f.local()), info(f.local()), f.spec(), refs[i]);
            } else if (f.global()) {
                b.beginDestructureGlobalStep(f.spec(), refs[i]);
            } else if (f.staticProperty()) {
                b.beginDestructureStaticStep(f.spec(), refs[i]);
            } else if (isGlobalsElement(f.spec())) {
                b.beginDestructureGlobalsStep(f.spec(), refs[i]);
            } else {
                b.beginDestructureObjStep(f.spec(), refs[i]);
            }
            b.emitLoadLocal(state);
            sourceKey(keys[i], autos[i]);
            operands(f);
            if (f.local() != null) {
                b.endDestructureVarStep();
            } else if (f.global()) {
                b.endDestructureGlobalStep();
            } else if (f.staticProperty()) {
                b.endDestructureStaticStep();
            } else if (isGlobalsElement(f.spec())) {
                b.endDestructureGlobalsStep();
            } else {
                b.endDestructureObjStep();
            }
        }
    }

    private void sourceKey(PhpNode key, long autoIndex) {
        if (key != null) {
            expr(key);
        } else {
            b.emitLoadConstant(autoIndex);
        }
    }

    /** Binds {@code target} to the reference cell emitted by {@code cell}. */
    public void bind(dev.phpruntime.nodes.var.LValueNode target, Runnable cell) {
        if (target instanceof dev.phpruntime.nodes.var.DestructureLValueNode destructure) {
            destructureFrom(destructure, cell);
            return;
        }
        if (target instanceof LocalVarNode local) {
            b.beginBindVarCell(accessor(local), info(local));
            cell.run();
            b.endBindVarCell();
        } else if (isPlace(target)) {
            place(target, PhpBytecodeRootNode.P_BIND, 0, cell);
        } else {
            throw new Unsupported("assign by reference to " + target.getClass().getSimpleName());
        }
    }

    /** Starts a by-reference foreach over a variable or place subject. */
    public void refStart(dev.phpruntime.nodes.var.LValueNode subject, String describe) {
        String caller = callerConstant();
        if (subject instanceof LocalVarNode local) {
            b.beginForeachRefStartVar(accessor(local), info(local), describe, caller);
            traitSelfOperand();
            b.endForeachRefStartVar();
            return;
        }
        Form f = form(subject);
        if (f.local() != null) {
            b.beginForeachRefStartVarPlace(accessor(f.local()), info(f.local()), f.spec(), describe, caller);
        } else if (f.global()) {
            b.beginForeachRefStartGlobal(f.spec(), describe, caller);
        } else if (f.staticProperty()) {
            b.beginForeachRefStartStatic(f.spec(), describe, caller);
        } else if (isGlobalsElement(f.spec())) {
            b.beginForeachRefStartGlobals(f.spec(), describe, caller);
        } else {
            b.beginForeachRefStartObj(f.spec(), describe, caller);
        }
        traitSelfOperand();
        operands(f);
        if (f.local() != null) {
            b.endForeachRefStartVarPlace();
        } else if (f.global()) {
            b.endForeachRefStartGlobal();
        } else if (f.staticProperty()) {
            b.endForeachRefStartStatic();
        } else if (isGlobalsElement(f.spec())) {
            b.endForeachRefStartGlobals();
        } else {
            b.endForeachRefStartObj();
        }
    }

    /** The enclosing function's trait-self class as an operand, or PHP null. */
    public void traitSelfOperand() {
        if (fn != null && fn.traitSelfSlot() >= 0) {
            loadSlotOrNull(fn.traitSelfSlot());
        } else {
            b.emitLoadConstant(PhpNull.INSTANCE);
        }
    }

    /** The enclosing function's declaring class, or an empty string outside a class. */
    public String callerConstant() {
        String declaring = fn != null ? fn.declaringClass() : null;
        return declaring != null ? declaring : "";
    }

    /** {@code base} emits the base keys of a property or static place; {@code keys} the indexes. */
    private record Form(PhpBytecodeRootNode.PlaceSpec spec, LocalVarNode local,
                        Runnable base, PhpNode[] keys, boolean global, boolean staticProperty) {
    }

    /** A variable of a dynamic-scope function, or a computed variable name. */
    public static boolean isTableVar(PhpNode n) {
        return n instanceof dev.phpruntime.nodes.var.ScopedVarNode || n instanceof dev.phpruntime.nodes.var.DynamicVarNode;
    }

    private dev.phpruntime.nodes.var.LValueNode tableHeadless(dev.phpruntime.nodes.var.LValueNode v) {
        return v instanceof dev.phpruntime.nodes.var.ScopedVarNode sv ? sv.headless()
                : ((dev.phpruntime.nodes.var.DynamicVarNode) v).headless();
    }

    private Runnable tableBase(dev.phpruntime.nodes.var.LValueNode v) {
        if (v instanceof dev.phpruntime.nodes.var.ScopedVarNode sv) {
            return () -> {
                b.emitLoadLocal(tableLocal());
                b.emitLoadConstant(sv.varName());
            };
        }
        var dv = (dev.phpruntime.nodes.var.DynamicVarNode) v;
        return () -> {
            loadTable(dv.tableSlot());
            b.beginToStr();
            expr(dv.nameExpr());
            b.endToStr();
        };
    }

    private Runnable propertyBase(dev.phpruntime.nodes.var.PropertyLValueNode p) {
        return () -> {
            expr(p.targetExpr());
            propertyName(p);
            if (p.usesTraitSelf()) {
                loadSlotOrNull(p.traitSelfSlot());
            }
        };
    }

    /** The base-operand count of a property place: object, name and, in a trait, the caller. */
    private static int propertyBaseCount(dev.phpruntime.nodes.var.PropertyLValueNode p) {
        return p.usesTraitSelf() ? 3 : 2;
    }

    private Runnable staticBase(dev.phpruntime.nodes.var.StaticPropertyLValueNode p) {
        return () -> {
            classRef(p.classRef());
            if (p.staticName() != null) {
                b.emitLoadConstant(p.staticName());
            } else {
                b.beginToStr();
                expr(p.dynamicNameExpr());
                b.endToStr();
            }
            if (p.usesTraitSelf()) {
                loadSlotOrNull(p.traitSelfSlot());
            }
        };
    }

    /** The base-operand count of a static property place: class, name and the trait caller. */
    private static int staticBaseCount(dev.phpruntime.nodes.var.StaticPropertyLValueNode p) {
        return p.usesTraitSelf() ? 3 : 2;
    }

    private static int globalsBaseCount(dev.phpruntime.nodes.var.GlobalsElementLValueNode g) {
        return g.hasConstantName() ? 0 : 1;
    }

    private Runnable globalsBase(dev.phpruntime.nodes.var.GlobalsElementLValueNode g) {
        return () -> {
            if (g.hasConstantName()) {
                return;
            }
            if (g.keyNode() == null) {
                throw new Unsupported("append to $GLOBALS");
            }
            expr(g.keyNode());
        };
    }

    private Form form(dev.phpruntime.nodes.var.LValueNode lv) {
        if (lv instanceof dev.phpruntime.nodes.var.IndexLValueNode il) {
            if (il.base() instanceof LocalVarNode local) {
                var template = il.headless(language, new dev.phpruntime.nodes.var.HeldValueBase(local.varName()));
                return new Form(new PhpBytecodeRootNode.PlaceSpec(template, il.keyNodes().length),
                        local, null, il.keyNodes(), false, false);
            }
            if (il.base() instanceof dev.phpruntime.nodes.var.GlobalVarNode g && !g.isGlobalsSuperglobal()) {
                var template = il.headless(language, g.headless());
                return new Form(new PhpBytecodeRootNode.PlaceSpec(template, il.keyNodes().length),
                        null, null, il.keyNodes(), true, false);
            }
            if (il.base() instanceof dev.phpruntime.nodes.var.PropertyLValueNode p) {
                var template = il.headless(language, p.headless());
                return new Form(new PhpBytecodeRootNode.PlaceSpec(template, il.keyNodes().length,
                        propertyBaseCount(p)), null, propertyBase(p), il.keyNodes(), false, false);
            }
            if (il.base() instanceof dev.phpruntime.nodes.var.StaticPropertyLValueNode sp) {
                var template = il.headless(language, sp.headless());
                return new Form(new PhpBytecodeRootNode.PlaceSpec(template, il.keyNodes().length,
                        staticBaseCount(sp)), null, staticBase(sp), il.keyNodes(), false, true);
            }
            if (il.base() instanceof dev.phpruntime.nodes.var.GlobalsElementLValueNode g) {
                var template = il.headless(language, g.headless());
                return new Form(new PhpBytecodeRootNode.PlaceSpec(template, il.keyNodes().length,
                        globalsBaseCount(g)), null, globalsBase(g), il.keyNodes(), false, false);
            }
            if (isTableVar(il.base())) {
                var template = il.headless(language, tableHeadless(il.base()));
                return new Form(new PhpBytecodeRootNode.PlaceSpec(template, il.keyNodes().length),
                        null, tableBase(il.base()), il.keyNodes(), false, false);
            }
            throw new Unsupported("index of " + il.base().getClass().getSimpleName());
        }
        if (lv instanceof dev.phpruntime.nodes.var.PropertyLValueNode p) {
            return new Form(new PhpBytecodeRootNode.PlaceSpec(p.headless(), 0, propertyBaseCount(p)),
                    null, propertyBase(p), new PhpNode[0], false, false);
        }
        if (lv instanceof dev.phpruntime.nodes.var.StaticPropertyLValueNode sp) {
            return new Form(new PhpBytecodeRootNode.PlaceSpec(sp.headless(), 0, staticBaseCount(sp)),
                    null, staticBase(sp), new PhpNode[0], false, true);
        }
        if (isTableVar(lv)) {
            return new Form(new PhpBytecodeRootNode.PlaceSpec(tableHeadless(lv), 0), null, tableBase(lv),
                    new PhpNode[0], false, false);
        }
        if (lv instanceof dev.phpruntime.nodes.var.GlobalVarNode g) {
            return new Form(new PhpBytecodeRootNode.PlaceSpec(g.headless(), 0), null, null,
                    new PhpNode[0], true, false);
        }
        if (lv instanceof dev.phpruntime.nodes.var.GlobalsElementLValueNode g) {
            return new Form(new PhpBytecodeRootNode.PlaceSpec(g.headless(), 0, globalsBaseCount(g)),
                    null, globalsBase(g), new PhpNode[0], false, false);
        }
        throw new Unsupported("place " + lv.getClass().getSimpleName()
                + (lv instanceof dev.phpruntime.nodes.var.PropertyLValueNode ? " in a trait" : ""));
    }

    private void operands(Form f) {
        if (f.base() != null) {
            f.base().run();
        }
        for (PhpNode k : f.keys()) {
            if (k == null) {
                b.emitLoadConstant(dev.phpruntime.nodes.var.IndexLValueNode.APPEND);
            } else {
                expr(k);
            }
        }
    }

    private void propertyName(dev.phpruntime.nodes.var.PropertyLValueNode p) {
        if (p.staticName() != null) {
            b.emitLoadConstant(p.staticName());
        } else {
            b.beginToStr();
            expr(p.dynamicNameExpr());
            b.endToStr();
        }
    }

    /** Emits a class reference as an operation producing the {@code PhpClass}. */
    public void classRef(dev.phpruntime.nodes.expr.ClassRefNode ref) {
        if (ref.line() <= 0 && currentLine > 0) {
            // The parser only puts the line on the expression around the class reference; the
            // cached copy reports errors (missing class, no parent) and needs it.
            ref.at(currentLine);
        }
        b.beginResolveClass(new PhpBytecodeRootNode.ClassRefSpec(ref));
        classRefOperand(ref);
        b.endResolveClass();
    }

    /** The value a class reference resolves from: the called-class local, the expression, or null. */
    public void classRefOperand(dev.phpruntime.nodes.expr.ClassRefNode ref) {
        if (ref.slot() >= 0) {
            if (!locals.containsKey(ref.slot())) {
                b.emitLoadConstant(PhpNull.INSTANCE);
            } else {
                b.emitLoadLocal(locals.get(ref.slot()));
            }
        } else if (ref.kind() == dev.phpruntime.nodes.expr.ClassRefNode.Kind.DYNAMIC) {
            expr(ref.dynamicExpr());
        } else {
            b.emitLoadConstant(PhpNull.INSTANCE);
        }
    }

    public static boolean isPlace(PhpNode n) {
        return n instanceof dev.phpruntime.nodes.var.IndexLValueNode || n instanceof dev.phpruntime.nodes.var.PropertyLValueNode
                || n instanceof dev.phpruntime.nodes.var.GlobalVarNode
                || n instanceof dev.phpruntime.nodes.var.StaticPropertyLValueNode
                || n instanceof dev.phpruntime.nodes.var.GlobalsElementLValueNode || isTableVar(n);
    }

    /** {@code target = value} where the value is already being emitted by {@code value}. */
    public void assign(dev.phpruntime.nodes.var.LValueNode target, Runnable value) {
        if (target instanceof dev.phpruntime.nodes.var.DestructureLValueNode destructure) {
            destructureFrom(destructure, value);
            return;
        }
        if (target instanceof LocalVarNode lv && !"this".equals(lv.varName())) {
            b.beginStoreVar(accessor(lv), info(lv));
            value.run();
            b.endStoreVar();
        } else if (isPlace(target)) {
            place(target, PhpBytecodeRootNode.P_WRITE, 0, value);
        } else {
            throw new Unsupported("assign to " + target.getClass().getSimpleName());
        }
    }

    /** Starts a spread/named call's raw argument list local. */
    public void beginRawArgs(BytecodeLocal raw) {
        b.beginStoreLocal(raw);
        b.emitNewRawArgs();
        b.endStoreLocal();
    }

    /** Evaluates each argument of a spread/named call into its raw argument list. */
    public void complexArgs(PhpNode[] args, dev.phpruntime.nodes.var.LValueNode[] refTargets,
                            String[] argNames, boolean[] spreads, BytecodeLocal callee, BytecodeLocal raw) {
        for (int i = 0; i < args.length; i++) {
            complexArg(args[i], refTargets != null ? refTargets[i] : null,
                    argNames != null ? argNames[i] : null,
                    spreads != null && spreads[i], callee, raw);
        }
    }

    /**
     * One argument of a spread/named call. A variable or place keeps its cell/keys so a
     * by-reference parameter or a by-reference builtin still receives the right cell.
     */
    public void complexArg(PhpNode arg, dev.phpruntime.nodes.var.LValueNode refTarget, String name,
                           boolean spread, BytecodeLocal callee, BytecodeLocal raw) {
        String n = name == null ? "" : name;
        if (refTarget instanceof LocalVarNode lv) {
            b.emitRawVarArg(accessor(lv), info(lv), n, spread, callee, raw);
            return;
        }
        if (refTarget != null && isPlace(refTarget)) {
            BytecodeLocal prepared = temp();
            b.beginStoreLocal(prepared);
            place(refTarget, PhpBytecodeRootNode.P_KEYS, 0, null);
            b.endStoreLocal();
            b.beginRawPlaceArg(placeSpec(refTarget), n, spread, callee, raw);
            b.emitLoadLocal(prepared);
            b.endRawPlaceArg();
            return;
        }
        if (refTarget == null) {
            int kind = arg instanceof dev.phpruntime.nodes.expr.NewNode ? 2
                    : (arg instanceof dev.phpruntime.nodes.call.CallNode
                    || arg instanceof dev.phpruntime.nodes.call.MethodCallNode
                    || arg instanceof dev.phpruntime.nodes.call.StaticMethodCallNode
                    || arg instanceof dev.phpruntime.nodes.call.CallValueNode) ? 1 : 0;
            b.beginRawValueArg(n, spread, kind, callee, raw);
            expr(arg);
            b.endRawValueArg();
            return;
        }
        throw new Unsupported("complex argument from " + refTarget.getClass().getSimpleName());
    }

    /** Emits one call argument: a variable, a place, or a value, sent against the callee in {@code callee}. */
    public void sendArgument(PhpNode arg, dev.phpruntime.nodes.var.LValueNode refTarget, int param, BytecodeLocal callee) {
        if (arg instanceof LocalVarNode lv) {
            b.emitSendVar(param, accessor(lv), info(lv), callee);
        } else if (refTarget != null) {
            if (!isPlace(refTarget)) {
                throw new Unsupported("argument that may be by reference: " + refTarget.getClass().getSimpleName());
            }
            sendPlace(refTarget, param, callee);
        } else {
            int kind = arg instanceof dev.phpruntime.nodes.expr.NewNode ? 2
                    : (arg instanceof dev.phpruntime.nodes.call.CallNode || arg instanceof dev.phpruntime.nodes.call.MethodCallNode
                    || arg instanceof dev.phpruntime.nodes.call.StaticMethodCallNode
                    || arg instanceof dev.phpruntime.nodes.call.CallValueNode) ? 1 : 0;
            b.beginSendValue(param, kind, callee);
            expr(arg);
            b.endSendValue();
        }
    }

    /**
     * A {@code finally} block for the current try: {@code loopDepth} is the loop nesting at the
     * try, so a {@code break}/{@code continue} that stays inside the try does not run it.
     */
    private record Finally(Runnable emit, int loopDepth, BytecodeLocal exiting) {
    }

    private final Deque<Finally> finallyBlocks = new ArrayDeque<>();

    /**
     * Opens a try that has a finally block; it runs before any return or jump out of it.
     * {@code exiting} is set when the block runs inline for a return or jump, so the try's
     * exception handler does not run it a second time if the block itself throws.
     */
    public void pushFinally(Runnable emit, BytecodeLocal exiting) {
        finallyBlocks.push(new Finally(emit, loops.size(), exiting));
    }

    /** Ends the try: its finally is normally emitted explicitly after the try/catch. */
    public void popFinally() {
        finallyBlocks.pop();
    }

    public boolean hasFinallies() {
        return !finallyBlocks.isEmpty();
    }

    /**
     * Emits every pending finally, innermost first: a {@code return} leaves all of them. The
     * stack is restored afterwards, because the TryCatch emitter still generates the code after
     * the return and needs to know which finally blocks enclose it.
     */
    public void emitFinallies() {
        emitPopped(popFinallies(finallyBlocks.size()));
    }

    /** Emits the finally blocks a {@code break}/{@code continue} of {@code levels} leaves. */
    public void emitFinallies(int levels) {
        int threshold = loops.size() - levels + 1;
        int count = 0;
        for (Finally f : finallyBlocks) {
            if (f.loopDepth() < threshold) {
                break;
            }
            count++;
        }
        emitPopped(popFinallies(count));
    }

    /** Pops {@code count} innermost finally blocks, leaving the enclosing ones in place. */
    private Finally[] popFinallies(int count) {
        Finally[] popped = new Finally[count];
        for (int i = 0; i < count; i++) {
            popped[i] = finallyBlocks.pop();
        }
        return popped;
    }

    /**
     * Emits popped finally blocks innermost first. While one is emitted, the blocks enclosing it
     * are on the stack again, so a {@code return} inside it runs only those outer blocks.
     */
    private void emitPopped(Finally[] popped) {
        int count = popped.length;
        for (int i = 0; i < count; i++) {
            for (int j = count - 1; j > i; j--) {
                finallyBlocks.push(popped[j]);
            }
            b.beginStoreLocal(popped[i].exiting());
            b.emitLoadConstant(Boolean.TRUE);
            b.endStoreLocal();
            popped[i].emit().run();
            for (int j = count - 1; j > i; j--) {
                finallyBlocks.pop();
            }
        }
        for (int j = count - 1; j >= 0; j--) {
            finallyBlocks.push(popped[j]);
        }
    }

    /**
     * One goto structure lowered to a flat piece list. The bytecode builder rejects backward
     * branches, so the pieces run inside a {@code while (true)}: each piece sets a program
     * counter and branches forward to the end of the loop body, which dispatches to the next
     * piece. A {@code goto} is a pc assignment, forward or backward, so loops need no special
     * casing.
     */
    private static final class GotoPiece {
        static final int STMT = 0;
        static final int COND = 1;
        static final int JUMP = 2;

        final int kind;
        final PhpNode node;
        int target;

        GotoPiece(int kind, PhpNode node, int target) {
            this.kind = kind;
            this.node = node;
            this.target = target;
        }
    }

    private static final class GotoMachine {
        final BytecodeLocal pc;
        final BytecodeLabel end;
        final Map<Integer, Integer> labels;

        GotoMachine(BytecodeLocal pc, BytecodeLabel end, Map<Integer, Integer> labels) {
            this.pc = pc;
            this.end = end;
            this.labels = labels;
        }
    }

    private final Deque<GotoMachine> gotoMachines = new ArrayDeque<>();

    /** Lowers a goto dispatch (or a goto-if) into a pc machine and emits it. */
    public void emitGotoMachine(PhpNode root) {
        List<GotoPiece> pieces = new java.util.ArrayList<>();
        Map<Integer, Integer> labels = new HashMap<>();
        compileGoto(root, pieces, labels);
        if (pieces.isEmpty()) {
            return;
        }
        b.beginBlock();
        BytecodeLocal pc = temp();
        b.beginStoreLocal(pc);
        b.emitLoadConstant(0L);
        b.endStoreLocal();
        BytecodeLabel exit = b.createLabel();
        b.beginWhile();
        b.emitLoadConstant(true);
        b.beginBlock();
        int count = pieces.size();
        BytecodeLabel[] targets = new BytecodeLabel[count];
        for (int i = 0; i < count; i++) {
            targets[i] = b.createLabel();
        }
        for (int i = 0; i < count; i++) {
            b.beginIfThen();
            b.beginIdentical(false);
            b.emitLoadLocal(pc);
            b.emitLoadConstant((long) i);
            b.endIdentical();
            b.emitBranch(targets[i]);
            b.endIfThen();
        }
        b.emitBranch(exit);
        BytecodeLabel end = b.createLabel();
        gotoMachines.push(new GotoMachine(pc, end, labels));
        try {
            for (int i = 0; i < count; i++) {
                b.emitLabel(targets[i]);
                GotoPiece piece = pieces.get(i);
                if (piece.kind == GotoPiece.COND) {
                    b.beginIfThen();
                    b.beginNot();
                    expr(piece.node);
                    b.endNot();
                    b.beginBlock();
                    b.beginStoreLocal(pc);
                    b.emitLoadConstant((long) piece.target);
                    b.endStoreLocal();
                    b.emitBranch(end);
                    b.endBlock();
                    b.endIfThen();
                } else if (piece.kind == GotoPiece.JUMP) {
                    b.beginStoreLocal(pc);
                    b.emitLoadConstant((long) piece.target);
                    b.endStoreLocal();
                    b.emitBranch(end);
                    continue;
                } else {
                    stmt(piece.node);
                }
                b.beginStoreLocal(pc);
                b.emitLoadConstant((long) (i + 1));
                b.endStoreLocal();
                b.emitBranch(end);
            }
            b.emitLabel(end);
        } finally {
            gotoMachines.pop();
        }
        b.endBlock();
        b.endWhile();
        b.emitLabel(exit);
        b.endBlock();
    }

    private void compileGoto(PhpNode node, List<GotoPiece> pieces, Map<Integer, Integer> labels) {
        if (node == null) {
            return;
        }
        if (node instanceof dev.phpruntime.nodes.stmt.GotoDispatchNode dispatch) {
            PhpNode[] segments = dispatch.segments();
            int[] ids = dispatch.directLabelIds();
            int[] segmentsOf = dispatch.directLabelSegments();
            for (int segment = 0; segment < segments.length; segment++) {
                for (int i = 0; i < ids.length; i++) {
                    if (segmentsOf[i] == segment) {
                        labels.put(ids[i], pieces.size());
                    }
                }
                compileGoto(segments[segment], pieces, labels);
            }
            return;
        }
        if (node instanceof dev.phpruntime.nodes.stmt.GotoIfNode conditional) {
            GotoPiece cond = new GotoPiece(GotoPiece.COND, conditional.conditionNode(), -1);
            pieces.add(cond);
            compileGoto(conditional.thenNode(), pieces, labels);
            GotoPiece jump = new GotoPiece(GotoPiece.JUMP, null, -1);
            pieces.add(jump);
            cond.target = pieces.size();
            compileGoto(conditional.elseNode(), pieces, labels);
            jump.target = pieces.size();
            return;
        }
        if (node instanceof dev.phpruntime.nodes.stmt.BlockNode block) {
            for (PhpNode statement : block.statements()) {
                compileGoto(statement, pieces, labels);
            }
            return;
        }
        pieces.add(new GotoPiece(GotoPiece.STMT, node, 0));
    }

    /** A php {@code goto}: set the nearest machine's pc and jump to its dispatch. */
    public void gotoJump(int labelId) {
        for (GotoMachine machine : gotoMachines) {
            Integer target = machine.labels.get(labelId);
            if (target != null) {
                if (hasFinallies()) {
                    emitFinallies();
                }
                b.beginStoreLocal(machine.pc);
                b.emitLoadConstant((long) target);
                b.endStoreLocal();
                b.emitBranch(machine.end);
                return;
            }
        }
        throw new Unsupported("goto label outside a dispatch");
    }

    /** Opens a loop: break and continue inside it branch to these labels. */
    public void pushLoop(BytecodeLabel breakLabel, BytecodeLabel continueLabel) {
        loops.push(new Loop(breakLabel, continueLabel));
    }

    public void popLoop() {
        loops.pop();
    }

    public void emitBreak(int levels, boolean isContinue) {
        if (levels < 1 || levels > loops.size()) {
            throw new Unsupported("break/continue out of range");
        }
        emitFinallies(levels);
        Loop target = null;
        int i = 0;
        for (Loop l : loops) {
            if (++i == levels) {
                target = l;
                break;
            }
        }
        b.emitBranch(isContinue ? target.continueLabel() : target.breakLabel());
    }
}
