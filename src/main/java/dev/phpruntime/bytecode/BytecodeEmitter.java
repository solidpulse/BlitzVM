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

    private BytecodeEmitter(PhpBytecodeRootNodeGen.Builder b, PhpLanguage language, PhpFunctionRootNode fn) {
        this.b = b;
        this.language = language;
        this.fn = fn;
        this.source = fn.source();
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
            if (fn.usesSymbolTable()) {
                throw new Unsupported("dynamic scope");
            }
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
            currentLine = whole.getStartLine();
        }
        b.emitEnter();
        bindSpecialSlots();
        bindParameters();
        stmt(fn.body());
        b.beginReturn();
        b.emitLoadConstant(PhpNull.INSTANCE);
        b.endReturn();
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

    /** Emits a node whose value, if any, is not used. */
    public void stmt(PhpNode node) {
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
        } else if (node instanceof dev.phpruntime.nodes.var.PropertyLValueNode p) {
            place(p, PhpBytecodeRootNode.P_READ_IF_SET, 0, null);
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

    private BytecodeLocal accessor(int slot, String name) {
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
    public void place(dev.phpruntime.nodes.var.LValueNode lv, int mode, int arg, Runnable value) {
        Form f = form(lv);
        if (f.local() != null) {
            b.beginVarPlace(accessor(f.local()), info(f.local()), f.spec(), mode, arg);
        } else {
            b.beginObjPlace(f.spec(), mode, arg);
        }
        operands(f);
        if (value != null) {
            value.run();
        }
        if (f.local() != null) {
            b.endVarPlace();
        } else {
            b.endObjPlace();
        }
    }

    /** An argument that is a non-local lvalue: by reference or by value, as the callee decides. */
    public void sendPlace(dev.phpruntime.nodes.var.LValueNode lv, int param, BytecodeLocal callee) {
        Form f = form(lv);
        if (f.local() != null) {
            b.beginSendVarPlace(accessor(f.local()), info(f.local()), f.spec(), param, callee);
        } else {
            b.beginSendObjPlace(f.spec(), param, callee);
        }
        operands(f);
        if (f.local() != null) {
            b.endSendVarPlace();
        } else {
            b.endSendObjPlace();
        }
    }

    private record Form(PhpBytecodeRootNode.PlaceSpec spec, LocalVarNode local,
                        dev.phpruntime.nodes.var.PropertyLValueNode property, PhpNode[] keys) {
    }

    private Form form(dev.phpruntime.nodes.var.LValueNode lv) {
        if (lv instanceof dev.phpruntime.nodes.var.IndexLValueNode il) {
            if (il.base() instanceof LocalVarNode local) {
                var template = il.headless(language, new dev.phpruntime.nodes.var.HeldValueBase(local.varName()));
                return new Form(new PhpBytecodeRootNode.PlaceSpec(template, il.keyNodes().length, false),
                        local, null, il.keyNodes());
            }
            if (il.base() instanceof dev.phpruntime.nodes.var.PropertyLValueNode p && !p.usesTraitSelf()) {
                var template = il.headless(language, p.headless());
                return new Form(new PhpBytecodeRootNode.PlaceSpec(template, il.keyNodes().length, true),
                        null, p, il.keyNodes());
            }
            throw new Unsupported("index of " + il.base().getClass().getSimpleName());
        }
        if (lv instanceof dev.phpruntime.nodes.var.PropertyLValueNode p && !p.usesTraitSelf()) {
            return new Form(new PhpBytecodeRootNode.PlaceSpec(p.headless(), 0, true), null, p, new PhpNode[0]);
        }
        throw new Unsupported("place " + lv.getClass().getSimpleName()
                + (lv instanceof dev.phpruntime.nodes.var.PropertyLValueNode ? " in a trait" : ""));
    }

    private void operands(Form f) {
        if (f.property() != null) {
            expr(f.property().targetExpr());
            propertyName(f.property());
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

    public static boolean isPlace(PhpNode n) {
        return n instanceof dev.phpruntime.nodes.var.IndexLValueNode || n instanceof dev.phpruntime.nodes.var.PropertyLValueNode;
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
