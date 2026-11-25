package dev.phpruntime.bytecode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.bytecode.BytecodeLocation;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.ShortCircuitOperation;
import com.oracle.truffle.api.bytecode.Variadic;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.source.SourceSection;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.builtins.FastBuiltin;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpRootNode;
import dev.phpruntime.nodes.call.ArrayCallbackNode;
import dev.phpruntime.nodes.call.CallArgHelper;
import dev.phpruntime.nodes.call.CallNode;
import dev.phpruntime.nodes.call.DispatchCallNode;
import dev.phpruntime.nodes.call.SplBuiltinNode;
import dev.phpruntime.nodes.expr.CastNode;
import dev.phpruntime.nodes.expr.CompareNode;
import dev.phpruntime.nodes.expr.CondNode;
import dev.phpruntime.nodes.expr.FirstClassCallableNode;
import dev.phpruntime.nodes.expr.FuncArgsNode;
import dev.phpruntime.nodes.expr.ReadConstantNode;
import dev.phpruntime.nodes.expr.ThrowNode;
import dev.phpruntime.nodes.expr.UnaryNode;
import dev.phpruntime.nodes.stmt.IteratorNode;
import dev.phpruntime.nodes.stmt.TryCatchNode;
import dev.phpruntime.nodes.var.ArrayAccessNode;
import dev.phpruntime.nodes.var.IndexReadNode;
import dev.phpruntime.nodes.var.VarKind;
import dev.phpruntime.runtime.PhpArith;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpBacktrace;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClosure;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpException;
import dev.phpruntime.runtime.PhpGenerator;
import dev.phpruntime.runtime.PhpExceptions;
import dev.phpruntime.runtime.PhpFunction;
import dev.phpruntime.runtime.DeclaredType;
import dev.phpruntime.runtime.CallMode;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpStringBuffer;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.runtime.PhpTypes;
import dev.phpruntime.runtime.SplInterfaces;

import java.util.List;
import java.util.Map;

/**
 * A PHP function, method or closure compiled to Truffle bytecode. The function's metadata
 * (parameters, by-reference flags, declaring class) stays on the AST root it was compiled
 * from, {@link #function()}; callers find it through {@link PhpFunctionRootNode#of}. The
 * calling convention is the AST root's, so call sites do not care which one they call.
 */
@GenerateBytecode(languageClass = PhpLanguage.class, enableQuickening = true, enableYield = true,
        // Every function, method, closure and file root is an implicit RootTag (and its body a
        // RootBodyTag), so instruments such as the CPU sampler see each PHP invocation as a root
        // with its own source section. Runs without an instrument carry no extra nodes.
        enableTagInstrumentation = true, enableRootTagging = true, enableRootBodyTagging = true,
        boxingEliminationTypes = {long.class, double.class, boolean.class},
        enableBlockScoping = false,
        // exit/die skips finally blocks in PHP: it must pass through guest handlers. The
        // snapshot boundary abort behaves the same way.
        unwindExceptions = {ControlFlow.Exit.class, dev.phpruntime.snapshot.SnapshotAbort.class})
@TypeSystemReference(PhpTypes.class)
@ShortCircuitOperation(name = "PhpAnd", operator = ShortCircuitOperation.Operator.AND_RETURN_CONVERTED,
        booleanConverter = PhpBytecodeRootNode.ToBool.class)
@ShortCircuitOperation(name = "PhpOr", operator = ShortCircuitOperation.Operator.OR_RETURN_CONVERTED,
        booleanConverter = PhpBytecodeRootNode.ToBool.class)
@ShortCircuitOperation(name = "Elvis", operator = ShortCircuitOperation.Operator.OR_RETURN_VALUE,
        booleanConverter = PhpBytecodeRootNode.ToBool.class)
@ShortCircuitOperation(name = "Coalesce", operator = ShortCircuitOperation.Operator.OR_RETURN_VALUE,
        booleanConverter = PhpBytecodeRootNode.IsNonNull.class)
public abstract class PhpBytecodeRootNode extends PhpRootNode implements BytecodeRootNode {

    /** Set once by {@link #init} before the root runs, so compiled code reads it as a constant. */
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal private PhpFunctionRootNode function;
    /** {@link PhpFunctionRootNode#refMask} of the function, read by call sites. */
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal private long refMask;
    private dev.phpruntime.nodes.PhpMainRootNode main;
    /** The trait-self slot of an expression root, or -1. */
    private int exprTraitSelfSlot = -1;

    protected PhpBytecodeRootNode(PhpLanguage language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    dev.phpruntime.nodes.PhpMainRootNode main() {
        return main;
    }

    void init(PhpFunctionRootNode fn) {
        this.function = fn;
        this.refMask = fn.refMask();
        setStrictTypes(fn.strictTypes());
        SourceSection ss = fn.getSourceSection();
        initSource(fn.source(), ss != null ? ss.getStartLine() : -1);
    }

    void initMain(dev.phpruntime.nodes.PhpMainRootNode m) {
        this.main = m;
        setStrictTypes(m.strictTypes());
        initSource(m.source(), 1);
    }

    /** A member initializer expression root; {@code traitSelfSlot} is -1 outside a trait. */
    void initExpression(com.oracle.truffle.api.source.Source source, int line, int traitSelfSlot) {
        this.exprTraitSelfSlot = traitSelfSlot;
        initSource(source, line);
    }

    /** Whether this expression root takes the using class as argument 0. */
    public boolean hasTraitSelf() {
        return exprTraitSelfSlot >= 0;
    }

    /** The function this root was compiled from; null for a file's top-level code. */
    public PhpFunctionRootNode function() {
        return function;
    }

    /** The file whose top-level code this root runs; null for a function. */
    public dev.phpruntime.nodes.PhpMainRootNode mainRoot() {
        return main;
    }

    @Override
    public String getName() {
        if (main != null) {
            return main.getName();
        }
        return function != null ? function.getName() : "{member initializer}";
    }

    /**
     * The name tools show for this root: {@code Class::method} for a method, the function's
     * name, {@code {closure:file:line}} for a closure, and {@code {main} file} for a file's
     * top-level code. Unlike {@link #getName()}, which PHP stack traces use, this carries the
     * file and line that a profiler or debugger needs to tell two closures apart.
     */
    @Override
    public String getQualifiedName() {
        if (main != null) {
            return main.qualifiedName();
        }
        if (function != null) {
            return function.qualifiedCallableName();
        }
        return getName();
    }

    @Override
    public String toString() {
        if (main != null) {
            return main.getName();
        }
        return function != null ? function.toString() : "{member initializer}";
    }

    @Override
    public AbstractTruffleException interceptTruffleException(AbstractTruffleException ex, VirtualFrame frame,
                                                              BytecodeNode bytecode, int bci) {
        if (ex instanceof PhpError e && e.getLocation() == null) {
            return locate(e, bytecode, bci);
        }
        return ex;
    }

    @TruffleBoundary
    private static PhpError locate(PhpError e, BytecodeNode bytecode, int bci) {
        SourceSection ss = bci >= 0 ? bytecode.getSourceLocation(bci) : null;
        if (ss == null) {
            return e;
        }
        return e.atLine(ss.getSource().getPath(), ss.getStartLine());
    }

    /** The line of the instruction at {@code bci}, for warnings. */
    @TruffleBoundary
    static int lineOf(BytecodeNode bytecode, int bci) {
        SourceSection ss = bytecode.getSourceLocation(bci);
        return ss != null ? ss.getStartLine() : -1;
    }

    /** The source position a bytecode frame on the stack is executing, for backtraces. */
    @TruffleBoundary
    public static SourceSection callSiteSection(FrameInstance fi) {
        // Without a stored bytecode index, BytecodeLocation finds the call node by scanning the
        // caller's bytecode; backtraces (debug_backtrace per Tempest view) repeat that for every
        // frame. A cached call node sits at one index, so its section is kept on the root. The
        // uncached interpreter reports the bytecode node itself, whose index varies: not cached.
        Node call = fi.getCallNode();
        PhpBytecodeRootNode owner = call == null || call instanceof BytecodeNode ? null
                : call.getRootNode() instanceof PhpBytecodeRootNode r ? r : null;
        if (owner != null) {
            Object hit = owner.siteSections().get(call);
            if (hit != null) {
                return hit == NO_SECTION ? null : (SourceSection) hit;
            }
        }
        BytecodeLocation loc = BytecodeLocation.get(fi);
        SourceSection section = loc == null ? null : loc.getSourceLocation();
        if (owner != null && loc != null) {
            owner.siteSections().put(call, section == null ? NO_SECTION : section);
        }
        return section;
    }

    private static final Object NO_SECTION = new Object();
    private volatile java.util.concurrent.ConcurrentHashMap<Node, Object> siteSections;

    private java.util.concurrent.ConcurrentHashMap<Node, Object> siteSections() {
        java.util.concurrent.ConcurrentHashMap<Node, Object> m = siteSections;
        if (m == null) {
            synchronized (this) {
                m = siteSections;
                if (m == null) {
                    m = new java.util.concurrent.ConcurrentHashMap<>();
                    siteSections = m;
                }
            }
        }
        return m;
    }

    /** What a variable operation needs besides its local: the AST's kind and the name for warnings. */
    public record VarInfo(VarKind kind, String name) {
    }

    /** A call site: the function name and ids as {@code CallNode} has them. */
    public record CallSite(String name, int functionId, String nsName, int nsFunctionId, String splName,
                           boolean keepRef) {
        /** A call whose by-reference result is unwrapped (every context but {@code =&}, by-ref arguments...). */
        public CallSite(String name, int functionId, String nsName, int nsFunctionId, String splName) {
            this(name, functionId, nsName, nsFunctionId, splName, false);
        }

        public CallSite withKeepRef(boolean keep) {
            return keep == keepRef ? this : new CallSite(name, functionId, nsName, nsFunctionId, splName, keep);
        }
    }

    /** A bytecode local seen as an lvalue, for builtin by-reference writeback. */
    public static final class LocalLValue extends dev.phpruntime.nodes.var.LValueNode {
        private final LocalAccessor acc;
        private final VarInfo info;
        private final BytecodeNode bytecode;

        public LocalLValue(LocalAccessor acc, VarInfo info, BytecodeNode bytecode) {
            this.acc = acc;
            this.info = info;
            this.bytecode = bytecode;
        }

        @Override
        public Object read(VirtualFrame frame, Object[] keys) {
            return current(frame, acc, bytecode);
        }

        @Override
        public void write(VirtualFrame frame, Object[] keys, Object value) {
            store(frame, acc, info, bytecode, value);
        }

        @Override
        public PhpReference obtainCell(VirtualFrame frame, Object[] keys) {
            PhpReference ref = cell(frame, acc, info, bytecode);
            ref.ensureDefined();
            return ref;
        }

        @Override
        public void unset(VirtualFrame frame, Object[] keys) {
            acc.clear(bytecode, frame);
        }

        @Override
        public String describe() {
            return "$" + info.name();
        }
    }

    /**
     * A node standing for one bytecode instruction where an error, warning or builtin call can
     * report from: its source section is that instruction's line, so the code that formats
     * errors and builtin warnings from a node works unchanged.
     */
    public static final class SiteNode extends Node {
        private final SourceSection section;

        SiteNode(SourceSection section) {
            this.section = section;
        }

        @TruffleBoundary
        public static SiteNode create(BytecodeNode bytecode, int bci) {
            return new SiteNode(bytecode.getSourceLocation(bci));
        }

        @Override
        public SourceSection getSourceSection() {
            return section;
        }

        int line() {
            return section != null ? section.getStartLine() : -1;
        }
    }

    // ------------------------------------------------------------------ variables

    @TruffleBoundary
    static Object undefined(VarInfo info, BytecodeNode bytecode, int bci, Node node) {
        PhpContext.get(node).warning("Undefined variable $" + info.name(), lineOf(bytecode, bci));
        return PhpNull.INSTANCE;
    }

    /** The current value: unwraps a reference cell or string buffer; null if undefined. */
    static Object current(VirtualFrame frame, LocalAccessor acc, BytecodeNode bytecode) {
        return current(frame, acc, bytecode, com.oracle.truffle.api.profiles.BranchProfile.getUncached());
    }

    /**
     * The value of a variable, through its cell. A {@code .=} target holds an append buffer; few
     * read sites ever see one, so with a cached profile that path is a deoptimization in
     * compiled code instead of a check, a field read and a call inlined into every read.
     */
    static Object current(VirtualFrame frame, LocalAccessor acc, BytecodeNode bytecode, com.oracle.truffle.api.profiles.BranchProfile buffer) {
        if (acc.isCleared(bytecode, frame)) {
            return null;
        }
        Object v = acc.getObject(bytecode, frame);
        if (v instanceof PhpReference ref) {
            v = ref.rawValue();
        }
        if (v instanceof PhpStringBuffer b) {
            buffer.enter();
            return b.asString();
        }
        return v;
    }

    /** The cell of a variable, converting a plain value into one (as {@code LocalVarNode} does). */
    static PhpReference cell(VirtualFrame frame, LocalAccessor acc, VarInfo info, BytecodeNode bytecode) {
        Object v = acc.isCleared(bytecode, frame) ? null : acc.getObject(bytecode, frame);
        if (v instanceof PhpReference ref) {
            return ref;
        }
        PhpReference ref = new PhpReference(v == null ? null : PhpStringBuffer.flatten(v));
        acc.setObject(bytecode, frame, ref);
        return ref;
    }

    static void store(VirtualFrame frame, LocalAccessor acc, VarInfo info, BytecodeNode bytecode, Object v) {
        if (info.kind().isCell()) {
            cell(frame, acc, info, bytecode).setValue(v);
        } else if (v instanceof Long l) {
            if (info.kind().longBoxed()) {
                acc.setObject(bytecode, frame, l);
            } else {
                acc.setLong(bytecode, frame, l);
            }
        } else if (v instanceof Double d) {
            if (info.kind().doubleBoxed()) {
                acc.setObject(bytecode, frame, d);
            } else {
                acc.setDouble(bytecode, frame, d);
            }
        } else {
            info.kind().markObject();
            acc.setObject(bytecode, frame, v);
        }
    }

    /** The local bound directly to a reference cell, as {@code LocalVarNode.bindCell} does. */
    static void bindCell(VirtualFrame frame, LocalAccessor acc, VarInfo info, BytecodeNode bytecode,
                         PhpReference cell) {
        if ("this".equals(info.name())) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Cannot re-assign $this");
        }
        if (!info.kind().isCell()) {
            info.kind().markCell();
        }
        acc.setObject(bytecode, frame, cell);
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    public static final class ReadVar {
        // On a mismatch the DSL returns the exception's value as the result, so it must carry
        // the generic read's value (unwrapped, flattened, or the undefined warning's null).
        @Specialization(guards = {"!info.kind().isCell()", "info.kind().longSlot()"},
                rewriteOn = UnexpectedResultException.class)
        static long readLong(VirtualFrame frame, LocalAccessor acc, VarInfo info, @Bind BytecodeNode bytecode,
                             @Bind("$bytecodeIndex") int bci, @Bind Node node) throws UnexpectedResultException {
            try {
                return acc.getLong(bytecode, frame);
            } catch (UnexpectedResultException e) {
                throw new UnexpectedResultException(read(frame, acc, info, bytecode, bci, node));
            }
        }

        @Specialization(guards = {"!info.kind().isCell()", "info.kind().doubleSlot()"},
                rewriteOn = UnexpectedResultException.class)
        static double readDouble(VirtualFrame frame, LocalAccessor acc, VarInfo info, @Bind BytecodeNode bytecode,
                                 @Bind("$bytecodeIndex") int bci, @Bind Node node) throws UnexpectedResultException {
            try {
                return acc.getDouble(bytecode, frame);
            } catch (UnexpectedResultException e) {
                throw new UnexpectedResultException(read(frame, acc, info, bytecode, bci, node));
            }
        }

        @Specialization(replaces = {"readLong", "readDouble"})
        static Object readGeneric(VirtualFrame frame, LocalAccessor acc, VarInfo info, @Bind BytecodeNode bytecode,
                                  @Bind("$bytecodeIndex") int bci, @Bind Node node,
                                  @Cached("create()") com.oracle.truffle.api.profiles.BranchProfile buffer,
                                  @Cached("create()") com.oracle.truffle.api.profiles.BranchProfile undefinedSeen) {
            Object v = current(frame, acc, bytecode, buffer);
            if (v == null) {
                undefinedSeen.enter();
                return undefined(info, bytecode, bci, node);
            }
            return v;
        }

        static Object read(VirtualFrame frame, LocalAccessor acc, VarInfo info, BytecodeNode bytecode, int bci,
                           Node node) {
            Object v = current(frame, acc, bytecode);
            return v == null ? undefined(info, bytecode, bci, node) : v;
        }
    }

    /** A read that never warns: {@code isset}, {@code ??}, {@code ??=}. Undefined reads as PHP null. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    public static final class ReadVarIfSet {
        @Specialization
        static Object read(VirtualFrame frame, LocalAccessor acc, VarInfo info, @Bind BytecodeNode bytecode,
                           @Cached("create()") com.oracle.truffle.api.profiles.BranchProfile buffer) {
            Object v = current(frame, acc, bytecode, buffer);
            return v == null ? PhpNull.INSTANCE : v;
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    public static final class IssetVar {
        @Specialization
        static boolean isset(VirtualFrame frame, LocalAccessor acc, VarInfo info, @Bind BytecodeNode bytecode,
                             @Cached("create()") com.oracle.truffle.api.profiles.BranchProfile buffer) {
            Object v = current(frame, acc, bytecode, buffer);
            return v != null && v != PhpNull.INSTANCE;
        }
    }

    /** {@code $x = value}: stores a copy (arrays are shared copy-on-write) and yields the value. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    public static final class WriteVar {
        @Specialization(guards = "!info.kind().isCell()")
        static long writeLong(VirtualFrame frame, LocalAccessor acc, VarInfo info, long value,
                              @Bind BytecodeNode bytecode) {
            if (info.kind().longBoxed()) {
                acc.setObject(bytecode, frame, value);
            } else {
                acc.setLong(bytecode, frame, value);
            }
            return value;
        }

        @Specialization(guards = "!info.kind().isCell()")
        static double writeDouble(VirtualFrame frame, LocalAccessor acc, VarInfo info, double value,
                                  @Bind BytecodeNode bytecode) {
            if (info.kind().doubleBoxed()) {
                acc.setObject(bytecode, frame, value);
            } else {
                acc.setDouble(bytecode, frame, value);
            }
            return value;
        }

        @Specialization(replaces = {"writeLong", "writeDouble"})
        static Object write(VirtualFrame frame, LocalAccessor acc, VarInfo info, Object value,
                            @Bind BytecodeNode bytecode) {
            store(frame, acc, info, bytecode, PhpOps.copyOnAssign(value));
            return value;
        }
    }

    /** {@code $x = value;} as a statement: as {@link WriteVar}, without pushing the value. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    public static final class StoreVar {
        @Specialization(guards = "!info.kind().isCell()")
        static void storeLong(VirtualFrame frame, LocalAccessor acc, VarInfo info, long value,
                              @Bind BytecodeNode bytecode) {
            if (info.kind().longBoxed()) {
                acc.setObject(bytecode, frame, value);
            } else {
                acc.setLong(bytecode, frame, value);
            }
        }

        @Specialization(guards = "!info.kind().isCell()")
        static void storeDouble(VirtualFrame frame, LocalAccessor acc, VarInfo info, double value,
                                @Bind BytecodeNode bytecode) {
            if (info.kind().doubleBoxed()) {
                acc.setObject(bytecode, frame, value);
            } else {
                acc.setDouble(bytecode, frame, value);
            }
        }

        @Specialization(replaces = {"storeLong", "storeDouble"})
        static void store(VirtualFrame frame, LocalAccessor acc, VarInfo info, Object value,
                          @Bind BytecodeNode bytecode) {
            PhpBytecodeRootNode.store(frame, acc, info, bytecode, PhpOps.copyOnAssign(value));
        }
    }

    /** {@code $x = &$cell}: binds the local to the reference cell (foreach by reference, =&). */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    public static final class BindVarCell {
        @Specialization
        static void bind(VirtualFrame frame, LocalAccessor acc, VarInfo info, PhpReference cell,
                         @Bind BytecodeNode bytecode) {
            bindCell(frame, acc, info, bytecode, cell);
        }
    }

    /** {@code &$this}: a temporary cell holding the object. */
    @Operation
    public static final class BoxRef {
        @Specialization
        static PhpReference box(Object value) {
            return new PhpReference(value);
        }
    }

    /** {@code &<non-lvalue>} in an array literal: a new cell holding a copy of the value. */
    @Operation
    public static final class BoxRefCopy {
        @Specialization
        static PhpReference box(Object value) {
            // A by-reference return ([&f()]) keeps its reference, as ArrayLiteralNode does.
            return value instanceof PhpReference ref ? ref : new PhpReference(PhpOps.copyOnAssign(value));
        }
    }

    /** The cell of a non-lvalue RHS: a returned cell, else a notice and a copy in a new cell. */
    @Operation
    public static final class RefFromValue {
        @Specialization
        static PhpReference box(Object raw, @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                                @Bind Node node) {
            if (raw instanceof PhpReference ref) {
                ref.ensureDefined();
                return ref;
            }
            PhpContext.get(node).notice("Only variables should be assigned by reference", lineOf(bytecode, bci));
            return new PhpReference(PhpOps.copyOnAssign(raw));
        }
    }

    /** The value of a reference cell, as {@code AssignRefNode.execute} returns it. */
    @Operation
    public static final class RefValue {
        @Specialization
        static Object value(PhpReference cell) {
            Object v = cell.getValue();
            return v == null ? PhpNull.INSTANCE : v;
        }
    }

    /** {@code $x++;} as a statement. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = boolean.class, name = "increment")
    public static final class StepVar {
        @Specialization
        static void step(VirtualFrame frame, LocalAccessor acc, VarInfo info, boolean increment,
                         @Bind BytecodeNode bytecode) {
            if (!info.kind().isCell() && info.kind().longSlot()) {
                try {
                    long l = acc.getLong(bytecode, frame);
                    if (l != Long.MAX_VALUE && l != Long.MIN_VALUE) {
                        long updated = increment ? l + 1 : l - 1;
                        if (info.kind().longBoxed()) {
                            acc.setObject(bytecode, frame, updated);
                        } else {
                            acc.setLong(bytecode, frame, updated);
                        }
                        return;
                    }
                } catch (UnexpectedResultException e) {
                    // not an unboxed int: the generic path below
                }
            }
            IncDecVar.generic(frame, acc, info, increment, true, bytecode);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    public static final class ObtainVarCell {
        @Specialization
        static PhpReference obtain(VirtualFrame frame, LocalAccessor acc, VarInfo info, @Bind BytecodeNode bytecode) {
            if (!info.kind().isCell()) {
                info.kind().markCell();
            }
            PhpReference ref = cell(frame, acc, info, bytecode);
            ref.ensureDefined();
            return ref;
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = boolean.class, name = "increment")
    @ConstantOperand(type = boolean.class, name = "prefix")
    public static final class IncDecVar {
        @Specialization(guards = {"!info.kind().isCell()", "info.kind().longSlot()"},
                rewriteOn = UnexpectedResultException.class)
        static long incLong(VirtualFrame frame, LocalAccessor acc, VarInfo info, boolean increment, boolean prefix,
                            @Bind BytecodeNode bytecode) throws UnexpectedResultException {
            long l;
            try {
                l = acc.getLong(bytecode, frame);
            } catch (UnexpectedResultException e) {
                throw new UnexpectedResultException(generic(frame, acc, info, increment, prefix, bytecode));
            }
            if (l == Long.MAX_VALUE || l == Long.MIN_VALUE) {
                throw new UnexpectedResultException(generic(frame, acc, info, increment, prefix, bytecode));
            }
            long u = increment ? l + 1 : l - 1;
            if (info.kind().longBoxed()) {
                acc.setObject(bytecode, frame, u);
            } else {
                acc.setLong(bytecode, frame, u);
            }
            return prefix ? u : l;
        }

        @Specialization(replaces = "incLong")
        static Object generic(VirtualFrame frame, LocalAccessor acc, VarInfo info, boolean increment, boolean prefix,
                              @Bind BytecodeNode bytecode) {
            Object old = current(frame, acc, bytecode);
            Object updated;
            if (old instanceof Long l && l != Long.MAX_VALUE && l != Long.MIN_VALUE) {
                updated = increment ? l + 1 : l - 1;
            } else {
                updated = Ops.incDecSlow(old, increment);
            }
            store(frame, acc, info, bytecode, updated);
            return prefix ? updated : (old == null ? PhpNull.INSTANCE : old);
        }
    }

    /** {@code $x op= value} for every operator except {@code ??=} (emitted as a coalesce). */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = PhpArith.Op.class, name = "op")
    public static final class CompoundVar {
        @Specialization
        static Object apply(VirtualFrame frame, LocalAccessor acc, VarInfo info, PhpArith.Op op, Object value,
                            @Bind BytecodeNode bytecode) {
            Object current = current(frame, acc, bytecode);
            Object result = Ops.arith(op, current == null ? PhpNull.INSTANCE : current, value);
            store(frame, acc, info, bytecode, result);
            return result;
        }
    }

    /**
     * {@code $x .= value}: appends into a string buffer kept in the variable, as
     * {@code LocalVarNode.appendString} does, so repeated appends are linear. The resulting
     * string is only built when the expression's value is used.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = boolean.class, name = "resultNeeded")
    public static final class AppendVar {
        @Specialization
        static Object append(VirtualFrame frame, LocalAccessor acc, VarInfo info, boolean resultNeeded, Object value,
                             @Bind BytecodeNode bytecode) {
            if (value instanceof PhpObject) {
                Object current = current(frame, acc, bytecode);
                String result = Ops.concat(current == null ? PhpNull.INSTANCE : current, value);
                store(frame, acc, info, bytecode, result);
                return result;
            }
            String suffix = value instanceof String s ? s : Ops.toStr(value);
            info.kind().markObject();
            if (info.kind().isCell()) {
                String r = cell(frame, acc, info, bytecode).appendString(suffix, resultNeeded);
                return resultNeeded ? r : PhpNull.INSTANCE;
            }
            return Ops.appendLocal(frame, acc, bytecode, suffix, resultNeeded);
        }
    }

    // ------------------------------------------------------------------ function entry

    /** Safepoint and argument count check, as {@code PhpFunctionRootNode} does on entry. */
    @Operation
    public static final class Enter {
        @Specialization
        static void enter(VirtualFrame frame, @Bind PhpBytecodeRootNode root, @Bind Node node) {
            TruffleSafepoint.poll(node);
            PhpFunctionRootNode fn = root.function;
            int passed = frame.getArguments().length - fn.argOffset();
            if (passed < fn.requiredArgs()) {
                CompilerDirectives.transferToInterpreter();
                throw fn.tooFewArguments(passed);
            }
        }
    }

    /**
     * Binds parameter {@code param} from argument {@code argIndex}; false when the argument was
     * not passed, so the emitted code evaluates the default.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = int.class, name = "argIndex")
    @ConstantOperand(type = boolean.class, name = "byRef")
    public static final class BindParam {
        @Specialization
        static boolean bind(VirtualFrame frame, LocalAccessor acc, VarInfo info, int argIndex, boolean byRef,
                            @Bind BytecodeNode bytecode) {
            Object[] args = frame.getArguments();
            Object raw = argIndex < args.length ? args[argIndex] : PhpFunctionRootNode.UNPASSED;
            if (raw == PhpFunctionRootNode.UNPASSED) {
                return false;
            }
            if (byRef) {
                acc.setObject(bytecode, frame, raw instanceof PhpReference ref ? ref : new PhpReference(PhpOps.copyOnAssign(raw)));
            } else {
                store(frame, acc, info, bytecode, raw instanceof PhpReference ref ? PhpOps.copyOnAssign(ref.getValue()) : raw);
            }
            return true;
        }
    }

    /** A parameter without a default: binds it, or throws the missing-argument error. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = int.class, name = "argIndex")
    @ConstantOperand(type = boolean.class, name = "byRef")
    @ConstantOperand(type = int.class, name = "param")
    public static final class BindRequired {
        @Specialization
        static void bind(VirtualFrame frame, LocalAccessor acc, VarInfo info, int argIndex, boolean byRef, int param,
                         @Bind BytecodeNode bytecode, @Bind PhpBytecodeRootNode root) {
            if (!BindParam.bind(frame, acc, info, argIndex, byRef, bytecode)) {
                CompilerDirectives.transferToInterpreter();
                throw root.function.missingArgument(param);
            }
        }
    }

    @Operation
    @ConstantOperand(type = int.class, name = "param")
    public static final class MissingArgument {
        @Specialization
        static void fail(int param, @Bind PhpBytecodeRootNode root) {
            CompilerDirectives.transferToInterpreter();
            throw root.function.missingArgument(param);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = int.class, name = "from")
    @ConstantOperand(type = boolean.class, name = "byRef")
    public static final class BindVariadic {
        @Specialization
        static void bind(VirtualFrame frame, LocalAccessor acc, VarInfo info, int from, boolean byRef,
                         @Bind BytecodeNode bytecode) {
            Object[] args = frame.getArguments();
            PhpArray rest;
            if (args.length == from + 1 && args[from] instanceof PhpFunctionRootNode.NamedVariadic nv) {
                rest = nv.entries();
            } else {
                rest = PhpFunctionRootNode.packVariadic(args, from, byRef);
            }
            store(frame, acc, info, bytecode, rest);
        }
    }

    // ------------------------------------------------------------------ declared types

    /** A parameter's value for its type check: the argument or the evaluated default, unwrapped. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = int.class, name = "argIndex")
    public static final class ParamValue {
        @Specialization
        static Object value(VirtualFrame frame, LocalAccessor acc, int argIndex, @Bind BytecodeNode bytecode) {
            Object[] args = frame.getArguments();
            Object raw = argIndex < args.length ? args[argIndex] : PhpFunctionRootNode.UNPASSED;
            if (raw == PhpFunctionRootNode.UNPASSED) {
                raw = acc.isCleared(bytecode, frame) ? null : acc.getObject(bytecode, frame);
            }
            if (raw instanceof PhpReference ref) {
                raw = ref.getValue();
            }
            return raw == null ? PhpNull.INSTANCE : raw;
        }
    }

    /**
     * Checks one parameter after binding. The fast specializations read only the declared
     * type's final flags; a mismatch goes through the boundary, which coerces in weak mode or
     * throws php's TypeError, and writes the coerced value back to the local (or the referenced
     * cell for a by-reference parameter).
     */
    @Operation
    @ConstantOperand(type = DeclaredType.class)
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = int.class, name = "param")
    @ConstantOperand(type = String.class, name = "paramName")
    public static final class CheckParam {
        @Specialization(guards = "type.isMixed()")
        static void mixed(VirtualFrame frame, DeclaredType type, LocalAccessor acc, VarInfo info, int param,
                          String name, Object value) {
        }

        @Specialization(guards = "type.acceptsLong()")
        static void longValue(VirtualFrame frame, DeclaredType type, LocalAccessor acc, VarInfo info, int param,
                              String name, long value) {
        }

        @Specialization(guards = "type.acceptsDouble()")
        static void doubleValue(VirtualFrame frame, DeclaredType type, LocalAccessor acc, VarInfo info, int param,
                                String name, double value) {
        }

        @Specialization(guards = "type.acceptsString()")
        static void stringValue(VirtualFrame frame, DeclaredType type, LocalAccessor acc, VarInfo info, int param,
                                String name, String value) {
        }

        @Specialization(guards = "type.acceptsBool()")
        static void boolValue(VirtualFrame frame, DeclaredType type, LocalAccessor acc, VarInfo info, int param,
                              String name, boolean value) {
        }

        @Specialization(guards = "type.acceptsArray()")
        static void arrayValue(VirtualFrame frame, DeclaredType type, LocalAccessor acc, VarInfo info, int param,
                               String name, PhpArray value) {
        }

        @Specialization(guards = "type.acceptsNull()")
        static void nullValue(VirtualFrame frame, DeclaredType type, LocalAccessor acc, VarInfo info, int param,
                              String name, PhpNull value) {
        }

        @Specialization(guards = "type.acceptsAnyObject()")
        static void objectValue(VirtualFrame frame, DeclaredType type, LocalAccessor acc, VarInfo info, int param,
                                String name, PhpObject value) {
        }

        @Specialization(guards = {"type.hasFastClass()", "type.fastClassMatches(value)"})
        static void classValue(VirtualFrame frame, DeclaredType type, LocalAccessor acc, VarInfo info, int param,
                               String name, PhpObject value) {
            // The class name id is in the value class's ancestor set: nothing to coerce or store.
        }

        @Fallback
        static void fallback(VirtualFrame frame, DeclaredType type, LocalAccessor acc, VarInfo info, int param,
                             String name, Object value, @Bind Node node, @Bind BytecodeNode bytecode) {
            slow(frame, type, acc, info, param, name, value, node, bytecode);
        }

        private static void slow(VirtualFrame frame, DeclaredType type, LocalAccessor acc, VarInfo info, int param,
                                 String name, Object value, Node node, BytecodeNode bytecode) {
            Object result = DeclaredTypes.checkParameter(type, value, rootOf(node), node, param, name,
                    frame.getArguments());
            Object raw = acc.isCleared(bytecode, frame) ? null : acc.getObject(bytecode, frame);
            if (raw instanceof PhpReference ref) {
                ref.setValue(result);
            } else {
                store(frame, acc, info, bytecode, result);
            }
        }
    }

    /** Checks every element of a typed variadic parameter, preserving by-reference cells. */
    @Operation
    @ConstantOperand(type = DeclaredType.class)
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = int.class, name = "param")
    @ConstantOperand(type = String.class, name = "paramName")
    public static final class CheckVariadic {
        @Specialization
        static void check(VirtualFrame frame, DeclaredType type, LocalAccessor acc, VarInfo info, int param,
                          String name, @Bind BytecodeNode bytecode, @Bind Node node) {
            if (type.isMixed()) {
                // Accepts every element: no walk, no materialized frame (type is a constant).
                return;
            }
            Object raw = acc.isCleared(bytecode, frame) ? null : acc.getObject(bytecode, frame);
            if (!(raw instanceof PhpArray array)) {
                return;
            }
            PhpBytecodeRootNode root = (PhpBytecodeRootNode) node.getRootNode();
            PhpArray checked = Ops.checkVariadic(type, array, root, node, frame.materialize(), param, name);
            if (checked != array) {
                store(frame, acc, info, bytecode, checked);
            }
        }
    }

    /** Checks a returned value: weak coercion or TypeError, in the declaring file's mode. */
    @Operation
    @ConstantOperand(type = DeclaredType.class)
    @ConstantOperand(type = boolean.class, name = "implicit")
    public static final class CheckReturn {
        @Specialization(guards = {"!type.isVoid()", "!type.isNever()", "type.isMixed()"})
        static Object mixed(DeclaredType type, boolean implicit, Object value) {
            return value;
        }

        @Specialization(guards = {"!type.isVoid()", "!type.isNever()", "type.acceptsLong()"})
        static long longValue(DeclaredType type, boolean implicit, long value) {
            return value;
        }

        @Specialization(guards = {"!type.isVoid()", "!type.isNever()", "type.acceptsDouble()"})
        static double doubleValue(DeclaredType type, boolean implicit, double value) {
            return value;
        }

        @Specialization(guards = {"!type.isVoid()", "!type.isNever()", "type.acceptsString()"})
        static String stringValue(DeclaredType type, boolean implicit, String value) {
            return value;
        }

        @Specialization(guards = {"!type.isVoid()", "!type.isNever()", "type.acceptsBool()"})
        static boolean boolValue(DeclaredType type, boolean implicit, boolean value) {
            return value;
        }

        @Specialization(guards = {"!type.isVoid()", "!type.isNever()", "type.acceptsArray()"})
        static PhpArray arrayValue(DeclaredType type, boolean implicit, PhpArray value) {
            return value;
        }

        @Specialization(guards = {"!type.isVoid()", "!type.isNever()", "type.acceptsNull()"})
        static PhpNull nullValue(DeclaredType type, boolean implicit, PhpNull value) {
            return value;
        }

        @Specialization(guards = {"!type.isVoid()", "!type.isNever()", "type.acceptsAnyObject()"})
        static PhpObject objectValue(DeclaredType type, boolean implicit, PhpObject value) {
            return value;
        }

        @Specialization(guards = {"!type.isVoid()", "!type.isNever()", "type.hasFastClass()",
                "type.fastClassMatches(value)"})
        static PhpObject classValue(VirtualFrame frame, DeclaredType type, boolean implicit, PhpObject value) {
            return value;
        }

        @Fallback
        static Object slow(VirtualFrame frame, DeclaredType type, boolean implicit, Object value, @Bind Node node) {
            return slowReturn(frame, type, implicit, value, node);
        }

        private static Object slowReturn(VirtualFrame frame, DeclaredType type, boolean implicit, Object value,
                                         Node node) {
            PhpBytecodeRootNode root = rootOf(node);
            Object[] args = frame.getArguments();
            if (value instanceof PhpReference ref) {
                ref.setValue(DeclaredTypes.checkReturn(type, ref.getValue(), root, node, implicit, args));
                return ref;
            }
            return DeclaredTypes.checkReturn(type, value, root, node, implicit, args);
        }
    }

    /** The runtime class helpers the type operations need, derived from the running frame. */
    static final class DeclaredTypes {
        /**
         * A parameter check that a fast specialization could not settle (coercion, a trait's
         * self, an error): the class context is resolved here, out of compiled code.
         */
        @TruffleBoundary
        static Object checkParameter(DeclaredType type, Object value, PhpBytecodeRootNode root, Node node, int param,
                                     String name, Object[] args) {
            return type.checkParameter(value, root, node, param, name, selfClass(root, args), calledClass(root, args));
        }

        /** {@link #checkParameter} for a return value. */
        @TruffleBoundary
        static Object checkReturn(DeclaredType type, Object value, PhpBytecodeRootNode root, Node node,
                                  boolean implicit, Object[] args) {
            return type.checkReturn(value, root, node, implicit, selfClass(root, args), calledClass(root, args));
        }

        static PhpClass selfClass(PhpBytecodeRootNode root, com.oracle.truffle.api.frame.Frame frame) {
            return selfClass(root, frame.getArguments());
        }

        static PhpClass selfClass(PhpBytecodeRootNode root, Object[] args) {
            PhpFunctionRootNode fn = root.function();
            if (fn == null) {
                return null;
            }
            if (fn.traitSelfSlot() >= 0) {
                if (fn.isClosure()) {
                    if (args.length > 2 && args[2] instanceof PhpObject obj && obj.closure() != null
                            && obj.closure().traitSelf() != null) {
                        return obj.closure().traitSelf();
                    }
                } else if (args.length > 2 && args[2] instanceof PhpClass c) {
                    return c;
                }
            }
            int id = fn.declaringClassId();
            return id < 0 ? null : PhpContext.get(root).classById(id);
        }

        static PhpClass calledClass(PhpBytecodeRootNode root, VirtualFrame frame) {
            return calledClass(root, frame.getArguments());
        }

        static PhpClass calledClass(PhpBytecodeRootNode root, Object[] args) {
            if (root.function() != null && root.function().argOffset() > 0) {
                if (args.length > 1 && args[1] instanceof PhpClass c) {
                    return c;
                }
                if (args.length > 0 && args[0] instanceof PhpObject obj) {
                    return obj.phpClass();
                }
            }
            return null;
        }
    }

    private static PhpBytecodeRootNode rootOf(Node node) {
        return (PhpBytecodeRootNode) node.getRootNode();
    }

    /** A closure's captured variable {@code index}, from the closure object in argument 2. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = int.class, name = "index")
    @ConstantOperand(type = boolean.class, name = "byRef")
    public static final class BindCapture {
        @Specialization
        static void bind(VirtualFrame frame, LocalAccessor acc, VarInfo info, int index, boolean byRef,
                         @Bind BytecodeNode bytecode) {
            Object cap = ((PhpObject) frame.getArguments()[2]).closure().captures()[index];
            if (byRef) {
                acc.setObject(bytecode, frame, cap);
            } else {
                store(frame, acc, info, bytecode, PhpOps.copyOnAssign(cap));
            }
        }
    }

    @Operation
    public static final class ClosureTraitSelf {
        @Specialization
        static Object get(VirtualFrame frame) {
            PhpClass self = ((PhpObject) frame.getArguments()[2]).closure().traitSelf();
            return self == null ? PhpNull.INSTANCE : self;
        }
    }

    // ------------------------------------------------------------------ operators

    @Operation
    public static final class Copy {
        @Specialization
        static long copyLong(long v) {
            return v;
        }

        @Specialization
        static double copyDouble(double v) {
            return v;
        }

        @Fallback
        static Object copy(Object v) {
            return PhpOps.copyOnAssign(v);
        }
    }

    @Operation
    public static final class ToBool {
        @Specialization
        static boolean fromBoolean(boolean v) {
            return v;
        }

        @Specialization
        static boolean fromLong(long v) {
            return v != 0;
        }

        @Fallback
        static boolean generic(Object v) {
            return CondNode.toBool(v);
        }
    }

    @Operation
    public static final class IsNonNull {
        @Specialization
        static boolean test(Object v) {
            return v != null && v != PhpNull.INSTANCE;
        }
    }

    @Operation
    public static final class Not {
        @Specialization
        static boolean fromBoolean(boolean v) {
            return !v;
        }

        @Fallback
        static boolean generic(Object v) {
            return !CondNode.toBool(v);
        }
    }

    @Operation
    public static final class Add {
        @Specialization(rewriteOn = ArithmeticException.class)
        static long addLong(long a, long b) {
            return Math.addExact(a, b);
        }

        /**
         * After a site has overflowed once. Overflow is tested arithmetically: a caught
         * ArithmeticException compiles to a deoptimization, so a site that overflows on every
         * request (WordPress's ZB_IN_BYTES) would deoptimize and recompile forever.
         */
        @Specialization
        static Object addLongChecked(long a, long b) {
            long r = a + b;
            if (((a ^ r) & (b ^ r)) < 0) {
                return (double) a + (double) b;
            }
            return r;
        }

        @Specialization
        static double addDouble(double a, double b) {
            return a + b;
        }

        @Specialization
        static double addLongDouble(long a, double b) {
            return a + b;
        }

        @Specialization
        static double addDoubleLong(double a, long b) {
            return a + b;
        }

        @Fallback
        static Object generic(Object a, Object b) {
            return Ops.arith(PhpArith.Op.ADD, a, b);
        }
    }

    @Operation
    public static final class Sub {
        @Specialization(rewriteOn = ArithmeticException.class)
        static long subLong(long a, long b) {
            return Math.subtractExact(a, b);
        }

        /** After a site has overflowed once; see {@link Add#addLongChecked}. */
        @Specialization
        static Object subLongChecked(long a, long b) {
            long r = a - b;
            if (((a ^ b) & (a ^ r)) < 0) {
                return (double) a - (double) b;
            }
            return r;
        }

        @Specialization
        static double subDouble(double a, double b) {
            return a - b;
        }

        @Specialization
        static double subLongDouble(long a, double b) {
            return a - b;
        }

        @Specialization
        static double subDoubleLong(double a, long b) {
            return a - b;
        }

        @Fallback
        static Object generic(Object a, Object b) {
            return Ops.arith(PhpArith.Op.SUB, a, b);
        }
    }

    @Operation
    public static final class Mul {
        @Specialization(rewriteOn = ArithmeticException.class)
        static long mulLong(long a, long b) {
            return Math.multiplyExact(a, b);
        }

        /** After a site has overflowed once; see {@link Add#addLongChecked}. */
        @Specialization
        static Object mulLongChecked(long a, long b) {
            long r = a * b;
            if (Math.multiplyHigh(a, b) != (r >> 63)) {
                return (double) a * (double) b;
            }
            return r;
        }

        @Specialization
        static double mulDouble(double a, double b) {
            return a * b;
        }

        @Specialization
        static double mulLongDouble(long a, double b) {
            return a * b;
        }

        @Specialization
        static double mulDoubleLong(double a, long b) {
            return a * b;
        }

        @Fallback
        static Object generic(Object a, Object b) {
            return Ops.arith(PhpArith.Op.MUL, a, b);
        }
    }

    @Operation
    public static final class Div {
        @Specialization(guards = "isExact(a, b)")
        static long divExact(long a, long b) {
            return a / b;
        }

        @Specialization(guards = {"b != 0", "!isExact(a, b)"})
        static double divLong(long a, long b) {
            return (double) a / (double) b;
        }

        @Specialization(guards = "isNonZero(b)")
        static double divDouble(double a, double b) {
            return a / b;
        }

        static boolean isNonZero(double b) {
            return b != 0;
        }

        @Fallback
        static Object generic(Object a, Object b) {
            return Ops.arith(PhpArith.Op.DIV, a, b);
        }

        static boolean isExact(long a, long b) {
            return b != 0 && a % b == 0 && !(a == Long.MIN_VALUE && b == -1);
        }
    }

    @Operation
    public static final class Mod {
        @Specialization(guards = "b != 0")
        static long modLong(long a, long b) {
            return b == -1 ? 0 : a % b;
        }

        @Fallback
        static Object generic(Object a, Object b) {
            return Ops.arith(PhpArith.Op.MOD, a, b);
        }
    }

    /** Every other binary arithmetic operator (power, bitwise, shifts). */
    @Operation
    @ConstantOperand(type = PhpArith.Op.class, name = "op")
    public static final class Arith {
        @Specialization
        static Object apply(PhpArith.Op op, Object a, Object b) {
            return Ops.arith(op, a, b);
        }
    }

    @Operation
    public static final class Neg {
        @Specialization(rewriteOn = ArithmeticException.class)
        static long negLong(long a) {
            return Math.negateExact(a);
        }

        @Specialization
        static double negDouble(double a) {
            return -a;
        }

        @Fallback
        static Object generic(Object a) {
            return Ops.negate(a);
        }
    }

    @Operation
    @ConstantOperand(type = CompareNode.Kind.class, name = "kind")
    public static final class Compare {
        @Specialization
        static Object compareLong(CompareNode.Kind kind, long a, long b) {
            return result(kind, Long.compare(a, b));
        }

        @Specialization
        static Object compareDouble(CompareNode.Kind kind, double a, double b) {
            if (Double.isNaN(a) || Double.isNaN(b)) {
                return kind == CompareNode.Kind.SPACESHIP ? (Object) 1L : false;
            }
            return result(kind, a < b ? -1 : (a > b ? 1 : 0));
        }

        @Specialization
        static Object compareLongDouble(CompareNode.Kind kind, long a, double b) {
            return compareDouble(kind, a, b);
        }

        @Specialization
        static Object compareDoubleLong(CompareNode.Kind kind, double a, long b) {
            return compareDouble(kind, a, b);
        }

        @Fallback
        static Object generic(CompareNode.Kind kind, Object a, Object b) {
            if (a instanceof dev.phpruntime.runtime.PhpObject x
                    && b instanceof dev.phpruntime.runtime.PhpObject y
                    && x.nativeState() instanceof dev.phpruntime.runtime.EnumCaseState
                    && y.nativeState() instanceof dev.phpruntime.runtime.EnumCaseState) {
                // Enum cases are uncomparable except to themselves: relational operators are
                // false for two different cases, and true for the same case.
                boolean same = x == y;
                return switch (kind) {
                    case SPACESHIP -> same ? 0L : 1L;
                    case LE, GE -> same;
                    case LT, GT -> false;
                };
            }
            return result(kind, Ops.compare(a, b));
        }

        static Object result(CompareNode.Kind kind, int c) {
            return switch (kind) {
                case LT -> c < 0;
                case LE -> c <= 0;
                case GT -> c > 0;
                case GE -> c >= 0;
                case SPACESHIP -> (long) Integer.signum(c);
            };
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "negate")
    public static final class Equal {
        @Specialization
        static boolean eqLong(boolean negate, long a, long b) {
            return (a == b) != negate;
        }

        @Specialization
        static boolean eqDouble(boolean negate, double a, double b) {
            return (a == b) != negate;
        }

        @Specialization
        static boolean eqLongDouble(boolean negate, long a, double b) {
            return (a == b) != negate;
        }

        @Specialization
        static boolean eqDoubleLong(boolean negate, double a, long b) {
            return (a == b) != negate;
        }

        @Specialization
        static boolean eqBoolean(boolean negate, boolean a, boolean b) {
            return (a == b) != negate;
        }

        @Specialization
        static boolean eqString(boolean negate, String a, String b) {
            if (couldBeNumeric(a) || couldBeNumeric(b)) {
                // "1e1" == "10": two numeric strings compare as numbers.
                return Ops.looseEquals(a, b) != negate;
            }
            return a.equals(b) != negate;
        }

        /** Whether the text has only characters a numeric string may contain; conservative. */
        static boolean couldBeNumeric(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if ((c < '0' || c > '9') && c != '.' && c != 'e' && c != 'E' && c != '+'
                        && c != '-' && c != ' ' && c != '\t' && c != '\n' && c != '\r'
                        && c != '\f' && c != 0x0B) {
                    return false;
                }
            }
            return true;
        }

        @Fallback
        static boolean generic(boolean negate, Object a, Object b) {
            return Ops.looseEquals(a, b) != negate;
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "negate")
    public static final class Identical {
        @Specialization
        static boolean idLong(boolean negate, long a, long b) {
            return (a == b) != negate;
        }

        @Specialization
        static boolean idBoolean(boolean negate, boolean a, boolean b) {
            return (a == b) != negate;
        }

        @Specialization
        static boolean idString(boolean negate, String a, String b) {
            return a.equals(b) != negate;
        }

        @Fallback
        static boolean generic(boolean negate, Object a, Object b) {
            return Ops.strictEquals(a, b) != negate;
        }
    }

    @Operation
    public static final class Concat {
        @Specialization
        @TruffleBoundary
        static String strings(String a, String b) {
            // php shares the other operand when one side is empty; String.concat only skips an
            // empty right side.
            if (a.isEmpty()) {
                return b;
            }
            return a.concat(b);
        }

        @Fallback
        static String generic(Object a, Object b) {
            return Ops.concat(a, b);
        }
    }

    @Operation
    public static final class Interpolate {
        @Specialization
        static String join(@Variadic Object[] parts) {
            return Ops.join(parts);
        }
    }

    @Operation
    public static final class Echo {
        @Specialization
        static void echo(String s, @Bind Node node) {
            PhpContext.get(node).echo(s);
        }

        @Fallback
        static void generic(Object v, @Bind Node node) {
            PhpContext.get(node).echo(Ops.toStr(v));
        }
    }

    // ------------------------------------------------------------------ @

    /**
     * {@code @expr}: masks error_reporting to the fatal levels for the operand. Yields the
     * level to restore, which the emitter keeps in a local around a try/finally.
     */
    @Operation
    public static final class SilenceBegin {
        @Specialization
        static long begin(@Bind Node node) {
            PhpContext ctx = PhpContext.get(node);
            long previous = ctx.errorReporting();
            ctx.setErrorReporting(previous & PhpContext.SILENCE_MASK);
            return previous;
        }
    }

    /**
     * Ends {@code @expr}, restoring the level saved by {@link SilenceBegin}. php keeps a level
     * the operand set itself with error_reporting() or ini_set() when it enables anything
     * outside the fatal mask, and restores otherwise.
     */
    @Operation
    public static final class SilenceEnd {
        @Specialization
        static void end(long previous, @Bind Node node) {
            PhpContext ctx = PhpContext.get(node);
            if ((ctx.errorReporting() & ~PhpContext.SILENCE_MASK) == 0) {
                ctx.setErrorReporting(previous);
            }
        }
    }

    // ------------------------------------------------------------------ arrays

    @Operation
    public static final class NewArray {
        @Specialization
        static PhpArray create() {
            return new PhpArray();
        }
    }

    /** {@link #NewArray} with the literal's element count and layout known at compile time. */
    @Operation
    @ConstantOperand(type = int.class, name = "expected")
    @ConstantOperand(type = boolean.class, name = "stringKeys")
    public static final class NewArraySized {
        @Specialization
        static PhpArray create(int expected, boolean stringKeys) {
            return PhpArray.withCapacity(expected, stringKeys);
        }
    }

    /** Adds one element to an array under construction; a null key appends. */
    @Operation
    public static final class ArrayPut {
        @Specialization
        static PhpArray put(PhpArray array, Object key, Object value) {
            Ops.arrayPut(array, key, PhpOps.copyOnAssign(value));
            return array;
        }
    }

    @Operation
    public static final class ArrayAppend {
        @Specialization
        static PhpArray append(PhpArray array, Object value) {
            Ops.arrayAppend(array, PhpOps.copyOnAssign(value));
            return array;
        }
    }

    /** Adds one reference element to an array under construction. */
    @Operation
    public static final class ArrayPutRef {
        @Specialization
        static PhpArray putRef(PhpArray array, Object key, PhpReference cell,
                               @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                               @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location) {
            try {
                array.putRef(key, cell);
            } catch (PhpError e) {
                throw e.at(location);
            }
            return array;
        }
    }

    @Operation
    public static final class ArrayAppendRef {
        @Specialization
        static PhpArray appendRef(PhpArray array, PhpReference cell) {
            array.appendRef(cell);
            return array;
        }
    }

    /** An array literal's {@code ...$value}: unpacks an array or Traversable into the new array. */
    @Operation
    public static final class ArraySpread {
        @Specialization
        static PhpArray spread(PhpArray array, Object unpacked, @Bind PhpBytecodeRootNode root,
                               @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                               @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                               @Cached("create(root)") IteratorNode iterator) {
            return Ops.arraySpread(array, unpacked, iterator, location);
        }
    }

    @Operation
    public static final class Index {
        static PhpArray.KnownKey knownKey(String key) {
            return PhpArray.knownKey(key);
        }

        /**
         * A constant string key (the same instance on every execution): normalized and hashed
         * once for the site instead of on every access.
         */
        @Specialization(guards = "key == cachedKey", limit = "1")
        static Object fromArrayConstKey(PhpArray array, String key, @Bind BytecodeNode bytecode,
                                        @Bind("$bytecodeIndex") int bci, @Bind Node node,
                                        @Cached("key") String cachedKey,
                                        @Cached("knownKey(key)") PhpArray.KnownKey known) {
            Object v = array.get(known);
            return v == null ? undefinedKey(key, bytecode, bci, node) : v;
        }

        @Specialization
        static Object fromArray(PhpArray array, Object key, @Bind BytecodeNode bytecode,
                                @Bind("$bytecodeIndex") int bci, @Bind Node node) {
            Object v = Ops.arrayGet(array, key);
            return v == null ? undefinedKey(key, bytecode, bci, node) : v;
        }

        @Specialization
        static Object fromObject(PhpObject object, Object key, @Bind PhpBytecodeRootNode root,
                                 @Cached("create(root)") ArrayAccessNode access) {
            Object v = access.get(object, key);
            return v == null ? PhpNull.INSTANCE : v;
        }

        @Fallback
        static Object other(Object container, Object key, @Bind BytecodeNode bytecode,
                            @Bind("$bytecodeIndex") int bci, @Bind Node node) {
            Object v = Ops.index(container, key);
            return v == null ? undefinedKey(key, bytecode, bci, node) : v;
        }
    }

    /** The element if it exists and is not null, else PHP null, with no warning. */
    @Operation
    public static final class IndexIfSet {
        static PhpArray.KnownKey knownKey(String key) {
            return PhpArray.knownKey(key);
        }

        /** A constant string key: see {@link Index#fromArrayConstKey}. */
        @Specialization(guards = "key == cachedKey", limit = "1")
        static Object fromArrayConstKey(PhpArray array, String key, @Cached("key") String cachedKey,
                                        @Cached("knownKey(key)") PhpArray.KnownKey known) {
            Object v = array.get(known);
            return v == null ? PhpNull.INSTANCE : v;
        }

        @Specialization
        static Object fromArray(PhpArray array, Object key) {
            Object v = Ops.arrayGet(array, key);
            return v == null ? PhpNull.INSTANCE : v;
        }

        @Specialization
        static Object fromObject(PhpObject object, Object key, @Bind PhpBytecodeRootNode root,
                                 @Cached("create(root)") ArrayAccessNode access) {
            Object v = access.exists(object, key) ? access.get(object, key) : null;
            return v == null ? PhpNull.INSTANCE : v;
        }

        @Fallback
        static Object other(Object container, Object key) {
            if (container == null || container == PhpNull.INSTANCE) {
                return PhpNull.INSTANCE;
            }
            Object v = Ops.index(container, key);
            return v == null ? PhpNull.INSTANCE : v;
        }
    }

    @Operation
    public static final class IssetIndex {
        static PhpArray.KnownKey knownKey(String key) {
            return PhpArray.knownKey(key);
        }

        /** A constant string key: see {@link Index#fromArrayConstKey}. */
        @Specialization(guards = "key == cachedKey", limit = "1")
        static boolean fromArrayConstKey(PhpArray array, String key, @Cached("key") String cachedKey,
                                         @Cached("knownKey(key)") PhpArray.KnownKey known) {
            Object v = array.get(known);
            return v != null && v != PhpNull.INSTANCE;
        }

        @Specialization
        static boolean fromArray(PhpArray array, Object key) {
            Object v = Ops.arrayGet(array, key);
            return v != null && v != PhpNull.INSTANCE;
        }

        @Specialization
        static boolean fromObject(PhpObject object, Object key, @Bind PhpBytecodeRootNode root,
                                  @Cached("create(root)") ArrayAccessNode access) {
            return access.isSet(object, key);
        }

        @Fallback
        static boolean other(Object container, Object key) {
            if (container == null || container == PhpNull.INSTANCE) {
                return false;
            }
            Object v = Ops.index(container, key);
            return v != null && v != PhpNull.INSTANCE;
        }
    }

    @TruffleBoundary
    static Object undefinedKey(Object key, BytecodeNode bytecode, int bci, Node node) {
        Object k = PhpArray.normalizeKey(key);
        String shown = k instanceof String ? "\"" + k + "\"" : PhpOps.toStr(k);
        PhpContext.get(node).warning("Undefined array key " + shown, lineOf(bytecode, bci));
        return PhpNull.INSTANCE;
    }

    /** {@code $a[k1][k2]...} read: one warning, naming the last key, if any level is missing. */
    @Operation
    public static final class IndexPath {
        @Specialization
        static Object read(Object base, @Variadic Object[] keys, @Bind PhpBytecodeRootNode root,
                           @Cached("create(root)") ArrayAccessNode access, @Bind BytecodeNode bytecode,
                           @Bind("$bytecodeIndex") int bci, @Bind Node node) {
            Object v = Ops.path(base, keys, access, false);
            return v == null ? undefinedKey(keys[keys.length - 1], bytecode, bci, node) : v;
        }
    }

    @Operation
    public static final class IndexPathIfSet {
        @Specialization
        static Object read(Object base, @Variadic Object[] keys, @Bind PhpBytecodeRootNode root,
                           @Cached("create(root)") ArrayAccessNode access) {
            Object v = Ops.path(base, keys, access, true);
            return v == null ? PhpNull.INSTANCE : v;
        }
    }

    @Operation
    public static final class IssetPath {
        @Specialization
        static boolean isset(Object base, @Variadic Object[] keys, @Bind PhpBytecodeRootNode root,
                             @Cached("create(root)") ArrayAccessNode access) {
            Object value = base;
            for (int i = 0; i < keys.length; i++) {
                if (value instanceof PhpObject obj) {
                    if (i == keys.length - 1) {
                        return access.isSet(obj, keys[i]);
                    }
                    if (!access.exists(obj, keys[i])) {
                        return false;
                    }
                    value = access.get(obj, keys[i]);
                } else {
                    value = Ops.index(value, keys[i]);
                }
                if (value == null || value == PhpNull.INSTANCE) {
                    return false;
                }
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ foreach

    /**
     * Iteration state of one {@code foreach} by value. The stepping stays in Java (an array
     * cursor, an Iterator object, or a snapshot of an object's visible properties); the loop
     * and its body are bytecode.
     */
    public static final class Cursor {
        final PhpArray array;
        final PhpObject iterator;
        final List<Map.Entry<Object, Object>> entries;
        /** Snapshot size, kept off the interface call in the compiled loop. */
        final int entryCount;
        int pos = -1;
        boolean started;

        Cursor(PhpArray array, PhpObject iterator, List<Map.Entry<Object, Object>> entries) {
            this.array = array;
            this.iterator = iterator;
            this.entries = entries;
            this.entryCount = entries == null ? 0 : entries.size();
        }

        @TruffleBoundary
        Object entryKey() {
            return entries.get(pos).getKey();
        }

        @TruffleBoundary
        Object entryValue() {
            return entries.get(pos).getValue();
        }
    }

    /** Starts a foreach: a cursor, or PHP null (after the warning) when there is nothing to iterate. */
    @Operation
    public static final class ForeachStart {
        @Specialization
        static Object array(PhpArray array) {
            array.share();
            return new Cursor(array, null, null);
        }

        @Specialization
        static Object object(PhpObject object, @Bind PhpBytecodeRootNode root,
                             @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                             @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                             @Cached("create(root)") IteratorNode iterator, @Bind Node node) {
            if (object.phpClass().hasSplInterface(SplInterfaces.TRAVERSABLE)) {
                PhpObject it = iterator.resolve(object);
                iterator.rewind(it, location);
                return new Cursor(null, it, null);
            }
            PhpFunctionRootNode fn = root.function;
            return new Cursor(null, null, Ops.visibleEntries(PhpContext.get(node), object, fn == null ? null : fn.declaringClass()));
        }

        @Fallback
        static Object other(Object value, @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci, @Bind Node node) {
            Ops.warnForeach(node, value, lineOf(bytecode, bci));
            return PhpNull.INSTANCE;
        }
    }

    static IteratorNode newIterator(PhpBytecodeRootNode root) {
        return new IteratorNode(root.getLanguage(PhpLanguage.class));
    }

    @Operation
    public static final class ForeachNext {
        @Specialization
        static boolean next(Object state, @Bind PhpBytecodeRootNode root,
                            @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                            @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                            @Cached("create(root)") IteratorNode iterator, @Bind Node node) {
            if (!(state instanceof Cursor c)) {
                return false;
            }
            TruffleSafepoint.poll(node);
            if (c.array != null) {
                c.pos = c.pos < 0 && !c.started ? c.array.firstPos() : c.array.nextPos(c.pos);
                c.started = true;
                return c.pos >= 0;
            }
            if (c.iterator != null) {
                if (c.started) {
                    iterator.next(c.iterator, location);
                }
                c.started = true;
                return iterator.valid(c.iterator, location);
            }
            c.pos++;
            return c.pos < c.entryCount;
        }
    }

    @Operation
    public static final class ForeachKey {
        @Specialization
        static Object key(Cursor c, @Bind PhpBytecodeRootNode root,
                          @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                          @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                          @Cached("create(root)") IteratorNode iterator) {
            if (c.array != null) {
                return c.array.keyAt(c.pos);
            }
            if (c.iterator != null) {
                return iterator.key(c.iterator, location);
            }
            return c.entryKey();
        }
    }

    @Operation
    public static final class ForeachValue {
        @Specialization
        static Object value(Cursor c, @Bind PhpBytecodeRootNode root,
                            @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                            @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                            @Cached("create(root)") IteratorNode iterator,
                            @Cached("create()") com.oracle.truffle.api.profiles.BranchProfile refSeen) {
            Object raw;
            if (c.array != null) {
                raw = c.array.rawValueAt(c.pos);
            } else if (c.iterator != null) {
                raw = iterator.current(c.iterator, location);
            } else {
                raw = c.entryValue();
            }
            Object v = raw;
            if (raw instanceof PhpReference ref) {
                // Elements bound by reference are rare; until a loop sees one this is a deopt.
                refSeen.enter();
                v = ref.getValue();
            }
            return v == null ? PhpNull.INSTANCE : v;
        }
    }

    @Operation
    public static final class ForeachEnd {
        @Specialization
        static void end(Object state) {
            if (state instanceof Cursor c && c.array != null) {
                c.array.releaseShare();
            }
        }
    }

    /** State of a {@code foreach (... as $k => &$v)}: a live array, or object entries. */
    public static final class RefCursor {
        final PhpArray array;
        final PhpReference cell;
        final List<Object> keys;
        final List<Map.Entry<Object, PhpReference>> entries;
        /** A by-reference generator's object and state: {@code array} and friends are null. */
        final PhpObject generatorObject;
        final PhpGenerator generator;
        boolean generatorStarted;
        int pos = -1;
        Object key;
        PhpReference element;

        RefCursor(PhpArray array, PhpReference cell, List<Object> keys,
                  List<Map.Entry<Object, PhpReference>> entries) {
            this.array = array;
            this.cell = cell;
            this.keys = keys;
            this.entries = entries;
            this.generatorObject = null;
            this.generator = null;
        }

        RefCursor(PhpObject generatorObject, PhpGenerator generator) {
            this.array = null;
            this.cell = null;
            this.keys = null;
            this.entries = null;
            this.generatorObject = generatorObject;
            this.generator = generator;
        }

        @TruffleBoundary
        Object entryKey() {
            return entries.get(pos).getKey();
        }

        @TruffleBoundary
        PhpReference entryElement() {
            return entries.get(pos).getValue();
        }
    }

    // ------------------------------------------------------------------ calls

    /** The function a call site names in this request: a {@link PhpFunction} or a builtin. */
    @Operation
    @ConstantOperand(type = CallSite.class)
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class ResolveFunction {
        @Specialization
        static void resolve(VirtualFrame frame, CallSite site, LocalAccessor callee, @Bind BytecodeNode bytecode,
                            @Bind Node node,
                            @Cached(value = "builtinNamed(site)", neverDefault = false) Builtins.Def builtin,
                            @Cached("createIdentityProfile()") com.oracle.truffle.api.profiles.ValueProfile calleeIdentity) {
            // Functions are shared by every request that declares them, so a site usually sees
            // one callee. Profiling it makes the callee a constant for the argument operations.
            callee.setObject(bytecode, frame, calleeIdentity.profile(lookup(site, node, builtin)));
        }

        /** The builtin the site's global name denotes; fixed at startup, and no user function can take it. */
        static Builtins.Def builtinNamed(CallSite site) {
            return Builtins.lookup(site.name());
        }

        static Object lookup(CallSite site, Node node, Builtins.Def builtin) {
            if (site.nsFunctionId() >= 0) {
                PhpFunction nsFn = PhpContext.get(node).functionById(site.nsFunctionId());
                if (nsFn != null) {
                    return nsFn;
                }
            }
            if (builtin != null) {
                return builtin;
            }
            PhpFunction fn = PhpContext.get(node).functionById(site.functionId());
            if (fn != null) {
                return fn;
            }
            return Ops.builtinOrFail(site);
        }
    }

    /** A variable argument: its cell when the callee takes the parameter by reference, else its value. */
    @Operation
    @ConstantOperand(type = int.class, name = "param")
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class SendVar {
        @Specialization
        static Object send(VirtualFrame frame, int param, LocalAccessor acc, VarInfo info, LocalAccessor calleeLocal,
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci, @Bind Node node,
                           @Cached("createClassProfile()") com.oracle.truffle.api.profiles.ValueProfile calleeType,
                           @Cached("create()") com.oracle.truffle.api.profiles.BranchProfile buffer,
                           @Cached("create()") com.oracle.truffle.api.profiles.BranchProfile undefinedSeen,
                           @Cached("create()") RefProfile refs) {
            Object callee = calleeType.profile(calleeLocal.getObject(bytecode, frame));
            if (refs.takesRef(callee, param)) {
                if ("this".equals(info.name())) {
                    return new PhpReference(ReadVar.read(frame, acc, info, bytecode, bci, node));
                }
                return ObtainVarCell.obtain(frame, acc, info, bytecode);
            }
            Object v = current(frame, acc, bytecode, buffer);
            if (v == null) {
                undefinedSeen.enter();
                v = undefined(info, bytecode, bci, node);
            }
            return callee instanceof Builtins.Def ? v : PhpOps.copyOnAssign(v);
        }
    }

    /**
     * A non-variable argument. {@code kind}: 0 a plain expression, 1 a call result, 2 a
     * {@code new} expression; only 1 and 2 may go to a by-reference parameter.
     */
    @Operation
    @ConstantOperand(type = int.class, name = "param")
    @ConstantOperand(type = int.class, name = "kind")
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class SendValue {
        @Specialization
        static Object send(VirtualFrame frame, int param, int kind, LocalAccessor calleeLocal, Object value,
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci, @Bind Node node,
                           @Cached("createClassProfile()") com.oracle.truffle.api.profiles.ValueProfile calleeType,
                           @Cached("create()") RefProfile refs) {
            Object callee = calleeType.profile(calleeLocal.getObject(bytecode, frame));
            if (value instanceof PhpReference returned) {
                // Only a call result (kind 1) emitted for its reference: a by-reference return.
                return Ops.sendReturnedRef(callee, param, returned);
            }
            if (refs.takesRef(callee, param)) {
                return Ops.sendValueToRef(callee, param, kind, value, node, bytecode, bci);
            }
            return callee instanceof Builtins.Def ? value : PhpOps.copyOnAssign(value);
        }
    }

    /** The parameters a callee declares (for by-reference checks), or null if unknown or a builtin. */
    static PhpFunctionRootNode signature(Object callee) {
        if (callee instanceof PhpFunction fn) {
            return PhpFunctionRootNode.of(fn.callTarget().getRootNode());
        }
        if (callee instanceof MethodCallee m && m.entry() != null) {
            return PhpFunctionRootNode.of(m.sigTarget().getRootNode());
        }
        if (callee instanceof ValueCallee v) {
            return v.sig();
        }
        return null;
    }

    /** A method call resolved on its receiver; {@code entry} is null for a {@code __call} fallback. */
    public record MethodCallee(PhpClass.MethodEntry entry, Object thisArg, PhpClass cls, RootCallTarget sigTarget,
                               Object obj, String name, RootCallTarget magic) {
    }

    /**
     * A callable value resolved for {@code $f(...)}: the runtime callable and the call target's
     * metadata (for by-reference parameters). The call target is null for a builtin.
     */
    public record ValueCallee(dev.phpruntime.runtime.PhpCallable callable, PhpFunctionRootNode sig) {
    }

    /**
     * Whether the resolved callee takes parameter {@code param} by reference. Kept small: it is
     * compiled into every argument of every call. Reads the mask precomputed on the callee's
     * root; only an unusual root (a closure's invoke, SPL methods) goes through a boundary.
     */
    static boolean takesRef(Object callee, int param) {
        if (callee instanceof PhpFunction fn) {
            return PhpFunctionRootNode.refBit(refMaskOf(fn.callTarget()), param);
        }
        if (callee instanceof MethodCallee m) {
            if (m.entry() == null) {
                return false;
            }
            long mask = refMaskOf(m.sigTarget());
            if (mask == 0) {
                mask = m.entry().refMask();
            }
            return PhpFunctionRootNode.refBit(mask, param);
        }
        if (callee instanceof Builtins.Def def) {
            return def.isRef(param);
        }
        if (callee instanceof ValueCallee v) {
            if (v.callable().kind() == dev.phpruntime.runtime.PhpCallable.Kind.BUILTIN) {
                return v.callable().builtin().isRef(param);
            }
            if (v.sig() != null) {
                return PhpFunctionRootNode.refBit(v.sig().refMask(), param);
            }
            dev.phpruntime.runtime.PhpClass.MethodEntry entry = v.callable().methodEntry();
            return entry != null && PhpFunctionRootNode.refBit(entry.refMask(), param);
        }
        return false;
    }

    /**
     * {@link #takesRef} with an identity profile of what decides the answer: the callee's
     * signature call target, builtin or signature. An argument operation has a fixed parameter
     * index, so while it sees one such object the answer is a constant in compiled code and the
     * by-reference or by-value branch that cannot happen drops out. A method callee is a fresh
     * record per call, so without the profile the mask is loaded through it on every argument.
     */
    static final class RefProfile extends Node {
        private static final Object UNSET = new Object();
        private static final Object GENERIC = new Object();
        /** A callee with no by-reference parameters at all ({@code __call}, unknown). */
        private static final Object NONE = new Object();

        /** The decision for one key, published as one object so key and answer never mix. */
        private record Seen(Object key, boolean ref) {
        }

        @CompilerDirectives.CompilationFinal private Object state = UNSET;

        static RefProfile create() {
            return new RefProfile();
        }

        boolean takesRef(Object callee, int param) {
            Object st = state;
            if (st instanceof Seen seen) {
                if (refKey(callee) == seen.key()) {
                    return seen.ref();
                }
                CompilerDirectives.transferToInterpreterAndInvalidate();
                state = GENERIC;
            } else if (st == UNSET) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                boolean ref = PhpBytecodeRootNode.takesRef(callee, param);
                state = new Seen(refKey(callee), ref);
                return ref;
            }
            return PhpBytecodeRootNode.takesRef(callee, param);
        }

        /** The object that decides {@link PhpBytecodeRootNode#takesRef} for every parameter. */
        private static Object refKey(Object callee) {
            if (callee instanceof PhpFunction fn) {
                return fn.callTarget();
            }
            if (callee instanceof MethodCallee m) {
                return m.entry() != null ? m.sigTarget() : NONE;
            }
            if (callee instanceof Builtins.Def def) {
                return def;
            }
            if (callee instanceof ValueCallee v) {
                if (v.callable().kind() == dev.phpruntime.runtime.PhpCallable.Kind.BUILTIN) {
                    return v.callable().builtin();
                }
                if (v.sig() != null) {
                    return v.sig();
                }
                // A Java method: its call target is unique to the method, as its mask is.
                return v.callable().callTarget() != null ? v.callable().callTarget() : NONE;
            }
            return NONE;
        }
    }

    static long refMaskOf(RootCallTarget target) {
        com.oracle.truffle.api.nodes.RootNode root = target.getRootNode();
        if (root instanceof PhpBytecodeRootNode b) {
            return b.refMask;
        }
        if (root instanceof GeneratorEntryRootNode g) {
            return g.refMask();
        }
        return Ops.refMaskSlow(root);
    }

    /** The builtin a callee denotes, also through a callable value, else null. */
    static Builtins.Def builtinCallee(Object callee) {
        if (callee instanceof Builtins.Def def) {
            return def;
        }
        if (callee instanceof ValueCallee v
                && v.callable().kind() == dev.phpruntime.runtime.PhpCallable.Kind.BUILTIN) {
            return v.callable().builtin();
        }
        return null;
    }

    /** A method call site: the static name (null for {@code $obj->$name()}) and its method id. */
    public record MethodSite(String name, int methodId) {
    }

    @Operation
    @ConstantOperand(type = MethodSite.class)
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class ResolveMethod {
        /**
         * A literal method name on an object of a class this site has seen: classes cannot
         * change once declared and are shared by every request, so the entry is a constant and
         * the call that follows can use it directly. Closure objects keep the general path.
         */
        @Specialization(guards = {"site.methodId() >= 0", "obj.phpClass() == cachedClass", "cachedEntry != null"},
                limit = "3")
        static void cached(VirtualFrame frame, MethodSite site, LocalAccessor callee, PhpObject obj, Object nameValue,
                           @Bind BytecodeNode bytecode,
                           @Cached("obj.phpClass()") PhpClass cachedClass,
                           @Cached("cacheableEntry(cachedClass, site)") PhpClass.MethodEntry cachedEntry) {
            Object thisArg = cachedEntry.isStatic() ? PhpNull.INSTANCE : obj;
            callee.setObject(bytecode, frame,
                    new MethodCallee(cachedEntry, thisArg, cachedClass, cachedEntry.callTarget(), obj, site.name(), null));
        }

        /** The entry to cache for a class, or null when the site needs the general path. */
        static PhpClass.MethodEntry cacheableEntry(PhpClass cls, MethodSite site) {
            PhpClass.MethodEntry entry = cls.methodById(site.methodId());
            if (entry == null || entry.callTarget() == null
                    || entry.callTarget().getRootNode() instanceof dev.phpruntime.nodes.call.ClosureInvokeRootNode) {
                return null;
            }
            return entry;
        }

        @Specialization(replaces = "cached")
        static void resolve(VirtualFrame frame, MethodSite site, LocalAccessor callee, Object target, Object nameValue,
                            @Bind BytecodeNode bytecode,
                            @Cached("createIdentityProfile()") com.oracle.truffle.api.profiles.ValueProfile sigIdentity) {
            String name = nameValue instanceof String s ? s : Ops.toStr(nameValue);
            if (!(target instanceof PhpObject obj)) {
                CompilerDirectives.transferToInterpreter();
                throw Ops.error("Call to a member function " + name + "() on " + PhpOps.typeName(target));
            }
            PhpClass cls = obj.phpClass();
            PhpClass.MethodEntry entry = site.methodId() >= 0 ? cls.methodById(site.methodId()) : cls.lookupMethod(name);
            if (entry == null) {
                PhpClass.MethodEntry magic = cls.callMethod();
                if (magic != null && magic.callTarget() != null) {
                    callee.setObject(bytecode, frame, new MethodCallee(null, obj, cls, null, obj, name, magic.callTarget()));
                    return;
                }
                CompilerDirectives.transferToInterpreter();
                throw Ops.error("Call to undefined method " + cls.name() + "::" + name + "()");
            }
            if (entry.callTarget() == null) {
                CompilerDirectives.transferToInterpreter();
                throw Ops.error("Cannot call abstract method " + entry.declaringClass().name() + "::" + entry.name() + "()");
            }
            Object thisArg = entry.isStatic() ? PhpNull.INSTANCE : obj;
            // The class differs per request but the method body does not: profiling the target
            // lets the by-reference checks of the arguments fold.
            RootCallTarget sig = (RootCallTarget) sigIdentity.profile(entry.callTarget());
            if (obj.closure() != null && obj.closure().callTarget() != null
                    && sig.getRootNode() instanceof dev.phpruntime.nodes.call.ClosureInvokeRootNode) {
                sig = obj.closure().callTarget();
            }
            callee.setObject(bytecode, frame, new MethodCallee(entry, thisArg, cls, sig, obj, name, null));
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "keepRef")
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class DoMethodCall {
        @Specialization
        static Object call(VirtualFrame frame, boolean keepRef, LocalAccessor calleeLocal, @Variadic Object[] args,
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create()") DispatchCallNode dispatch,
                           @Cached("create()") com.oracle.truffle.api.profiles.BranchProfile magicSeen) {
            MethodCallee m = (MethodCallee) calleeLocal.getObject(bytecode, frame);
            if (m.entry() == null) {
                // __call: rare at most sites, so its dispatch stays out of compiled code until
                // a site takes it.
                magicSeen.enter();
                return Ops.ret(keepRef, dispatch.call(m.magic(),
                        new Object[]{m.obj(), m.cls(), m.name(), Ops.packArgs(args)}, location, m.cls()));
            }
            Object[] callArgs = new Object[2 + args.length];
            callArgs[0] = m.thisArg();
            callArgs[1] = m.cls();
            System.arraycopy(args, 0, callArgs, 2, args.length);
            return Ops.ret(keepRef, dispatch.callMethod(m.entry(), callArgs, location, m.cls()));
        }
    }

    // ------------------------------------------------------------------ places

    public static final int P_READ = 0;
    public static final int P_READ_IF_SET = 1;
    public static final int P_ISSET = 2;
    public static final int P_WRITE = 3;
    public static final int P_COMPOUND = 4;
    public static final int P_INCDEC = 5;
    public static final int P_APPEND = 6;
    public static final int P_CELL = 7;
    public static final int P_UNSET = 8;
    /** The value without the undefined warning, or {@link #UNDEFINED} (compact, extract). */
    public static final int P_RAW = 9;
    /** Binds the place to the reference cell given as the value (destructuring, foreach by ref). */
    public static final int P_BIND = 10;
    /** Prepares the evaluated keys of a place without reading or writing it ({@code ??=}). */
    public static final int P_KEYS = 11;

    /**
     * A non-local lvalue (an index path or a property). {@code template} is a copy of its AST
     * node without expressions; each instruction caches its own copy and calls its read, write,
     * cell and unset methods with the keys the bytecode evaluated, so the semantics are the AST's.
     */
    public static final class PlaceSpec {
        private final dev.phpruntime.nodes.var.LValueNode template;
        final int arrayKeys;
        /** Base operands before the array keys: 2 object/name, 3 static class/name/caller. */
        final int baseCount;

        public PlaceSpec(dev.phpruntime.nodes.var.LValueNode template, int arrayKeys) {
            this(template, arrayKeys, 2);
        }

        public PlaceSpec(dev.phpruntime.nodes.var.LValueNode template, int arrayKeys, int baseCount) {
            this.template = template;
            this.arrayKeys = arrayKeys;
            this.baseCount = baseCount;
        }

        public dev.phpruntime.nodes.var.LValueNode newEngine() {
            return (dev.phpruntime.nodes.var.LValueNode) template.deepCopy();
        }

        dev.phpruntime.nodes.var.LValueNode template() {
            return template;
        }

        @Override
        public String toString() {
            return template.describe();
        }
    }

    /** A place on an index path rooted in a local: operands are the keys, then the value if any. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = int.class, name = "mode")
    @ConstantOperand(type = int.class, name = "arg")
    public static final class VarPlace {
        @Specialization
        static Object run(VirtualFrame frame, LocalAccessor acc, VarInfo info, PlaceSpec spec, int mode, int arg,
                          @Variadic Object[] ops, @Bind BytecodeNode bytecode,
                          @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine,
                          @Cached("create()") com.oracle.truffle.api.profiles.BranchProfile buffer) {
            Object[] holder = {HOLDER_EMPTY};
            Object[] keys = Places.varKeys(current(frame, acc, bytecode, buffer), holder, ops, spec.arrayKeys);
            if (mode == P_KEYS) {
                return new PreparedPlace(keys, holder);
            }
            Object result = Places.run(frame, engine, keys, mode, arg, Places.value(ops, spec.arrayKeys));
            if (holder[0] != HOLDER_EMPTY) {
                store(frame, acc, info, bytecode, holder[0]);
            }
            return result;
        }
    }

    /** A property, or an index path on one: operands are the object, the name, the keys, the value. */
    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = int.class, name = "mode")
    @ConstantOperand(type = int.class, name = "arg")
    public static final class ObjPlace {
        @Specialization
        static Object run(VirtualFrame frame, PlaceSpec spec, int mode, int arg, @Variadic Object[] ops,
                          @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            Object[] keys = Places.objKeys(ops, spec.arrayKeys, spec.baseCount);
            if (mode == P_KEYS) {
                return new PreparedPlace(keys, null);
            }
            return Places.run(frame, engine, keys, mode, arg, Places.value(ops, spec.baseCount + spec.arrayKeys));
        }
    }

    /** A global variable, or an index path on one: operands are the keys, then the value. */
    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = int.class, name = "mode")
    @ConstantOperand(type = int.class, name = "arg")
    public static final class GlobalPlace {
        @Specialization
        static Object run(VirtualFrame frame, PlaceSpec spec, int mode, int arg, @Variadic Object[] ops,
                          @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            Object[] keys = Places.globalKeys(ops, spec.arrayKeys);
            if (mode == P_KEYS) {
                return new PreparedPlace(keys, null);
            }
            return Places.run(frame, engine, keys, mode, arg, Places.value(ops, spec.arrayKeys));
        }
    }

    /** A static property, or an index path on one: operands are class, name, caller, keys, value. */
    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = int.class, name = "mode")
    @ConstantOperand(type = int.class, name = "arg")
    public static final class StaticPlace {
        @Specialization
        static Object run(VirtualFrame frame, PlaceSpec spec, int mode, int arg, @Variadic Object[] ops,
                          @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            Object[] keys = Places.staticKeys(ops, spec.arrayKeys, spec.baseCount);
            if (mode == P_KEYS) {
                return new PreparedPlace(keys, null);
            }
            return Places.run(frame, engine, keys, mode, arg, Places.value(ops, spec.baseCount + spec.arrayKeys));
        }
    }

    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = int.class, name = "param")
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class SendStaticPlace {
        @Specialization
        static Object send(VirtualFrame frame, PlaceSpec spec, int param, LocalAccessor calleeLocal,
                           @Variadic Object[] ops, @Bind BytecodeNode bytecode,
                           @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine,
                           @Cached("createClassProfile()") com.oracle.truffle.api.profiles.ValueProfile calleeType,
                           @Cached("create()") RefProfile refs) {
            Object callee = calleeType.profile(calleeLocal.getObject(bytecode, frame));
            int mode = refs.takesRef(callee, param) ? P_CELL : P_READ;
            Object[] keys = Places.staticKeys(ops, spec.arrayKeys, spec.baseCount);
            Object result = Places.runSend(frame, engine, keys, mode);
            return Places.sendValue(callee, mode, result);
        }
    }

    /** A {@code $GLOBALS[...]} element, or an index path on one. */
    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = int.class, name = "mode")
    @ConstantOperand(type = int.class, name = "arg")
    public static final class GlobalsPlace {
        @Specialization
        static Object run(VirtualFrame frame, PlaceSpec spec, int mode, int arg, @Variadic Object[] ops,
                          @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            Object[] keys = Places.globalsElementKeys(ops, spec.baseCount, spec.arrayKeys);
            if (mode == P_KEYS) {
                return new PreparedPlace(keys, null);
            }
            return Places.run(frame, engine, keys, mode, arg, Places.value(ops, spec.baseCount + spec.arrayKeys));
        }
    }

    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = int.class, name = "param")
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class SendGlobalsPlace {
        @Specialization
        static Object send(VirtualFrame frame, PlaceSpec spec, int param, LocalAccessor calleeLocal,
                           @Variadic Object[] ops, @Bind BytecodeNode bytecode,
                           @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine,
                           @Cached("createClassProfile()") com.oracle.truffle.api.profiles.ValueProfile calleeType,
                           @Cached("create()") RefProfile refs) {
            Object callee = calleeType.profile(calleeLocal.getObject(bytecode, frame));
            int mode = refs.takesRef(callee, param) ? P_CELL : P_READ;
            Object[] keys = Places.globalsElementKeys(ops, spec.baseCount, spec.arrayKeys);
            Object result = Places.runSend(frame, engine, keys, mode);
            return Places.sendValue(callee, mode, result);
        }
    }

    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = int.class, name = "param")
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class SendGlobalPlace {
        @Specialization
        static Object send(VirtualFrame frame, PlaceSpec spec, int param, LocalAccessor calleeLocal,
                           @Variadic Object[] ops, @Bind BytecodeNode bytecode,
                           @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine,
                           @Cached("createClassProfile()") com.oracle.truffle.api.profiles.ValueProfile calleeType,
                           @Cached("create()") RefProfile refs) {
            Object callee = calleeType.profile(calleeLocal.getObject(bytecode, frame));
            int mode = refs.takesRef(callee, param) ? P_CELL : P_READ;
            Object result = Places.runSend(frame, engine, Places.globalKeys(ops, spec.arrayKeys), mode);
            return Places.sendValue(callee, mode, result);
        }
    }

    // ------------------------------------------------------------------ top-level code

    /** Binds a name in a symbol table to a cell ({@code static} in an included file). */
    @Operation
    @ConstantOperand(type = String.class)
    public static final class TableBindName {
        @Specialization
        static void bind(String name, Object table, Object cell) {
            Ops.tableBind((dev.phpruntime.runtime.PhpSymbolTable) table, name, (PhpReference) cell);
        }
    }

    /** {@code $this}, the called class or the lexical class of the scope an included file runs in. */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class TableScopeValue {
        @Specialization
        static Object value(int which, Object table) {
            dev.phpruntime.runtime.PhpSymbolTable t = (dev.phpruntime.runtime.PhpSymbolTable) table;
            return switch (which) {
                case 0 -> t.thisValue;
                case 1 -> t.calledClass;
                default -> t.lexicalClass;
            };
        }
    }

    /** Whether the user function with this id is declared (ScopeFunctionFallbackNode). */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class FunctionDefined {
        @Specialization
        static boolean defined(int functionId, @Bind Node node) {
            return PhpContext.get(node).functionById(functionId) != null;
        }
    }

    /**
     * {@code eval($code)}: the code runs in the global scope, or in the caller's symbol table
     * when the function has one (EvalNode).
     */
    @Operation
    public static final class Eval {
        @Specialization
        static Object eval(Object code, Object table, @Bind Node node, @Bind BytecodeNode bytecode,
                           @Bind("$bytecodeIndex") int bci,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached com.oracle.truffle.api.nodes.IndirectCallNode call) {
            boolean local = table != PhpNull.INSTANCE;
            com.oracle.truffle.api.CallTarget target = dev.phpruntime.nodes.expr.EvalNode.prepare(
                    PhpContext.get(node), PhpOps.toStr(code), local, Ops.sourcePathOf(bytecode, bci),
                    lineOf(bytecode, bci), null);
            PhpContext ctx = PhpContext.get(node);
            int depth = ctx.pushTraceFrame(location);
            try {
                return call.call(target, local ? new Object[]{dev.phpruntime.nodes.expr.EvalNode.EVAL_MODE, table}
                        : new Object[]{dev.phpruntime.nodes.expr.EvalNode.EVAL_MODE});
            } finally {
                ctx.popTraceFrame(depth);
            }
        }
    }

    /** Declares the file's functions and hoists its classes; the hoisted flags for DeclareClass. */
    @Operation
    public static final class MainPrologue {
        @Specialization
        static Object prologue(@Bind PhpBytecodeRootNode root, @Bind Node node) {
            boolean[] hoisted = root.main.prologue(PhpContext.get(node));
            return hoisted == null ? PhpNull.INSTANCE : hoisted;
        }
    }

    @Operation
    public static final class MainResult {
        @Specialization
        static Object result(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            return args.length > 0 && args[0] == dev.phpruntime.nodes.expr.EvalNode.EVAL_MODE ? PhpNull.INSTANCE : 1L;
        }
    }

    @Operation
    @ConstantOperand(type = PhpFunction.class)
    public static final class DeclareFunction {
        @Specialization
        static void declare(PhpFunction fn, @Bind Node node) {
            Ops.declareFunction(PhpContext.get(node), fn);
        }
    }

    /** Declares a class unless it was hoisted at file start ({@code index} into the hoisted flags). */
    @Operation
    @ConstantOperand(type = dev.phpruntime.runtime.PhpClassTemplate.class)
    @ConstantOperand(type = int.class, name = "index")
    public static final class DeclareClass {
        @Specialization
        static void declare(dev.phpruntime.runtime.PhpClassTemplate template, int index, Object hoisted, @Bind Node node) {
            if (index >= 0 && hoisted instanceof boolean[] flags && flags[index]) {
                return;
            }
            Ops.declareClass(PhpContext.get(node), template);
        }
    }

    @Operation
    @ConstantOperand(type = String.class, name = "constName")
    @ConstantOperand(type = int.class, name = "line")
    public static final class DefineConst {
        @Specialization
        static void define(String constName, int line, Object value, @Bind Node node) {
            Ops.defineConstant(PhpContext.get(node), constName, PhpOps.copyOnAssign(value), line);
        }
    }

    public record IncludeSpec(dev.phpruntime.nodes.expr.IncludeNode template) {
        public dev.phpruntime.nodes.expr.IncludeNode newEngine() {
            return template.headless();
        }

        @Override
        public String toString() {
            return template.kind().toString();
        }
    }

    /** include/require: the cached copy of the IncludeNode resolves and runs the file. */
    @Operation
    @ConstantOperand(type = IncludeSpec.class)
    public static final class Include {
        @Specialization
        static Object include(IncludeSpec spec, Object path,
                              @Cached("spec.newEngine()") dev.phpruntime.nodes.expr.IncludeNode engine) {
            return engine.executePath(path instanceof String s ? s : Ops.toStr(path));
        }
    }

    /** An argument that is an index path on a local: its cell for a by-reference parameter, else its value. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = int.class, name = "param")
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class SendVarPlace {
        @Specialization
        static Object send(VirtualFrame frame, LocalAccessor acc, VarInfo info, PlaceSpec spec, int param,
                           LocalAccessor calleeLocal, @Variadic Object[] ops, @Bind BytecodeNode bytecode,
                           @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine,
                           @Cached("createClassProfile()") com.oracle.truffle.api.profiles.ValueProfile calleeType,
                           @Cached("create()") com.oracle.truffle.api.profiles.BranchProfile buffer,
                           @Cached("create()") RefProfile refs) {
            Object callee = calleeType.profile(calleeLocal.getObject(bytecode, frame));
            int mode = refs.takesRef(callee, param) ? P_CELL : P_READ;
            Object[] holder = {HOLDER_EMPTY};
            Object[] keys = Places.varKeys(current(frame, acc, bytecode, buffer), holder, ops, spec.arrayKeys);
            Object result = Places.runSend(frame, engine, keys, mode);
            if (holder[0] != HOLDER_EMPTY) {
                store(frame, acc, info, bytecode, holder[0]);
            }
            return Places.sendValue(callee, mode, result);
        }
    }

    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = int.class, name = "param")
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class SendObjPlace {
        @Specialization
        static Object send(VirtualFrame frame, PlaceSpec spec, int param, LocalAccessor calleeLocal,
                           @Variadic Object[] ops, @Bind BytecodeNode bytecode,
                           @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine,
                           @Cached("createClassProfile()") com.oracle.truffle.api.profiles.ValueProfile calleeType,
                           @Cached("create()") RefProfile refs) {
            Object callee = calleeType.profile(calleeLocal.getObject(bytecode, frame));
            int mode = refs.takesRef(callee, param) ? P_CELL : P_READ;
            Object result = Places.runSend(frame, engine, Places.objKeys(ops, spec.arrayKeys, spec.baseCount), mode);
            return Places.sendValue(callee, mode, result);
        }
    }

    static final Object HOLDER_EMPTY = new Object();

    /** The mode logic of the AST's assignment, compound, increment and append nodes, on an engine. */
    static final class Places {
        static Object[] varKeys(Object root, Object[] holder, Object[] ops, int n) {
            Object[] arrayKeys = new Object[n];
            System.arraycopy(ops, 0, arrayKeys, 0, n);
            return dev.phpruntime.nodes.var.IndexLValueNode.pack(
                    dev.phpruntime.nodes.var.HeldValueBase.keys(root, holder), arrayKeys);
        }

        static Object[] globalKeys(Object[] ops, int n) {
            if (n == 0) {
                return dev.phpruntime.nodes.var.LValueNode.NO_KEYS;
            }
            Object[] arrayKeys = new Object[n];
            System.arraycopy(ops, 0, arrayKeys, 0, n);
            return arrayKeys;
        }

        /** Base keys (class, name, caller?) then array keys, packed for an index path. */
        static Object[] staticKeys(Object[] ops, int n, int baseCount) {
            Object[] base = new Object[baseCount];
            System.arraycopy(ops, 0, base, 0, baseCount);
            if (n == 0) {
                return base;
            }
            Object[] arrayKeys = new Object[n];
            System.arraycopy(ops, baseCount, arrayKeys, 0, n);
            return dev.phpruntime.nodes.var.IndexLValueNode.pack(base, arrayKeys);
        }

        /** {@code $GLOBALS} element base keys (0 for a constant name, 1 for an expression), then array keys. */
        static Object[] globalsElementKeys(Object[] ops, int baseCount, int n) {
            Object[] base = baseCount == 0 ? dev.phpruntime.nodes.var.LValueNode.NO_KEYS : new Object[baseCount];
            if (baseCount != 0) {
                System.arraycopy(ops, 0, base, 0, baseCount);
            }
            if (n == 0) {
                return base;
            }
            Object[] arrayKeys = new Object[n];
            System.arraycopy(ops, baseCount, arrayKeys, 0, n);
            return dev.phpruntime.nodes.var.IndexLValueNode.pack(base, arrayKeys);
        }

        static Object[] objKeys(Object[] ops, int n, int baseCount) {
            Object[] base = new Object[baseCount];
            System.arraycopy(ops, 0, base, 0, baseCount);
            if (n == 0) {
                return base;
            }
            Object[] arrayKeys = new Object[n];
            System.arraycopy(ops, baseCount, arrayKeys, 0, n);
            return dev.phpruntime.nodes.var.IndexLValueNode.pack(base, arrayKeys);
        }

        static Object value(Object[] ops, int index) {
            return index < ops.length ? ops[index] : null;
        }

        /**
         * {@link #run} for an argument: {@code mode} is only known at run time (the callee decides
         * by-reference), so each branch passes a literal mode. With the variable mode every place
         * mode (write, append, ++, compound, ...) was compiled into each argument.
         */
        static Object runSend(VirtualFrame frame, dev.phpruntime.nodes.var.LValueNode engine, Object[] keys, int mode) {
            if (mode == P_CELL) {
                return run(frame, engine, keys, P_CELL, 0, null);
            }
            return run(frame, engine, keys, P_READ, 0, null);
        }

        static Object sendValue(Object callee, int mode, Object result) {
            return mode == P_READ && !(callee instanceof Builtins.Def) ? PhpOps.copyOnAssign(result) : result;
        }

        static Object run(VirtualFrame frame, dev.phpruntime.nodes.var.LValueNode engine, Object[] keys, int mode,
                          int arg, Object value) {
            switch (mode) {
                case P_READ:
                    if (engine instanceof dev.phpruntime.nodes.var.IndexLValueNode il) {
                        return il.readOrWarn(frame, keys);
                    }
                    if (engine instanceof dev.phpruntime.nodes.var.PropertyLValueNode p) {
                        return p.executeOn(frame, keys[0], (String) keys[1], keys);
                    }
                    if (engine instanceof dev.phpruntime.nodes.var.StaticPropertyLValueNode sp) {
                        return sp.executeWithKeys(frame, keys);
                    }
                    if (engine instanceof dev.phpruntime.nodes.var.GlobalsElementLValueNode ge) {
                        return ge.executeWithKeys(frame, keys);
                    }
                    return engine.readValue(frame, keys);
                case P_READ_IF_SET: {
                    Object v = engine.readIfSet(frame, keys);
                    return v == null ? PhpNull.INSTANCE : v;
                }
                case P_ISSET:
                    return engine.isSet(frame, keys);
                case P_WRITE:
                    engine.write(frame, keys, PhpOps.copyOnAssign(value));
                    return value;
                case P_COMPOUND:
                    return compound(frame, engine, keys, PhpArith.Op.values()[arg], value);
                case P_INCDEC:
                    return incDec(frame, engine, keys, (arg & 1) != 0, (arg & 2) != 0);
                case P_APPEND:
                    return append(frame, engine, keys, value, arg != 0);
                case P_CELL:
                    return engine.obtainCell(frame, keys);
                case P_UNSET:
                    engine.unset(frame, keys);
                    return PhpNull.INSTANCE;
                case P_BIND:
                    engine.bindCell(frame, keys, (PhpReference) value);
                    return value;
                case P_RAW: {
                    Object v = engine.read(frame, keys);
                    return v == null ? UNDEFINED : v;
                }
                default:
                    throw CompilerDirectives.shouldNotReachHere();
            }
        }

        /** Writes or binds one destructured element on a place engine. */
        static void destructureStep(VirtualFrame frame, dev.phpruntime.nodes.var.LValueNode engine,
                                    Object[] keys, DestructureItem item) {
            if (item.cell() != null) {
                run(frame, engine, keys, P_BIND, 0, item.cell());
            } else {
                run(frame, engine, keys, P_WRITE, 0, item.value());
            }
        }

        private static PhpReference nestedCell(VirtualFrame frame, dev.phpruntime.nodes.var.LValueNode engine, Object[] keys) {
            return engine instanceof dev.phpruntime.nodes.var.IndexLValueNode il && il.isNested()
                    && il.usesArrayAccess(frame, keys) ? il.obtainCell(frame, keys) : null;
        }

        static Object compound(VirtualFrame frame, dev.phpruntime.nodes.var.LValueNode engine, Object[] keys,
                               PhpArith.Op op, Object value) {
            PhpReference nested = nestedCell(frame, engine, keys);
            Object current = nested != null ? nested.getValue() : engine.read(frame, keys);
            Object result = Ops.arith(op, current == null ? PhpNull.INSTANCE : current, value);
            if (nested != null) {
                nested.setValue(result);
            } else {
                engine.write(frame, keys, result);
            }
            return result;
        }

        static Object incDec(VirtualFrame frame, dev.phpruntime.nodes.var.LValueNode engine, Object[] keys,
                             boolean increment, boolean prefix) {
            if (engine instanceof dev.phpruntime.nodes.var.StaticPropertyLValueNode sp) {
                return sp.increment(frame, keys, increment, prefix);
            }
            PhpReference overloaded = engine instanceof dev.phpruntime.nodes.var.IndexLValueNode il
                    && il.usesArrayAccess(frame, keys) ? il.obtainCell(frame, keys) : null;
            Object old = overloaded != null ? overloaded.getValue() : engine.read(frame, keys);
            Object updated = old instanceof Long l && l != Long.MAX_VALUE && l != Long.MIN_VALUE
                    ? (Object) (increment ? l + 1 : l - 1) : Ops.incDecSlow(old, increment);
            if (overloaded != null) {
                overloaded.setValue(updated);
            } else {
                engine.write(frame, keys, updated);
            }
            return prefix ? updated : (old == null ? PhpNull.INSTANCE : old);
        }

        static Object append(VirtualFrame frame, dev.phpruntime.nodes.var.LValueNode engine, Object[] keys,
                             Object suffix, boolean resultNeeded) {
            if (!(suffix instanceof PhpObject)) {
                String s = suffix instanceof String str ? str : Ops.toStr(suffix);
                String r = engine.appendString(frame, keys, s, resultNeeded);
                return r == null ? PhpNull.INSTANCE : r;
            }
            PhpReference nested = nestedCell(frame, engine, keys);
            Object current = nested == null ? engine.read(frame, keys) : nested.getValue();
            String result = Ops.concat(current == null ? PhpNull.INSTANCE : current, suffix);
            if (nested == null) {
                engine.write(frame, keys, result);
            } else {
                nested.setValue(result);
            }
            return result;
        }
    }

    /**
     * A class reference ({@code Foo}, {@code self}, {@code parent}, {@code static}, an expression):
     * the cached copy of its {@code ClassRefNode} resolves it from the operand the emitter loads
     * (the called-class local, the expression's value, or null).
     */
    public record ClassRefSpec(dev.phpruntime.nodes.expr.ClassRefNode template) {
        public dev.phpruntime.nodes.expr.ClassRefNode newEngine() {
            return template.headless();
        }

        /**
         * The constant name id for a literal class name, else -1. {@code self}, {@code parent}
         * and the dynamic forms need the context or the frame, so they keep the resolving path.
         */
        public int instanceofNameId() {
            return template.kind() == dev.phpruntime.nodes.expr.ClassRefNode.Kind.NAMED ? template.classId() : -1;
        }

        @Override
        public String toString() {
            return String.valueOf(template.staticName());
        }
    }

    @Operation
    @ConstantOperand(type = ClassRefSpec.class, name = "ref")
    public static final class ResolveClass {
        @Specialization
        static PhpClass resolve(ClassRefSpec ref, Object operand,
                                @Cached("ref.newEngine()") dev.phpruntime.nodes.expr.ClassRefNode engine) {
            return engine.resolveValue(operand == PhpNull.INSTANCE ? null : operand);
        }
    }

    /** {@code Cls::NAME}: the class comes from {@link ResolveClass}. */
    @Operation
    @ConstantOperand(type = String.class, name = "constName")
    public static final class ClassConst {
        /** Marks a constant that a request has to resolve: an initializer, or an unknown name. */
        private static final Object DYNAMIC = new Object();

        /**
         * An enum case: the canonical object is per request, so the site keeps only the case's
         * declaration index and reads the object from the class, which is one array access.
         */
        @Specialization(guards = {"cls == cachedCls", "caseIndex >= 0"}, limit = "4")
        static Object readEnumCase(String constName, PhpClass cls, @Bind Node node,
                                   @Cached("cls") PhpClass cachedCls,
                                   @Cached("enumCaseIndex(cls, constName)") int caseIndex) {
            return cls.enumCaseAt(PhpContext.get(node), caseIndex);
        }

        static int enumCaseIndex(PhpClass cls, String name) {
            return cls.template().enumCaseIndex(name);
        }

        /**
         * A constant that is a literal or {@code ::class} never changes for a class, so the site
         * keeps the value for the classes it has seen. Four classes are enough for a method of a
         * base class run on a few subclasses; beyond that the slow path takes over.
         */
        @Specialization(guards = "cls == cachedCls", limit = "4")
        static Object readStatic(String constName, PhpClass cls, @Bind Node node,
                                 @Cached("cls") PhpClass cachedCls,
                                 @Cached("staticConstant(cls, constName)") Object value) {
            if (value != DYNAMIC) {
                return value;
            }
            return readSlow(constName, cls, node);
        }

        @Specialization(replaces = "readStatic")
        static Object readSlow(String constName, PhpClass cls, @Bind Node node) {
            Object val = Ops.classConstant(PhpContext.get(node), cls, constName);
            if (val == null) {
                CompilerDirectives.transferToInterpreter();
                throw Ops.error("Undefined constant " + cls.name() + "::" + constName);
            }
            return val;
        }

        static Object staticConstant(PhpClass cls, String name) {
            Object value = cls.lookupStaticConstant(name);
            return value == null ? DYNAMIC : value;
        }
    }

    @Operation
    public static final class ToStr {
        @Specialization
        static String string(String s) {
            return s;
        }

        @Fallback
        static String generic(Object v) {
            return Ops.toStr(v);
        }
    }

    /** {@code unset($x)}: a reference is unbound, a plain variable becomes undefined. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    public static final class UnsetVar {
        @Specialization
        static void unset(VirtualFrame frame, LocalAccessor acc, VarInfo info, @Bind BytecodeNode bytecode) {
            if (info.kind().isCell()) {
                acc.setObject(bytecode, frame, new PhpReference(null));
            } else {
                acc.clear(bytecode, frame);
            }
        }
    }

    /** The call itself; the callee comes from the local {@link ResolveFunction} wrote. */
    @Operation
    @ConstantOperand(type = CallSite.class)
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class DoCall {
        @Specialization
        static Object call(VirtualFrame frame, CallSite site, LocalAccessor calleeLocal, @Variadic Object[] args,
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached CallCallee callCallee) {
            return callCallee.execute(site, calleeLocal.getObject(bytecode, frame), args, location);
        }
    }

    /** Dispatch on the resolved callee: a cached direct call, an indirect call, or a builtin. */
    @com.oracle.truffle.api.dsl.GenerateInline(false)
    public abstract static class CallCallee extends Node {
        abstract Object execute(CallSite site, Object callee, Object[] args, SiteNode location);

        @Specialization(guards = "fn.callTarget() == cachedTarget", limit = "1")
        static Object direct(CallSite site, PhpFunction fn, Object[] args, SiteNode location,
                             @Cached("fn.callTarget()") RootCallTarget cachedTarget,
                             @Cached("create(cachedTarget)") DirectCallNode call,
                             @Bind Node node) {
            dev.phpruntime.PhpContext ctx = dev.phpruntime.PhpContext.get(node);
            int depth = ctx.pushTraceFrame(location);
            try {
                return Ops.ret(site.keepRef(), call.call(args));
            } finally {
                ctx.popTraceFrame(depth);
            }
        }

        @Specialization(replaces = "direct")
        static Object indirect(CallSite site, PhpFunction fn, Object[] args, SiteNode location,
                               @Cached IndirectCallNode call, @Bind Node node) {
            dev.phpruntime.PhpContext ctx = dev.phpruntime.PhpContext.get(node);
            int depth = ctx.pushTraceFrame(location);
            try {
                return Ops.ret(site.keepRef(), call.call(fn.callTarget(), args));
            } finally {
                ctx.popTraceFrame(depth);
            }
        }

        @Specialization
        static Object builtin(CallSite site, Builtins.Def def, Object[] args, SiteNode location,
                              @Bind Node node,
                              @Cached(value = "createSpl(node, site)", neverDefault = false) SplBuiltinNode spl,
                              @Cached(value = "createArrayCallbacks(site, location)", neverDefault = false) ArrayCallbackNode callbacks,
                              @Cached(value = "intrinsicDef(site)", neverDefault = false) Builtins.Def intrinsicDef,
                              @Cached(value = "intrinsicKind(site)", neverDefault = false) int intrinsicKind,
                              @Cached(value = "fastFor(site)", neverDefault = false) FastBuiltin fastPath) {
            if (def == intrinsicDef && args.length == CallNode.intrinsicArgc(intrinsicKind)) {
                // A call the compiler could not make an intrinsic because the name was resolved
                // at run time (unqualified, inside a namespace): it reached the builtin.
                Object fastValue = Ops.inlineBuiltin(intrinsicKind, args);
                if (fastValue != SplBuiltinNode.UNHANDLED) {
                    return fastValue;
                }
            }
            if (fastPath != null && def == fastPath.def()) {
                // The usual argument shape of a hot builtin, answered without the boundary.
                Object fastValue = fastPath.call(node, args);
                if (fastValue != FastBuiltin.UNHANDLED) {
                    return fastValue;
                }
            }
            if (spl != null) {
                CallNode.checkArity(def, args.length, location);
                Object fast = spl.executeOrUnhandled(args, location);
                if (fast != SplBuiltinNode.UNHANDLED) {
                    return fast;
                }
            }
            if (callbacks != null) {
                CallNode.checkArity(def, args.length, location);
                Object fast = callbacks.execute(def, args);
                if (fast != ArrayCallbackNode.UNHANDLED) {
                    return fast;
                }
            }
            if (def.refMask() != 0) {
                return Ops.callBuiltinValue(def, args, location);
            }
            return Ops.callBuiltin(def, args, location);
        }

        /** The builtin a site may reach that has an intrinsic form, or null. */
        static Builtins.Def intrinsicDef(CallSite site) {
            return CallNode.intrinsicForName(site.name()) != 0 ? Builtins.lookup(site.name()) : null;
        }

        static int intrinsicKind(CallSite site) {
            return CallNode.intrinsicForName(site.name());
        }

        /** The per-site fast path for the builtin named by the call, or null. */
        static FastBuiltin fastFor(CallSite site) {
            return FastBuiltin.lookup(site.name());
        }

        static SplBuiltinNode createSpl(Node node, CallSite site) {
            return SplBuiltinNode.create(node.getRootNode(), site.splName());
        }

        /** The per-site closure-callback path for array_map and friends, or null. */
        static ArrayCallbackNode createArrayCallbacks(CallSite site, SiteNode location) {
            return ArrayCallbackNode.create(site.name(), location);
        }
    }

    // ------------------------------------------------------------------ builtin intrinsics

    @Operation
    @ConstantOperand(type = int.class, name = "test")
    public static final class TypeTest {
        @Specialization
        static boolean test(int test, Object v) {
            return switch (test) {
                case 1 -> v instanceof PhpArray;
                case 2 -> v instanceof String;
                case 3 -> v instanceof Long;
                case 4 -> v instanceof Double;
                case 5 -> v instanceof Boolean;
                case 6 -> v == PhpNull.INSTANCE;
                case 7 -> v instanceof PhpObject;
                default -> throw CompilerDirectives.shouldNotReachHere();
            };
        }
    }

    @Operation
    @ConstantOperand(type = Builtins.Def.class)
    public static final class Strlen {
        @Specialization
        static long string(Builtins.Def def, String s) {
            return s.length();
        }

        @Fallback
        static Object generic(Builtins.Def def, Object v, @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                              @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location) {
            return Ops.callBuiltin(def, new Object[]{v}, location);
        }
    }

    @Operation
    @ConstantOperand(type = Builtins.Def.class)
    public static final class KeyExists {
        @Specialization
        static boolean array(Builtins.Def def, Object key, PhpArray array) {
            return Ops.arrayHas(array, key);
        }

        @Fallback
        static Object generic(Builtins.Def def, Object key, Object v, @Bind BytecodeNode bytecode,
                              @Bind("$bytecodeIndex") int bci,
                              @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location) {
            return Ops.callBuiltin(def, new Object[]{key, v}, location);
        }
    }

    // ------------------------------------------------------------------ runtime helpers

    /** Slow paths behind boundaries, so operations stay small when compiled. */
    static final class Ops {
        /** The builtin assert() is compiled out; dynamic paths still reach it by identity. */
        private static final Builtins.Def ASSERT = Builtins.lookup("assert");

        // Spread and named calls build their argument lists on the heap, and builtin
        // by-reference writeback goes through lvalue nodes that take the frame. Behind these
        // boundaries the frame is materialized instead of escaping from compiled code, and no
        // node is created in compiled code. Only functions with such calls pay for it.
        @TruffleBoundary
        static void addRawVarArgBoundary(com.oracle.truffle.api.frame.MaterializedFrame frame,
                                         CallArgHelper.RawCallArgs raw, Object callee, LocalAccessor acc, VarInfo info,
                                         String name, boolean spread, BytecodeNode bytecode, int bci, Node node,
                                         SiteNode site) {
            addRawVarArg(frame, raw, callee, acc, info, name, spread, bytecode, bci, node, site);
        }

        @TruffleBoundary
        static void addRawPlaceArgBoundary(com.oracle.truffle.api.frame.MaterializedFrame frame,
                                           CallArgHelper.RawCallArgs raw, Object callee,
                                           dev.phpruntime.nodes.var.LValueNode engine, PlaceSpec spec,
                                           Object prepared, String name, boolean spread, Node site) {
            addRawPlaceArg(frame, raw, callee, engine, spec, prepared, name, spread, site);
        }

        @TruffleBoundary
        static void addRawValueArgBoundary(CallArgHelper.RawCallArgs raw, Object callee, Object value, int kind,
                                           String name, boolean spread, Node site) {
            addRawValueArg(raw, callee, value, kind, name, spread, site);
        }

        @TruffleBoundary
        static Object callComplexBuiltinBoundary(com.oracle.truffle.api.frame.MaterializedFrame frame,
                                                 Builtins.Def def, CallArgHelper.RawCallArgs raw, Node site) {
            return CallArgHelper.callComplexBuiltinRaw(frame, def, raw, site);
        }

        @TruffleBoundary
        static PhpArray checkVariadic(DeclaredType type, PhpArray array, PhpBytecodeRootNode root, Node node,
                                      com.oracle.truffle.api.frame.MaterializedFrame frame, int param, String name) {
            PhpContext ctx = PhpContext.get(node);
            PhpClass self = DeclaredTypes.selfClass(root, frame);
            PhpClass called = DeclaredTypes.calledClass(root, frame);
            PhpArray out = null;
            int index = param;
            for (Object key : array.keys()) {
                Object raw = array.getRaw(key);
                if (raw instanceof PhpReference ref) {
                    Object value = ref.getValue();
                    Object checked = type.checkParameter(value == null ? PhpNull.INSTANCE : value, root, node,
                            index, null, self, called);
                    if (checked != value) {
                        ref.setValue(checked);
                        if (out == null) {
                            out = copyWithRefs(array);
                        }
                    }
                } else {
                    Object value = array.get(key);
                    if (type.matchesFast(value)) {
                        // Already one of the declared types (a string for Stringable|string).
                        index++;
                        continue;
                    }
                    Object checked = type.checkParameter(value, root, node, index, null, self, called);
                    if (checked != value) {
                        if (out == null) {
                            out = copyWithRefs(array);
                        }
                        out.put(key, checked);
                    }
                }
                index++;
            }
            return out == null ? array : out;
        }

        private static PhpArray copyWithRefs(PhpArray source) {
            PhpArray out = new PhpArray();
            for (Object key : source.keys()) {
                Object raw = source.getRaw(key);
                if (raw instanceof PhpReference ref) {
                    out.putRef(key, ref);
                } else {
                    out.put(key, source.get(key));
                }
            }
            return out;
        }

        @TruffleBoundary
        static long refMaskSlow(com.oracle.truffle.api.nodes.RootNode root) {
            if (root instanceof dev.phpruntime.runtime.PhpExceptions.BuiltinMethodRootNode builtinMethod) {
                return builtinMethod.refMask();
            }
            PhpFunctionRootNode fn = PhpFunctionRootNode.of(root);
            return fn == null ? 0L : fn.refMask();
        }

        /** A value argument for a by-reference parameter: a notice and a temporary, or an error. */
        @TruffleBoundary
        static Object sendValueToRef(Object callee, int param, int kind, Object value, Node node,
                                     BytecodeNode bytecode, int bci) {
            Builtins.Def builtin = builtinCallee(callee);
            if (builtin != null) {
                return builtinValueToRefParam(builtin, param, kind, value, node, lineOf(bytecode, bci));
            }
            return valueToRefParam(signature(callee), param, kind, value, node, lineOf(bytecode, bci));
        }

        @TruffleBoundary
        static void tableBind(dev.phpruntime.runtime.PhpSymbolTable table, String name, PhpReference cell) {
            table.bind(name, cell);
        }

        @TruffleBoundary
        static String sourcePathOf(BytecodeNode bytecode, int bci) {
            com.oracle.truffle.api.source.SourceSection s = bytecode.getSourceLocation(bci);
            if (s == null) {
                s = bytecode.getRootNode().getSourceSection();
            }
            return s != null && s.getSource() != null ? s.getSource().getPath() : null;
        }

        /** A call's result: the returned reference itself in a reference context, else its value. */
        static Object ret(boolean keepRef, Object result) {
            return keepRef ? result : CallArgHelper.unwrapReturn(result);
        }

        @TruffleBoundary
        static Object arith(PhpArith.Op op, Object a, Object b) {
            return PhpArith.apply(op, a, b);
        }

        @TruffleBoundary
        static Object negate(Object a) {
            return PhpArith.negate(a);
        }

        @TruffleBoundary
        static int compare(Object a, Object b) {
            return PhpOps.compare(a, b);
        }

        @TruffleBoundary
        static boolean looseEquals(Object a, Object b) {
            return PhpOps.looseEquals(a, b);
        }

        @TruffleBoundary
        static boolean strictEquals(Object a, Object b) {
            return PhpOps.strictEquals(a, b);
        }

        @TruffleBoundary
        static String concat(Object a, Object b) {
            return PhpOps.toStr(a).concat(PhpOps.toStr(b));
        }

        @TruffleBoundary
        static String toStr(Object v) {
            return PhpOps.toStr(v);
        }

        @TruffleBoundary
        static String join(Object[] parts) {
            // Size the builder from the parts that are already strings, so a rope of a few
            // dozen characters does not allocate and copy three growing buffers.
            long chars = 0;
            for (Object v : parts) {
                chars += v instanceof String s ? s.length() : 12;
            }
            if (chars > Integer.MAX_VALUE - 16) {
                chars = Integer.MAX_VALUE - 16;
            }
            StringBuilder sb = new StringBuilder((int) chars);
            for (Object v : parts) {
                sb.append(PhpOps.toStr(v));
            }
            return sb.toString();
        }

        static Object appendLocal(VirtualFrame frame, LocalAccessor acc, BytecodeNode bytecode, String suffix,
                                  boolean resultNeeded) {
            Object current = acc.isCleared(bytecode, frame) ? null : acc.getObject(bytecode, frame);
            PhpStringBuffer buffer;
            if (current instanceof PhpStringBuffer existing) {
                buffer = existing;
            } else {
                buffer = newBuffer(current);
                acc.setObject(bytecode, frame, buffer);
            }
            appendTo(buffer, suffix);
            return resultNeeded ? bufferString(buffer) : PhpNull.INSTANCE;
        }

        @TruffleBoundary
        static PhpStringBuffer newBuffer(Object current) {
            return new PhpStringBuffer(current instanceof String s ? s
                    : PhpOps.toStr(current == null ? PhpNull.INSTANCE : current));
        }

        @TruffleBoundary
        static void appendTo(PhpStringBuffer buffer, String suffix) {
            buffer.append(suffix);
        }

        @TruffleBoundary
        static String bufferString(PhpStringBuffer buffer) {
            return buffer.asString();
        }

        @TruffleBoundary
        static Object classConstant(PhpContext ctx, PhpClass cls, String name) {
            return cls.lookupConstant(ctx, name);
        }

        @TruffleBoundary
        static void declareFunction(PhpContext ctx, PhpFunction fn) {
            ctx.declareFunction(fn);
        }

        @TruffleBoundary
        static void declareClass(PhpContext ctx, dev.phpruntime.runtime.PhpClassTemplate template) {
            ctx.declareClass(template);
        }

        @TruffleBoundary
        static void defineConstant(PhpContext ctx, String name, Object value, int line) {
            ctx.defineConstant(name, value, line);
        }

        @TruffleBoundary
        static PhpError error(String message) {
            return new PhpError(message);
        }

        @TruffleBoundary
        static PhpArray packArgs(Object[] args) {
            PhpArray arr = new PhpArray();
            for (Object a : args) {
                arr.append(a);
            }
            return arr;
        }

        @TruffleBoundary
        static Object incDecSlow(Object old, boolean increment) {
            if (old == null || old == PhpNull.INSTANCE) {
                return increment ? (Object) 1L : PhpNull.INSTANCE;
            }
            if (old instanceof String s && !PhpOps.isNumericString(s)) {
                return increment ? dev.phpruntime.nodes.var.IncDecNode.incrementString(s) : s;
            }
            return increment ? PhpArith.add(old, 1L) : PhpArith.sub(old, 1L);
        }

        @TruffleBoundary
        static void arrayPut(PhpArray a, Object key, Object value) {
            a.put(key, value);
        }

        @TruffleBoundary
        static void arrayAppend(PhpArray a, Object value) {
            a.append(value);
        }

        /** The {@code ...$value} of an array literal: see {@code ArrayLiteralNode.unpackInto}. */
        static PhpArray arraySpread(PhpArray dest, Object unpacked, IteratorNode iterator, Node node) {
            if (unpacked instanceof PhpObject obj && SplInterfaces.has(obj, SplInterfaces.TRAVERSABLE)) {
                PhpObject it = iterator.resolve(obj);
                iterator.rewind(it);
                while (iterator.valid(it)) {
                    TruffleSafepoint.poll(node);
                    Object value = PhpOps.copyOnAssign(iterator.current(it));
                    Object key = iterator.key(it);
                    if (key instanceof String) {
                        dest.put(key, value);
                    } else if (key instanceof Long) {
                        dest.append(value);
                    } else {
                        CompilerDirectives.transferToInterpreter();
                        throw new PhpError("Keys must be of type int|string during array unpacking");
                    }
                    iterator.next(it);
                }
                return dest;
            }
            if (!(unpacked instanceof PhpArray src)) {
                String given = unpacked == null || unpacked == PhpNull.INSTANCE ? "null" : PhpOps.typeName(unpacked);
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Only arrays and Traversables can be unpacked, " + given + " given");
            }
            arraySpreadArray(dest, src);
            return dest;
        }

        @TruffleBoundary
        private static void arraySpreadArray(PhpArray dest, PhpArray src) {
            // No PHP runs here, so the cursor cannot move; the entries() snapshot and its
            // KeyValueHolder per element were pure garbage.
            int n = src.size();
            dest.reserve(n);
            for (int pos = src.firstPos(); pos >= 0; pos = src.nextPos(pos)) {
                // php's unpacking keeps a reference cell: both arrays then point at the same
                // cell, so a write through either is visible through the other.
                Object v = PhpOps.copyOnAssign(src.rawValueAt(pos));
                Object key = src.keyAt(pos);
                if (key instanceof Long) {
                    dest.append(v);
                } else {
                    dest.put(key, v);
                }
            }
        }

        @TruffleBoundary
        static Object arrayGet(PhpArray a, Object key) {
            return a.get(key);
        }

        @TruffleBoundary
        static boolean arrayHas(PhpArray a, Object key) {
            return a.containsKey(key);
        }

        static Object index(Object container, Object key) {
            return IndexReadNode.index(container, key);
        }

        /** Follows a key path; null when a level is missing (or, if {@code silent}, null too). */
        static Object path(Object v, Object[] keys, ArrayAccessNode access, boolean silent) {
            for (int i = 0; i < keys.length; i++) {
                if (v instanceof PhpObject obj) {
                    if (silent && !access.exists(obj, keys[i])) {
                        return null;
                    }
                    v = access.get(obj, keys[i]);
                } else {
                    v = index(v, keys[i]);
                }
                if (v == null || (silent && v == PhpNull.INSTANCE)) {
                    return null;
                }
            }
            return v;
        }

        @TruffleBoundary
        static void warn(Node node, String message, int line) {
            PhpContext.get(node).warning(message, line);
        }

        @TruffleBoundary
        static void warnForeach(Node node, Object value, int line) {
            warn(node, "foreach() argument must be of type array|object, " + PhpOps.typeName(value) + " given", line);
        }

        @TruffleBoundary
        static List<Map.Entry<Object, Object>> visibleEntries(PhpContext ctx, PhpObject obj, String caller) {
            return PhpClass.visibleEntries(ctx, obj, caller);
        }

        @TruffleBoundary
        static Object builtinOrFail(CallSite site) {
            Builtins.Def def = Builtins.lookup(site.name());
            if (def == null) {
                throw new PhpError("Call to undefined function " + (site.nsName() != null ? site.nsName() : site.name()) + "()");
            }
            return def;
        }

        @TruffleBoundary
        static Object valueToRefParam(PhpFunctionRootNode root, int param, int kind, Object value, Node node, int line) {
            if (kind == 0) {
                throw CallArgHelper.cannotPassByRef(root, param, null);
            }
            if (kind == 1) {
                PhpContext.get(node).notice("Only variables should be passed by reference", line);
            }
            return new PhpReference(PhpOps.copyOnAssign(value));
        }

        /**
         * A by-reference return passed as an argument: a by-reference parameter receives the
         * reference itself (CallArgHelper.evalArgForParam), any other parameter its value.
         */
        @TruffleBoundary
        static Object sendReturnedRef(Object callee, int param, PhpReference returned) {
            Builtins.Def builtin = builtinCallee(callee);
            PhpFunctionRootNode root = builtin == null ? signature(callee) : null;
            boolean byRef = builtin != null ? builtin.isRef(param) : root != null && root.isParamByRef(param);
            if (byRef) {
                returned.ensureDefined();
                return returned;
            }
            return CallArgHelper.unwrapReturn(returned);
        }

        @TruffleBoundary
        static Object builtinValueToRefParam(Builtins.Def def, int param, int kind, Object value, Node node, int line) {
            // array_multisort accepts literals and constants in every position: php sorts a
            // temporary copy instead of demanding a variable for each array.
            if (def.name().equalsIgnoreCase("array_multisort")) {
                return new PhpReference(PhpOps.copyOnAssign(value));
            }
            // CallNode.callBuiltin: a call result may be passed with a notice; anything else
            // (a plain expression or a new object) is an error for a builtin.
            if (kind != 1) {
                dev.phpruntime.builtins.BuiltinParams.Param[] params =
                        dev.phpruntime.builtins.BuiltinParams.forName(def.name());
                String suffix = params != null && param < params.length ? " ($" + params[param].name() + ")" : "";
                throw new PhpError(def.name() + "(): Argument #" + (param + 1) + suffix
                        + " could not be passed by reference");
            }
            PhpContext.get(node).notice("Only variables should be passed by reference", line);
            return new PhpReference(PhpOps.copyOnAssign(value));
        }

        @TruffleBoundary
        static dev.phpruntime.runtime.PhpCallable resolveCallable(PhpContext ctx, Object value, Object callerThis) {
            // Null location: the root locates a "Value not of type callable" error from the bci.
            return dev.phpruntime.runtime.PhpCallable.resolve(ctx, value, callerThis, null);
        }

        @TruffleBoundary
        static PhpArray magicArgs(Object[] args) {
            PhpArray arr = new PhpArray();
            for (Object a : args) {
                Object v = a instanceof PhpReference ref ? ref.getValue() : a;
                arr.append(PhpOps.copyOnAssign(v == null ? PhpNull.INSTANCE : v));
            }
            return arr;
        }

        /** A builtin reached through {@code $f(...)}: unwrap cells and write the results back. */
        @TruffleBoundary
        static Object callBuiltinValue(Builtins.Def def, Object[] args, SiteNode site) {
            boolean refs = def.refMask() != 0;
            if (refs) {
                // Most calls to a builtin with an optional by-reference parameter do not pass
                // it (str_replace without $count, preg_replace without $count). When no cell
                // is present the arguments are already plain values: call the builtin directly
                // instead of copying the argument array and sharing every array argument.
                boolean anyCell = false;
                for (Object a : args) {
                    if (a instanceof PhpReference) {
                        anyCell = true;
                        break;
                    }
                }
                if (!anyCell) {
                    return callBuiltin(def, args, site);
                }
            }
            Object[] values = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a instanceof PhpReference ref) {
                    Object v = ref.getValue();
                    values[i] = v == null ? PhpNull.INSTANCE
                            : (refs && def.isRef(i) ? v : PhpOps.copyOnAssign(v));
                } else {
                    values[i] = a;
                }
            }
            Object result = callBuiltin(def, values, site);
            if (refs) {
                for (int i = 0; i < args.length; i++) {
                    if (def.isRef(i) && args[i] instanceof PhpReference ref) {
                        ref.setValue(values[i]);
                    }
                }
            }
            return result;
        }

        // ---- spread and named arguments

        static boolean ignoresRefTargets(Object callee) {
            if (callee instanceof MethodCallee m) {
                return m.entry() == null;
            }
            if (callee instanceof ValueCallee v) {
                return v.callable().kind() == dev.phpruntime.runtime.PhpCallable.Kind.MAGIC_CALL;
            }
            return false;
        }

        static void putArg(CallArgHelper.RawCallArgs raw, String name, Object value,
                           dev.phpruntime.nodes.var.LValueNode ref, Object[] keys, Node site) {
            if (name != null) {
                CallArgHelper.checkDuplicateNamed(raw.named(), name, site);
                raw.putNamed(name, value, ref, keys);
                return;
            }
            raw.addPositional(value, ref, keys);
        }

        static Object byRefValue(Object value, int kind, PhpFunctionRootNode fnRoot, int param, Node site) {
            if (kind == 0) {
                throw CallArgHelper.cannotPassByRef(fnRoot, param, site);
            }
            if (kind == 1) {
                PhpContext.get(site).notice("Only variables should be passed by reference",
                        site instanceof dev.phpruntime.nodes.PhpNode pn ? pn.line() : 0);
            }
            return new PhpReference(PhpOps.copyOnAssign(value));
        }

        static boolean isNamedByRef(PhpFunctionRootNode fnRoot, String name) {
            int idx = CallArgHelper.findParamIndex(fnRoot, name);
            if (idx >= 0) {
                return fnRoot.isParamByRef(idx);
            }
            return fnRoot.isVariadic() && fnRoot.isParamByRef(fnRoot.normalParamCount());
        }

        /** A plain expression argument: the AST's by-value and by-reference rules. */
        static void addRawValueArg(CallArgHelper.RawCallArgs raw, Object callee, Object value, int kind,
                                   String name, boolean spread, Node site) {
            PhpReference returned = value instanceof PhpReference r ? r : null;
            if (returned != null) {
                value = CallArgHelper.unwrapReturn(returned);
            }
            Builtins.Def builtin = builtinCallee(callee);
            if (spread) {
                CallArgHelper.unpackCallArg(value, null, raw, site);
                return;
            }
            if (builtin != null) {
                putArg(raw, name, PhpOps.copyOnAssign(value), null, null, site);
                return;
            }
            PhpFunctionRootNode fnRoot = signature(callee);
            if (name != null) {
                boolean byRef = fnRoot != null && isNamedByRef(fnRoot, name);
                Object v = byRef && returned != null ? defined(returned) : byRef
                        ? byRefValue(value, kind, fnRoot,
                        CallArgHelper.findParamIndex(fnRoot, name) >= 0
                                ? CallArgHelper.findParamIndex(fnRoot, name) : fnRoot.normalParamCount(), site)
                        : PhpOps.copyOnAssign(value);
                putArg(raw, name, v, null, null, site);
                return;
            }
            int idx = raw.positional().size();
            boolean byRef = fnRoot != null && fnRoot.isParamByRef(idx);
            Object v = byRef && returned != null ? defined(returned)
                    : byRef ? byRefValue(value, kind, fnRoot, idx, site) : PhpOps.copyOnAssign(value);
            putArg(raw, null, v, null, null, site);
        }

        private static PhpReference defined(PhpReference ref) {
            ref.ensureDefined();
            return ref;
        }

        /** A variable argument, evaluated with the AST's place/by-ref rules. */
        static void addRawVarArg(VirtualFrame frame, CallArgHelper.RawCallArgs raw, Object callee,
                                 LocalAccessor acc, VarInfo info, String name, boolean spread,
                                 BytecodeNode bytecode, int bci, Node node, SiteNode site) {
            if (builtinCallee(callee) == null && ignoresRefTargets(callee)) {
                addRawValueArg(raw, callee, ReadVar.read(frame, acc, info, bytecode, bci, node), 0, name, spread, site);
                return;
            }
            Builtins.Def builtin = builtinCallee(callee);
            PhpFunctionRootNode fnRoot = builtin == null ? signature(callee) : null;
            if (spread) {
                boolean needRef = builtin != null ? builtin.refMask() != 0
                        : fnRoot != null && fnRoot.hasAnyRefParam();
                Object unpacked;
                PhpArray writable = null;
                if (needRef) {
                    Object cur = current(frame, acc, bytecode);
                    if (cur instanceof PhpArray arr) {
                        PhpReference cell = ObtainVarCell.obtain(frame, acc, info, bytecode);
                        writable = arr.forWrite();
                        cell.setValue(writable);
                        unpacked = writable;
                    } else {
                        unpacked = cur == null ? PhpNull.INSTANCE : cur;
                    }
                } else {
                    unpacked = ReadVar.read(frame, acc, info, bytecode, bci, node);
                }
                CallArgHelper.unpackCallArg(unpacked, writable, raw, site);
                return;
            }
            boolean byRef;
            int param;
            if (name != null) {
                param = fnRoot != null ? CallArgHelper.findParamIndex(fnRoot, name) : -1;
                byRef = fnRoot != null && isNamedByRef(fnRoot, name);
            } else {
                param = raw.positional().size();
                byRef = fnRoot != null && fnRoot.isParamByRef(param);
            }
            if (byRef) {
                Object cell = "this".equals(info.name())
                        ? new PhpReference(ReadVar.read(frame, acc, info, bytecode, bci, node))
                        : ObtainVarCell.obtain(frame, acc, info, bytecode);
                putArg(raw, name, cell, null, null, site);
                return;
            }
            Object v = current(frame, acc, bytecode);
            v = v == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(v);
            dev.phpruntime.nodes.var.LValueNode ref = builtin != null
                    ? new LocalLValue(acc, info, bytecode) : null;
            putArg(raw, name, v, ref, ref != null ? dev.phpruntime.nodes.var.LValueNode.NO_KEYS : null, site);
        }

        /** A place argument (index path, property, global): the AST's place/by-ref rules. */
        static void addRawPlaceArg(VirtualFrame frame, CallArgHelper.RawCallArgs raw, Object callee,
                                   dev.phpruntime.nodes.var.LValueNode engine, PlaceSpec spec, Object prepared,
                                   String name, boolean spread, Node site) {
            Object[] keys = ((PreparedPlace) prepared).keys();
            if (builtinCallee(callee) == null && ignoresRefTargets(callee)) {
                addRawValueArg(raw, callee, engine.readValue(frame, keys), 0, name, spread, site);
                return;
            }
            Builtins.Def builtin = builtinCallee(callee);
            PhpFunctionRootNode fnRoot = builtin == null ? signature(callee) : null;
            if (spread) {
                boolean needRef = builtin != null ? builtin.refMask() != 0
                        : fnRoot != null && fnRoot.hasAnyRefParam();
                Object unpacked;
                PhpArray writable = null;
                if (needRef) {
                    Object cur = engine.read(frame, keys);
                    if (cur instanceof PhpArray arr) {
                        PhpReference cell = engine.obtainCell(frame, keys);
                        writable = arr.forWrite();
                        cell.setValue(writable);
                        unpacked = writable;
                    } else {
                        unpacked = cur == null ? PhpNull.INSTANCE : cur;
                    }
                } else {
                    unpacked = engine.readValue(frame, keys);
                }
                CallArgHelper.unpackCallArg(unpacked, writable, raw, site);
                return;
            }
            boolean byRef;
            int param;
            if (name != null) {
                param = fnRoot != null ? CallArgHelper.findParamIndex(fnRoot, name) : -1;
                byRef = fnRoot != null && isNamedByRef(fnRoot, name);
            } else {
                param = raw.positional().size();
                byRef = fnRoot != null && fnRoot.isParamByRef(param);
            }
            if (byRef) {
                if (spec.template() instanceof dev.phpruntime.nodes.var.GlobalVarNode gv
                        && gv.isGlobalsSuperglobal()) {
                    throw CallArgHelper.cannotPassByRef(fnRoot, param, site);
                }
                putArg(raw, name, engine.obtainCell(frame, keys), null, null, site);
                return;
            }
            Object v = engine.read(frame, keys);
            v = v == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(v);
            dev.phpruntime.nodes.var.LValueNode ref = builtin != null ? engine : null;
            putArg(raw, name, v, ref, ref != null ? keys : null, site);
        }

        // ---- destructuring

        static DestructureState destructureStartCell(PhpReference cell, String describe, int line, Node node) {
            Object cur = cell.getValue();
            if (cur == null) {
                PhpContext.get(node).warning("Undefined variable " + describe, line);
                cur = PhpNull.INSTANCE;
            }
            if (cur instanceof PhpArray arr) {
                PhpArray writable = arr.forWrite();
                cell.setValue(writable);
                return DestructureState.ref(cell);
            }
            return DestructureState.value(cur);
        }

        static DestructureState destructureStartValue(Object raw, boolean hasAnyRef, int line, Node node) {
            if (raw instanceof PhpReference ref) {
                if (ref.getValue() instanceof PhpArray arr) {
                    PhpArray writable = arr.forWrite();
                    ref.setValue(writable);
                    return DestructureState.ref(ref);
                }
                Object v = ref.getValue() == null ? PhpNull.INSTANCE : ref.getValue();
                return DestructureState.value(v);
            }
            if (hasAnyRef) {
                PhpContext.get(node).notice("Attempting to set reference to non referenceable value", line);
                return DestructureState.valueCopied(raw);
            }
            return DestructureState.value(raw);
        }

        static Object destructureResult(DestructureState state) {
            Object v;
            if (state.ref()) {
                Object cellValue = state.container().getValue();
                v = cellValue == null ? PhpNull.INSTANCE : cellValue;
            } else {
                v = state.result();
            }
            return state.copyResult() ? PhpOps.copyOnAssign(v) : v;
        }

        static DestructureItem destructureItem(VirtualFrame frame, DestructureState state, Object key, boolean byRef,
                                               ArrayAccessNode access, Node node, int line) {
            if (state.ref()) {
                PhpArray array = state.container().getValue() instanceof PhpArray a ? a : null;
                if (array == null) {
                    return byRef ? new DestructureItem(new PhpReference(PhpNull.INSTANCE), null)
                            : new DestructureItem(null, PhpNull.INSTANCE);
                }
                if (byRef) {
                    return new DestructureItem(destructureCell(array, key, node), null);
                }
                Object fetched = destructureGet(array, key, node);
                if (fetched == null) {
                    warnDestructureMissing(key, line, node);
                    return new DestructureItem(null, PhpNull.INSTANCE);
                }
                return new DestructureItem(null, PhpOps.copyOnAssign(fetched));
            }
            if (state.source() instanceof PhpArray array) {
                Object fetched = destructureGet(array, key, node);
                if (fetched == null) {
                    warnDestructureMissing(key, line, node);
                    return destructureValueItem(byRef, PhpNull.INSTANCE);
                }
                return destructureValueItem(byRef, PhpOps.copyOnAssign(fetched));
            }
            if (state.source() instanceof PhpObject obj) {
                Object fetched = byRef ? access.cell(obj, key) : access.get(obj, key);
                if (fetched instanceof PhpReference ref) {
                    return new DestructureItem(ref, null);
                }
                return destructureValueItem(byRef, PhpOps.copyOnAssign(fetched));
            }
            return destructureValueItem(byRef, PhpNull.INSTANCE);
        }

        private static DestructureItem destructureValueItem(boolean byRef, Object value) {
            return byRef ? new DestructureItem(new PhpReference(value), null)
                    : new DestructureItem(null, value);
        }

        private static Object destructureGet(PhpArray array, Object key, Node node) {
            try {
                return array.get(key);
            } catch (PhpError e) {
                throw e.at(node);
            }
        }

        private static PhpReference destructureCell(PhpArray array, Object key, Node node) {
            try {
                return array.obtainElementRef(key);
            } catch (PhpError e) {
                throw e.at(node);
            }
        }

        static boolean destructureHas(PhpArray array, Object key, Node node) {
            try {
                return array.containsKey(key);
            } catch (PhpError e) {
                throw e.at(node);
            }
        }

        @TruffleBoundary
        static void warnDestructureMissing(Object rawKey, int line, Node node) {
            Object norm = PhpArray.normalizeKey(rawKey);
            String shown = norm instanceof String s ? '"' + s + '"' : PhpOps.toStr(norm);
            PhpContext.get(node).warning("Undefined array key " + shown, line);
        }

        // ---- foreach by reference

        static RefCursor refStartPlace(VirtualFrame frame, dev.phpruntime.nodes.var.LValueNode engine,
                                       Object[] keys, String describe, int line, Node node,
                                       Object traitSelf, String declaringClass) {
            Object cur = engine.read(frame, keys);
            if (cur == null) {
                PhpContext.get(node).warning("Undefined variable " + describe, line);
                cur = PhpNull.INSTANCE;
            }
            if (!(cur instanceof PhpArray)) {
                return refFromValue(cur, null, traitSelf, declaringClass, line, node);
            }
            return refFromValue(cur, engine.obtainCell(frame, keys), traitSelf, declaringClass, line, node);
        }

        static RefCursor refStartValue(Object raw, Object traitSelf, String declaringClass, int line, Node node) {
            PhpReference cell = raw instanceof PhpReference ref ? ref : null;
            Object value = cell != null
                    ? (cell.getValue() == null ? PhpNull.INSTANCE : cell.getValue()) : raw;
            if (value instanceof PhpArray) {
                return refFromValue(value, cell, traitSelf, declaringClass, line, node);
            }
            return refFromValue(value, null, traitSelf, declaringClass, line, node);
        }

        private static RefCursor refFromValue(Object value, PhpReference cell, Object traitSelf,
                                              String declaringClass, int line, Node node) {
            if (value instanceof PhpArray arr) {
                PhpArray writable = arr.forWrite();
                if (cell != null) {
                    cell.setValue(writable);
                }
                return new RefCursor(writable, cell, writable.keys(), null);
            }
            if (value instanceof PhpObject obj) {
                if (obj.nativeState() instanceof PhpGenerator generator) {
                    if (!generator.signature.returnsRef()) {
                        CompilerDirectives.transferToInterpreter();
                        throw PhpGenerator.referenceIterationError(node, line);
                    }
                    return new RefCursor(obj, generator);
                }
                if (obj.phpClass().hasSplInterface(SplInterfaces.TRAVERSABLE)) {
                    CompilerDirectives.transferToInterpreter();
                    throw new PhpError("An iterator cannot be used with foreach by reference");
                }
                String caller = traitSelf instanceof PhpClass ts ? ts.name()
                        : (declaringClass == null || declaringClass.isEmpty() ? null : declaringClass);
                return new RefCursor(null, null, null,
                        PhpClass.visibleRefEntries(PhpContext.get(node), obj, caller));
            }
            warnForeach(node, value, line);
            return new RefCursor(null, null, null, null);
        }

        @TruffleBoundary
        static Object cursorKey(YieldFromCursor cursor) {
            return cursor.entries.get(cursor.index).getKey();
        }

        @TruffleBoundary
        static Object cursorValue(YieldFromCursor cursor) {
            return cursor.entries.get(cursor.index).getValue();
        }

        /**
         * The intrinsic forms of {@link CallNode} for a call that resolved to the builtin at run
         * time; {@link SplBuiltinNode#UNHANDLED} when the arguments need the builtin itself
         * (strlen of a non-string, array_key_exists on an object), which raises any diagnostic.
         */
        static Object inlineBuiltin(int kind, Object[] args) {
            if (kind == CallNode.ARRAY_KEY_EXISTS) {
                // Only plain keys: the function form words an invalid key's TypeError differently
                // from the global-name intrinsic.
                return args[1] instanceof PhpArray array && (args[0] instanceof String || args[0] instanceof Long)
                        ? (Object) arrayHas(array, args[0]) : SplBuiltinNode.UNHANDLED;
            }
            if (kind == CallNode.STRLEN) {
                return args[0] instanceof String s ? (Object) (long) s.length() : SplBuiltinNode.UNHANDLED;
            }
            return TypeTest.test(kind, args[0]);
        }

        /**
         * Calls a builtin from guest code. The context is read here, in compiled guest code,
         * where the lookup is a few loads; inside the boundary the same lookup takes Truffle's
         * generic path on every call.
         */
        static Object callBuiltin(Builtins.Def def, Object[] args, SiteNode site) {
            return callBuiltinBoundary(PhpContext.get(site), def, args, site);
        }

        @TruffleBoundary
        private static Object callBuiltinBoundary(PhpContext ctx, Builtins.Def def, Object[] args, SiteNode site) {
            if (def == ASSERT) {
                return true;
            }
            CallNode.checkArity(def, args.length, site);
            Node prev = ctx.currentBuiltinCallSite();
            String prevName = ctx.enterBuiltin(site, def.name());
            try {
                return def.call(ctx, args);
            } catch (PhpError e) {
                throw e.withTrace(PhpBacktrace.captureFromBuiltin(site, def.name())).at(site);
            } finally {
                ctx.exitBuiltin(prev, prevName);
            }
        }
    }

    static String bytes(String s) {
        return PhpStrings.fromJava(s);
    }

    // ---- 17b: simple statements and expressions

    /** {@code (int)}, {@code (float)}, ...: the conversion lives in {@link CastNode#cast}. */
    @Operation
    @ConstantOperand(type = CastNode.Kind.class, name = "kind")
    public static final class Cast {
        @Specialization
        static Object cast(CastNode.Kind kind, Object value, @Bind Node node) {
            return CastNode.cast(PhpContext.get(node), kind, value, node);
        }
    }

    /** {@code exit(expr)} / {@code die}: print unless the value is an exit code, then unwind. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "hasValue")
    public static final class Exit {
        @Specialization
        static Object exit(boolean hasValue, Object value, @Bind Node node) {
            if (hasValue && !(value instanceof Long)) {
                try {
                    PhpContext.get(node).echo(Ops.toStr(value));
                } catch (PhpError e) {
                    throw e.at(node);
                }
            }
            throw new ControlFlow.Exit();
        }
    }

    /** Unary {@code +} and {@code ~}: the conversion lives in {@code UnaryNode.apply}. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "bitNot")
    public static final class Unary {
        @Specialization
        static Object apply(boolean bitNot, Object value, @Bind Node node) {
            return UnaryNode.apply(value, bitNot, node);
        }
    }

    /** {@code throw $e}: validation and the PHP exception, shared with the AST node. */
    @Operation
    public static final class Throw {
        @Specialization
        static Object thr(Object value, @Bind Node node) {
            return ThrowNode.throwValue(value, node);
        }
    }

    /** {@code global $$name}: the global variable's cell for a computed name. */
    @Operation
    public static final class GlobalSymbolRef {
        @Specialization
        static PhpReference ref(String name, @Bind Node node) {
            return PhpContext.get(node).globalSymbolRef(name);
        }
    }

    /** {@code global $x;}: the global variable's reference cell, stored into the local by the emitter. */
    @Operation
    @ConstantOperand(type = int.class, name = "globalId")
    public static final class GlobalRef {
        @Specialization
        static PhpReference ref(int globalId, @Bind Node node) {
            return PhpContext.get(node).globalRefById(globalId);
        }
    }

    /** A copy of a {@code ReadConstantNode} for one bytecode instruction: it caches the built-in. */
    public record ConstantSpec(ReadConstantNode template) {
        public ReadConstantNode newEngine() {
            return template.headless();
        }

        @Override
        public String toString() {
            return template.name();
        }
    }

    @Operation
    @ConstantOperand(type = ConstantSpec.class)
    public static final class ReadConstant {
        @Specialization
        static Object read(ConstantSpec spec,
                           @Cached("spec.newEngine()") ReadConstantNode engine) {
            // A miss throws a PhpError with no root node; the root locates it from the instruction.
            return engine.execute(null);
        }
    }

    /** The static-local owner key: the closure object, or the function root the bytecode came from. */
    static Object staticOwner(Object owner, PhpBytecodeRootNode root) {
        return owner instanceof PhpObject ? owner : root.function;
    }

    /** {@code static $x}: the existing per-context cell, or null when it must be initialized. */
    @Operation
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = boolean.class, name = "global")
    public static final class StaticCell {
        @Specialization
        static PhpReference cell(String name, boolean global, Object owner, @Bind PhpBytecodeRootNode root,
                                 @Bind Node node) {
            PhpContext ctx = PhpContext.get(node);
            return ctx.getStaticLocal(global ? ctx : staticOwner(owner, root), name);
        }
    }

    /** The first execution of {@code static $x = expr}: creates and registers the cell. */
    @Operation
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = boolean.class, name = "global")
    public static final class InitStaticCell {
        @Specialization
        static PhpReference init(String name, boolean global, Object owner, Object value,
                                 @Bind PhpBytecodeRootNode root, @Bind Node node) {
            PhpContext ctx = PhpContext.get(node);
            return ctx.initStaticLocal(global ? ctx : staticOwner(owner, root), name, value);
        }
    }

    /** File-scope {@code static} variables are globals; bind the cell as the AST does. */
    @Operation
    @ConstantOperand(type = int.class, name = "globalId")
    public static final class BindGlobalById {
        @Specialization
        static void bind(int globalId, PhpReference cell, @Bind Node node) {
            PhpContext.get(node).bindGlobalById(globalId, cell);
        }
    }

    /** The catch clauses of one try, for the bytecode catch handler. */
    public record CatchSpec(TryCatchNode.CatchClause[] clauses) {
        @Override
        public String toString() {
            return "catch " + clauses.length;
        }
    }

    /** Dispatches a thrown object to its catch clause; rethrows when no clause matches. */
    @Operation
    @ConstantOperand(type = CatchSpec.class)
    public static final class CatchMatch {
        @Specialization
        static long match(CatchSpec spec, Object thrown, @Bind Node node) {
            Throwable t = (Throwable) thrown;
            int index = TryCatchNode.catchIndex(PhpContext.get(node), spec.clauses(), t);
            if (index < 0) {
                CompilerDirectives.transferToInterpreter();
                throw unchecked(t);
            }
            return index;
        }
    }

    /** The PHP object a matched catch clause binds: the thrown object, or the materialized Error. */
    @Operation
    public static final class CatchValue {
        @Specialization
        static PhpObject value(Object thrown, @Bind Node node) {
            PhpContext ctx = PhpContext.get(node);
            PhpObject value = TryCatchNode.throwableObject(ctx, (Throwable) thrown, node);
            if (value == null) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Internal error: caught a non-catchable throwable");
            }
            return value;
        }
    }

    /**
     * Whether a pending throwable runs the finally block: not when the block itself threw while
     * running inline for a return or jump ({@code exiting}), and not for non-catchable fatals.
     */
    @Operation
    public static final class ShouldRunFinally {
        @Specialization
        static boolean test(Object pending, Object exiting) {
            return exiting != Boolean.TRUE && pending instanceof Throwable t
                    && TryCatchNode.shouldRunFinallyFor(t);
        }
    }

    /** Links a throwable raised by a finally block to the pending one, as the AST does. */
    @Operation
    public static final class LinkPrevious {
        @Specialization
        static void link(Object pending, Object raised, @Bind Node node) {
            if (!(pending instanceof Throwable prev) || !(raised instanceof Throwable next)) {
                return;
            }
            PhpContext ctx = PhpContext.get(node);
            PhpObject previous = TryCatchNode.throwableObject(ctx, prev, node);
            if (previous == null) {
                return;
            }
            if (next instanceof PhpException ex) {
                PhpExceptions.linkPrevious(ex.exceptionObject(), previous);
            } else if (next instanceof PhpError err && err.isCatchable()) {
                PhpExceptions.linkPrevious(err.toPhpObject(ctx, node), previous);
            }
        }
    }

    /** Rethrows the current throwable from a bytecode handler. */
    @Operation
    public static final class Rethrow {
        @Specialization
        static void rethrow(Object thrown) {
            throw unchecked((Throwable) thrown);
        }
    }

    @SuppressWarnings("unchecked")
    static <E extends Throwable> RuntimeException unchecked(Throwable t) throws E {
        throw (E) t;
    }

    // ---- dynamic scope

    /** Builds the symbol table of a dynamic-scope function (see {@code PhpFunctionRootNode.newSymbolTable}). */
    @Operation
    public static final class InitSymbols {
        @Specialization
        static Object init(Object thisValue, Object calledClass, Object traitSelf, @Variadic Object[] values,
                           @Bind PhpBytecodeRootNode root, @Bind Node node) {
            return root.function.newSymbolTable(PhpContext.get(node), thisValue, calledClass, traitSelf, values);
        }
    }

    /** What a raw variable read yields for an undefined variable (compact and extract need the difference). */
    public static final Object UNDEFINED = new Object() {
        @Override
        public String toString() {
            return "undefined";
        }
    };

    /** A variable's value without the undefined warning, or {@link #UNDEFINED}. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class ReadVarRaw {
        @Specialization
        static Object read(VirtualFrame frame, LocalAccessor acc, @Bind BytecodeNode bytecode) {
            Object v = current(frame, acc, bytecode);
            return v == null ? UNDEFINED : v;
        }
    }

    /** {@code global}/{@code static} in a dynamic-scope function: binds a table slot to a cell. */
    @Operation
    @ConstantOperand(type = int.class, name = "slot")
    public static final class SetTableCell {
        @Specialization
        static void set(int slot, Object table, Object cell) {
            ((dev.phpruntime.runtime.PhpSymbolTable) table).cells[slot] = (PhpReference) cell;
        }
    }

    @Operation
    public static final class GetDefinedVars {
        @Specialization
        static Object vars(Object table, @Bind Node node) {
            return table instanceof dev.phpruntime.runtime.PhpSymbolTable t ? t.snapshot()
                    : globalsSnapshot(PhpContext.get(node));
        }

        @TruffleBoundary
        private static Object globalsSnapshot(PhpContext ctx) {
            return ctx.buildGlobalsSnapshot();
        }
    }

    public record CompactSpec(dev.phpruntime.nodes.expr.CompactNode template) {
        public dev.phpruntime.nodes.expr.CompactNode newEngine() {
            return template.headless();
        }

        @Override
        public String toString() {
            return "compact";
        }
    }

    /** compact(): operands are the table (or null), then per argument a raw read or the value. */
    @Operation
    @ConstantOperand(type = CompactSpec.class)
    public static final class Compact {
        @Specialization
        static Object compact(CompactSpec spec, Object table, @Variadic Object[] values,
                              @Cached("spec.newEngine()") dev.phpruntime.nodes.expr.CompactNode engine) {
            return engine.build(table, values);
        }
    }

    public record ExtractSpec(dev.phpruntime.nodes.expr.ExtractNode template) {
        public dev.phpruntime.nodes.expr.ExtractNode newEngine() {
            return template.headless();
        }

        @Override
        public String toString() {
            return "extract";
        }
    }

    /** extract(): operands are the table (or null), the array (raw read), the flags and the prefix. */
    @Operation
    @ConstantOperand(type = ExtractSpec.class)
    public static final class Extract {
        @Specialization
        static Object extract(ExtractSpec spec, Object table, Object array, Object flags, Object prefix,
                              @Cached("spec.newEngine()") dev.phpruntime.nodes.expr.ExtractNode engine) {
            return engine.run(table, array == UNDEFINED ? null : array, flags, prefix);
        }
    }

    /** include/require into a function's local scope: the included code shares the symbol table. */
    @Operation
    @ConstantOperand(type = IncludeSpec.class)
    public static final class IncludeLocal {
        @Specialization
        static Object include(IncludeSpec spec, Object table, Object path,
                              @Cached("spec.newEngine()") dev.phpruntime.nodes.expr.IncludeNode engine) {
            return engine.executePath(path instanceof String s ? s : Ops.toStr(path), table);
        }
    }

    // ---- 17a: objects and calls

    /**
     * A {@code Cls::m()} call: the forwarding rule of {@code StaticMethodCallNode.executeRaw}.
     * Operands are the class, the method name, {@code $this} and the called class. The result is
     * a {@link MethodCallee}, which {@link DoMethodCall} then calls unchanged.
     */
    @Operation
    @ConstantOperand(type = MethodSite.class)
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    @ConstantOperand(type = boolean.class, name = "forwarding")
    @ConstantOperand(type = boolean.class, name = "hasCalledClass")
    public static final class ResolveStaticMethod {
        @Specialization
        static void resolve(VirtualFrame frame, MethodSite site, LocalAccessor calleeLocal, boolean forwarding,
                            boolean hasCalledClass, PhpClass targetClass, Object nameValue, Object thisValue,
                            Object calledClassValue, @Bind BytecodeNode bytecode,
                            @Cached("createIdentityProfile()") com.oracle.truffle.api.profiles.ValueProfile sigIdentity) {
            String name = nameValue instanceof String s ? s : Ops.toStr(nameValue);
            PhpClass.MethodEntry entry = site.methodId() >= 0 ? targetClass.methodById(site.methodId())
                    : targetClass.lookupMethod(name);
            PhpClass calledArg = forwarding && hasCalledClass
                    ? (calledClassValue instanceof PhpClass c ? c : null) : targetClass;
            if (entry == null) {
                PhpClass.MethodEntry magic = targetClass.callMethod();
                if (forwarding && thisValue instanceof PhpObject thisObj
                        && magic != null && magic.callTarget() != null) {
                    calleeLocal.setObject(bytecode, frame,
                            new MethodCallee(null, thisObj, calledArg, null, thisObj, name, magic.callTarget()));
                    return;
                }
                PhpClass.MethodEntry magicStatic = targetClass.callStaticMethod();
                if (magicStatic != null && magicStatic.callTarget() != null) {
                    calleeLocal.setObject(bytecode, frame,
                            new MethodCallee(null, PhpNull.INSTANCE, calledArg, null, PhpNull.INSTANCE, name,
                                    magicStatic.callTarget()));
                    return;
                }
                CompilerDirectives.transferToInterpreter();
                throw Ops.error("Call to undefined method " + targetClass.name() + "::" + name + "()");
            }
            if (entry.callTarget() == null) {
                CompilerDirectives.transferToInterpreter();
                throw Ops.error("Cannot call abstract method " + entry.declaringClass().name() + "::"
                        + entry.name() + "()");
            }
            Object thisArg;
            if (entry.isStatic()) {
                thisArg = PhpNull.INSTANCE;
            } else if (thisValue instanceof PhpObject thisObj
                    && thisObj.phpClass().isInstanceOf(entry.declaringClass())) {
                thisArg = thisObj;
            } else {
                CompilerDirectives.transferToInterpreter();
                throw Ops.error("Non-static method " + entry.declaringClass().name() + "::" + entry.name()
                        + "() cannot be called statically");
            }
            RootCallTarget sig = (RootCallTarget) sigIdentity.profile(entry.callTarget());
            PhpObject obj = thisValue instanceof PhpObject o ? o : null;
            calleeLocal.setObject(bytecode, frame,
                    new MethodCallee(entry, thisArg, calledArg, sig, obj, name, null));
        }
    }

    /** {@code new Cls(...)}: validates the class, creates the object and populates its defaults. */
    @Operation
    public static final class NewObject {
        /**
         * A site that keeps instantiating the same class: one PutNode per declared property, so
         * the chain of shape transitions folds away in compiled code instead of running through
         * uncached puts behind a boundary.
         */
        @Specialization(guards = "cls == cachedCls", limit = "3")
        static PhpObject createCached(PhpClass cls, @Bind Node node, @Bind BytecodeNode bytecode,
                                      @Bind("$bytecodeIndex") int bci,
                                      @Cached("cls") PhpClass cachedCls,
                                      @Cached(value = "cls.defaultKeys()", dimensions = 1) String[] keys,
                                      @Cached(value = "cls.constantDefaultValuesOrNull()", dimensions = 1) Object[] constValues,
                                      @Cached("createPuts(keys.length)") DynamicObject.PutNode[] puts,
                                      @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location) {
            PhpContext ctx = PhpContext.get(node);
            checkInstantiable(ctx, cachedCls);
            PhpObject obj = new PhpObject(cachedCls, ctx.nextObjectId());
            putDefaults(obj, keys, constValues != null ? constValues : cachedCls.defaultValues(), puts);
            if (cachedCls.isThrowable()) {
                dev.phpruntime.runtime.PhpExceptions.initNewThrowable(obj, location);
            }
            return obj;
        }

        @Specialization(replaces = "createCached")
        static PhpObject create(PhpClass cls, @Bind Node node, @Bind BytecodeNode bytecode,
                                @Bind("$bytecodeIndex") int bci,
                                @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location) {
            PhpContext ctx = PhpContext.get(node);
            checkInstantiable(ctx, cls);
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            cls.populateDefaults(obj);
            if (cls.isThrowable()) {
                dev.phpruntime.runtime.PhpExceptions.initNewThrowable(obj, location);
            }
            return obj;
        }

        private static void checkInstantiable(PhpContext ctx, PhpClass cls) {
            if (cls == ctx.closureClass()) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Instantiation of Closure is not allowed");
            }
            if (cls.template().isEnum()) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Cannot instantiate enum " + cls.name());
            }
            if (cls.isInterface() || cls.isTrait() || cls.isAbstract()) {
                String kind = cls.isInterface() ? "interface " : (cls.isTrait() ? "trait " : "abstract class ");
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Cannot instantiate " + kind + cls.name());
            }
        }

        static DynamicObject.PutNode[] createPuts(int n) {
            DynamicObject.PutNode[] puts = new DynamicObject.PutNode[n];
            for (int i = 0; i < n; i++) {
                puts[i] = DynamicObject.PutNode.create();
            }
            return puts;
        }

        @ExplodeLoop
        private static void putDefaults(PhpObject obj, String[] keys, Object[] values, DynamicObject.PutNode[] puts) {
            for (int i = 0; i < puts.length; i++) {
                puts[i].execute(obj, keys[i], PhpOps.copyOnAssign(values[i]));
            }
        }
    }

    /**
     * The constructor call of a {@code new}, resolved into the call local as {@link ResolveMethod}
     * does so its by-reference parameters work. No constructor stores PHP null.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class ResolveConstructor {
        @Specialization
        static void resolve(VirtualFrame frame, LocalAccessor calleeLocal, PhpObject obj,
                            @Bind BytecodeNode bytecode,
                            @Cached("createIdentityProfile()") com.oracle.truffle.api.profiles.ValueProfile sigIdentity) {
            PhpClass cls = obj.phpClass();
            PhpClass.MethodEntry ctor = cls.constructMethod();
            if (ctor == null) {
                calleeLocal.setObject(bytecode, frame, PhpNull.INSTANCE);
                return;
            }
            calleeLocal.setObject(bytecode, frame,
                    new MethodCallee(ctor, obj, cls, (RootCallTarget) sigIdentity.profile(ctor.callTarget()), obj,
                            ctor.name(), null));
        }
    }

    /** Runs the constructor resolved by {@link ResolveConstructor}; the object is the result. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class DoConstructorCall {
        @Specialization
        static Object call(VirtualFrame frame, LocalAccessor calleeLocal, PhpObject obj, @Variadic Object[] args,
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create()") DispatchCallNode dispatch) {
            Object callee = calleeLocal.getObject(bytecode, frame);
            if (callee instanceof MethodCallee m) {
                Object[] callArgs = new Object[2 + args.length];
                callArgs[0] = m.thisArg();
                callArgs[1] = m.cls();
                System.arraycopy(args, 0, callArgs, 2, args.length);
                dispatch.callMethod(m.entry(), callArgs, location, m.cls());
            }
            return obj;
        }
    }

    /**
     * {@code $value instanceof Cls}: the resolved value and the class reference's operand. As in
     * {@code InstanceofNode}, a missing class or a non-object value is just false.
     */
    @Operation
    @ConstantOperand(type = ClassRefSpec.class)
    public static final class Instanceof {
        /**
         * A literal class name: the name id answers from the value class's ancestor set without
         * the context or the class table. A miss still resolves, because a {@code class_alias}
         * name points at a class whose own name id is different.
         */
        /**
         * A value of a class this site has seen: the answer is a constant of that class while
         * the name has never been a class_alias in any request.
         */
        @Specialization(guards = {"spec.instanceofNameId() >= 0", "obj.phpClass() == cachedClass"},
                assumptions = "neverAliased", limit = "3")
        static boolean cachedClass(ClassRefSpec spec, PhpObject obj, Object operand,
                                   @Cached("obj.phpClass()") PhpClass cachedClass,
                                   @Cached("neverAliased(obj, spec)") com.oracle.truffle.api.Assumption neverAliased,
                                   @Cached("cachedClass.matchesNameId(spec.instanceofNameId())") boolean answer) {
            return answer;
        }

        static com.oracle.truffle.api.Assumption neverAliased(PhpObject obj, ClassRefSpec spec) {
            return PhpLanguage.get(null).neverAliased(spec.instanceofNameId());
        }

        @Specialization(guards = "spec.instanceofNameId() >= 0")
        static boolean named(ClassRefSpec spec, Object value, Object operand, @Bind Node node) {
            // A non-object is never an instance, and php does not autoload for instanceof.
            if (!(value instanceof PhpObject obj)) {
                return false;
            }
            int nameId = spec.instanceofNameId();
            if (obj.phpClass().matchesNameId(nameId)) {
                return true;
            }
            // The miss is final unless the name is a class_alias: no class of that name in
            // this request means false, and a class declared under its own name has that name
            // id in every descendant's ancestor set. Only an alias needs the class test.
            PhpClass target = PhpContext.get(node).classById(nameId);
            return target != null && target.classId() != nameId && obj.phpClass().isInstanceOf(target);
        }

        @Specialization
        static boolean test(ClassRefSpec spec, Object value, Object operand,
                            @Cached("spec.newEngine()") dev.phpruntime.nodes.expr.ClassRefNode engine) {
            return resolveAndTest(spec, value, operand, engine);
        }

        @TruffleBoundary
        private static boolean resolveAndTest(ClassRefSpec spec, Object value, Object operand,
                                              dev.phpruntime.nodes.expr.ClassRefNode engine) {
            PhpClass target = engine.resolveForInstanceof(operand);
            return value instanceof PhpObject obj && target != null && obj.phpClass().isInstanceOf(target);
        }
    }

    /** {@code clone $value}: {@code CloneNode.cloneValue} with this instruction as the error site. */
    @Operation
    public static final class Clone {
        @Specialization
        static Object clone(Object value, @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                            @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                            @Cached("create()") DispatchCallNode dispatch,
                            @Cached("create()") DynamicObject.GetKeyArrayNode keyArray,
                            @Cached("create()") DynamicObject.GetNode propGet,
                            @Cached("create()") DynamicObject.PutNode propPut) {
            return dev.phpruntime.nodes.expr.CloneNode.cloneValue(value, location, dispatch,
                    keyArray, propGet, propPut);
        }
    }

    /** {@code clone($obj, $with)}: clone, run {@code __clone}, then assign the given properties. */
    @Operation
    @ConstantOperand(type = String.class, name = "callingClass")
    @ConstantOperand(type = int.class, name = "traitSelfSlot")
    public static final class CloneWith {
        @Specialization
        static Object clone(VirtualFrame frame, String callingClass, int traitSelfSlot, Object value, Object with,
                            @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                            @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                            @Cached("create()") DispatchCallNode dispatch,
                            @Cached("create()") DynamicObject.GetKeyArrayNode keyArray,
                            @Cached("create()") DynamicObject.GetNode propGet,
                            @Cached("create()") DynamicObject.PutNode propPut) {
            Object traitSelf = traitSelfSlot >= 0 ? frame.getObject(traitSelfSlot) : null;
            return dev.phpruntime.nodes.expr.CloneNode.cloneValueWith(value, with, location, dispatch,
                    callingClass.isEmpty() ? null : callingClass, traitSelf, keyArray, propGet, propPut);
        }
    }

    /**
     * The piped value on its way into a callable. php always passes it as a plain value, so a
     * first parameter declared by reference is an error rather than an implicit temporary.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class PipeArgument {
        @Specialization
        static Object check(VirtualFrame frame, LocalAccessor calleeLocal, Object value,
                            @Bind BytecodeNode bytecode, @Bind Node node) {
            Object callee = calleeLocal.getObject(bytecode, frame);
            if (takesRef(callee, 0)) {
                throw pipeByRef(callee, node);
            }
            return value;
        }

        @TruffleBoundary
        private static PhpError pipeByRef(Object callee, Node node) {
            Builtins.Def builtin = builtinCallee(callee);
            String function;
            String param;
            if (builtin != null) {
                function = builtin.name();
                dev.phpruntime.builtins.BuiltinParams.Param[] params =
                        dev.phpruntime.builtins.BuiltinParams.forName(builtin.name());
                param = params != null && params.length > 0 ? params[0].name() : null;
            } else {
                PhpFunctionRootNode fn = signature(callee);
                function = fn != null ? fn.qualifiedCallableName() : "unknown";
                param = fn != null ? fn.paramName(0) : null;
            }
            String suffix = param != null ? " ($" + param + ")" : "";
            return new PhpError(function + "(): Argument #1" + suffix
                    + " could not be passed by reference", node);
        }
    }

    /** Resolves a {@code $f} operand into a {@link ValueCallee}, as {@code CallValueNode} does. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class ResolveCallable {
        @Specialization
        static void resolve(VirtualFrame frame, LocalAccessor calleeLocal, Object targetValue, Object callerThis,
                            @Bind BytecodeNode bytecode, @Bind Node node) {
            // A closure object's callable is fixed for the request. Resolving it here keeps the
            // common $closure(...) call out of the resolver's boundary.
            dev.phpruntime.runtime.PhpObject closure = targetValue instanceof dev.phpruntime.runtime.PhpObject obj
                    && obj.closure() != null ? obj : null;
            dev.phpruntime.runtime.PhpCallable callable =
                    closure != null ? closure.cachedCallable() : null;
            if (callable == null) {
                callable = Ops.resolveCallable(PhpContext.get(node), targetValue, callerThis);
                if (closure != null) {
                    closure.cacheCallable(callable);
                }
            }
            // A magic call collects its arguments into an array, so its target's parameters do
            // not describe the call site (the AST evaluates those arguments as plain values).
            PhpFunctionRootNode sig = callable.kind() == dev.phpruntime.runtime.PhpCallable.Kind.MAGIC_CALL
                    || callable.callTarget() == null ? null
                    : PhpFunctionRootNode.of(callable.callTarget().getRootNode());
            calleeLocal.setObject(bytecode, frame, new ValueCallee(callable, sig));
        }
    }

    /**
     * Resolves the right-hand side of {@code |>}. php's message for a value that is not
     * callable names its type, unlike the older "not of type callable" paths.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class ResolvePipeCallable {
        @Specialization
        static void resolve(VirtualFrame frame, LocalAccessor calleeLocal, Object targetValue, Object callerThis,
                            @Bind BytecodeNode bytecode, @Bind Node node) {
            PhpContext ctx = PhpContext.get(node);
            dev.phpruntime.runtime.PhpCallable callable =
                    dev.phpruntime.runtime.PhpCallable.resolveOrNull(ctx, targetValue, callerThis);
            if (callable == null) {
                throw notCallable(targetValue, node);
            }
            PhpFunctionRootNode sig = callable.kind() == dev.phpruntime.runtime.PhpCallable.Kind.MAGIC_CALL
                    || callable.callTarget() == null ? null
                    : PhpFunctionRootNode.of(callable.callTarget().getRootNode());
            calleeLocal.setObject(bytecode, frame, new ValueCallee(callable, sig));
        }

        @TruffleBoundary
        private static PhpError notCallable(Object value, Node node) {
            if (value instanceof String s) {
                String name = s.startsWith("\\") ? s.substring(1) : s;
                if (name.indexOf("::") > 0) {
                    return new PhpError("Call to undefined method " + name + "()", node);
                }
                return new PhpError("Call to undefined function " + name + "()", node);
            }
            if (value instanceof PhpArray) {
                return new PhpError("Array callback must have exactly two elements", node);
            }
            return new PhpError("Value of type " + PhpOps.typeName(value) + " is not callable", node);
        }
    }

    /** Calls the {@link ValueCallee} written by {@link ResolveCallable}, laying out its frame. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "keepRef")
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class DoValueCall {
        @Specialization
        static Object call(VirtualFrame frame, boolean keepRef, LocalAccessor calleeLocal, @Variadic Object[] args,
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create()") DispatchCallNode dispatch) {
            ValueCallee v = (ValueCallee) calleeLocal.getObject(bytecode, frame);
            dev.phpruntime.runtime.PhpCallable cb = v.callable();
            Object result = switch (cb.kind()) {
                case CLOSURE -> {
                    Object[] frameArgs = new Object[3 + args.length];
                    frameArgs[0] = cb.boundThis();
                    frameArgs[1] = cb.calledClass();
                    frameArgs[2] = cb.closureObj();
                    System.arraycopy(args, 0, frameArgs, 3, args.length);
                    yield dispatch.call(cb.callTarget(), frameArgs,
                            dev.phpruntime.runtime.PhpBacktrace.bindingSite(location, v.sig(), cb.boundThis() instanceof PhpObject, cb.calledClass()));
                }
                case USER_FUNCTION -> dispatch.call(cb.callTarget(), args, location);
                case METHOD -> {
                    boolean trait = cb.traitSelf() != null;
                    int prefix = trait ? 3 : 2;
                    Object[] frameArgs = new Object[prefix + args.length];
                    frameArgs[0] = cb.boundThis();
                    frameArgs[1] = cb.calledClass();
                    if (trait) {
                        frameArgs[2] = cb.traitSelf();
                    }
                    System.arraycopy(args, 0, frameArgs, prefix, args.length);
                    yield dispatch.call(cb.callTarget(), frameArgs, location);
                }
                case MAGIC_CALL -> dispatch.call(cb.callTarget(), new Object[]{
                        cb.boundThis(), cb.calledClass(), cb.magicMethodName(), Ops.magicArgs(args)}, location);
                case BUILTIN -> Ops.callBuiltinValue(cb.builtin(), args, location);
            };
            return Ops.ret(keepRef, result);
        }
    }

    /** A closure creation site: its target and which captures are by reference. */
    public record ClosureSpec(RootCallTarget callTarget, boolean[] byRef) {
        @Override
        public String toString() {
            return "closure";
        }
    }

    /**
     * Creates a closure: operands are {@code $this}, the called class, the trait self and one
     * value or reference cell per capture, as {@code ClosureNode.execute} reads them.
     */
    @Operation
    @ConstantOperand(type = ClosureSpec.class)
    @ConstantOperand(type = boolean.class, name = "bindThis")
    @ConstantOperand(type = boolean.class, name = "hasTraitSlot")
    @ConstantOperand(type = int.class, name = "lexicalClassId")
    public static final class NewClosure {
        @Specialization
        static PhpObject create(ClosureSpec spec, boolean bindThis, boolean hasTraitSlot, int lexicalClassId,
                                Object thisValue, Object calledClassValue, Object traitSelfValue,
                                @Variadic Object[] captureValues, @Bind Node node) {
            PhpContext ctx = PhpContext.get(node);
            Object boundThis = bindThis ? thisValue : PhpNull.INSTANCE;
            PhpClass calledClass = calledClassValue instanceof PhpClass c ? c : null;
            PhpClass traitSelf = hasTraitSlot ? (traitSelfValue instanceof PhpClass c ? c : null)
                    : lexicalClassId >= 0 ? ctx.classById(lexicalClassId) : null;
            boolean[] byRef = spec.byRef();
            Object[] caps = new Object[byRef.length];
            for (int i = 0; i < byRef.length; i++) {
                caps[i] = byRef[i] ? captureValues[i]
                        : (captureValues[i] == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(captureValues[i]));
            }
            return ctx.newClosure(PhpClosure.closure(spec.callTarget(), boundThis, calledClass, traitSelf, caps));
        }
    }

    // ---- 17c: remaining lvalues

    /** The source of a destructuring assignment: a value, or a live array reached through a cell. */
    public record DestructureState(Object source, PhpReference container, boolean ref, Object result,
                                   boolean copyResult) {
        static DestructureState value(Object v) {
            return new DestructureState(v, null, false, v, false);
        }

        static DestructureState valueCopied(Object v) {
            return new DestructureState(v, null, false, v, true);
        }

        static DestructureState ref(PhpReference cell) {
            return new DestructureState(null, cell, true, null, true);
        }
    }

    /** One element of a destructuring assignment: a cell to bind, or a value to write. */
    public record DestructureItem(PhpReference cell, Object value) {
    }

    /** Evaluated place keys, with the holder of an index path rooted in a bytecode local. */
    public record PreparedPlace(Object[] keys, Object[] holder) {
    }

    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    public static final class PreparedReadIfSet {
        @Specialization
        static Object read(VirtualFrame frame, PlaceSpec spec, Object prepared,
                           @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            PreparedPlace p = (PreparedPlace) prepared;
            return Places.run(frame, engine, p.keys(), P_READ_IF_SET, 0, null);
        }
    }

    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    public static final class PreparedWrite {
        @Specialization
        static Object write(VirtualFrame frame, PlaceSpec spec, Object prepared, Object value,
                            @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            PreparedPlace p = (PreparedPlace) prepared;
            return Places.run(frame, engine, p.keys(), P_WRITE, 0, value);
        }
    }

    /** As {@link PreparedWrite}, also storing an index path's updated array back into its local. */
    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    public static final class PreparedVarWrite {
        @Specialization
        static Object write(VirtualFrame frame, PlaceSpec spec, LocalAccessor acc, VarInfo info, Object prepared,
                            Object value, @Bind BytecodeNode bytecode,
                            @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            PreparedPlace p = (PreparedPlace) prepared;
            Object result = Places.run(frame, engine, p.keys(), P_WRITE, 0, value);
            if (p.holder() != null && p.holder()[0] != HOLDER_EMPTY) {
                store(frame, acc, info, bytecode, p.holder()[0]);
            }
            return result;
        }
    }

    @Operation
    @ConstantOperand(type = String.class, name = "describe")
    public static final class DestructureStartCell {
        @Specialization
        static Object start(String describe, PhpReference cell, @Bind BytecodeNode bytecode,
                            @Bind("$bytecodeIndex") int bci, @Bind Node node) {
            return Ops.destructureStartCell(cell, describe, lineOf(bytecode, bci), node);
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "hasAnyRef")
    public static final class DestructureStartValue {
        @Specialization
        static Object start(boolean hasAnyRef, Object raw, @Bind BytecodeNode bytecode,
                            @Bind("$bytecodeIndex") int bci, @Bind Node node) {
            return Ops.destructureStartValue(raw, hasAnyRef, lineOf(bytecode, bci), node);
        }
    }

    @Operation
    public static final class DestructureResult {
        @Specialization
        static Object result(DestructureState state) {
            return Ops.destructureResult(state);
        }
    }

    @Operation
    public static final class DestructureSkipStep {
        @Specialization
        static Object step(DestructureState state, Object sourceKey) {
            return state;
        }
    }

    @Operation
    @ConstantOperand(type = boolean.class, name = "nestedAnyRef")
    @ConstantOperand(type = boolean.class, name = "byRef")
    public static final class DestructureNestedStep {
        @Specialization
        static Object step(boolean nestedAnyRef, boolean byRef, DestructureState state, Object sourceKey,
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                           @Bind PhpBytecodeRootNode root,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create(root)") ArrayAccessNode access) {
            int line = lineOf(bytecode, bci);
            DestructureItem item;
            if (state.ref() && nestedAnyRef && !byRef) {
                PhpArray array = state.container().getValue() instanceof PhpArray a ? a : null;
                if (array == null) {
                    return DestructureState.value(PhpNull.INSTANCE);
                }
                if (!Ops.destructureHas(array, sourceKey, location)) {
                    Ops.warnDestructureMissing(sourceKey, line, location);
                    return DestructureState.value(PhpNull.INSTANCE);
                }
                item = new DestructureItem(Ops.destructureCell(array, sourceKey, location), null);
            } else {
                item = Ops.destructureItem(null, state, sourceKey, byRef, access, location, line);
            }
            if (item.cell() != null) {
                PhpReference cell = item.cell();
                if (cell.getValue() instanceof PhpArray arr) {
                    PhpArray writable = arr.forWrite();
                    cell.setValue(writable);
                    return DestructureState.ref(cell);
                }
                return DestructureState.value(cell.getValue() == null ? PhpNull.INSTANCE : cell.getValue());
            }
            return DestructureState.value(item.value());
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = boolean.class, name = "byRef")
    public static final class DestructureLocalStep {
        @Specialization
        static Object step(VirtualFrame frame, LocalAccessor acc, VarInfo info, boolean byRef,
                           DestructureState state, Object sourceKey, @Bind BytecodeNode bytecode,
                           @Bind("$bytecodeIndex") int bci, @Bind PhpBytecodeRootNode root,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create(root)") ArrayAccessNode access) {
            // The common step: a value from a plain array into a local. No item or cell is made.
            if (!byRef && !state.ref() && state.source() instanceof PhpArray array) {
                Object fetched = Ops.destructureGet(array, sourceKey, location);
                if (fetched == null) {
                    Ops.warnDestructureMissing(sourceKey, lineOf(bytecode, bci), location);
                    store(frame, acc, info, bytecode, PhpNull.INSTANCE);
                } else {
                    store(frame, acc, info, bytecode, PhpOps.copyOnAssign(fetched));
                }
                return state;
            }
            DestructureItem item = Ops.destructureItem(frame, state, sourceKey, byRef, access, location,
                    lineOf(bytecode, bci));
            if (item.cell() != null) {
                bindCell(frame, acc, info, bytecode, item.cell());
            } else {
                store(frame, acc, info, bytecode, item.value());
            }
            return state;
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = boolean.class, name = "byRef")
    public static final class DestructureVarStep {
        @Specialization
        static Object step(VirtualFrame frame, LocalAccessor acc, VarInfo info, PlaceSpec spec, boolean byRef,
                           DestructureState state, Object sourceKey, @Variadic Object[] ops,
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                           @Bind PhpBytecodeRootNode root,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create(root)") ArrayAccessNode access,
                           @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            DestructureItem item = Ops.destructureItem(frame, state, sourceKey, byRef, access, location,
                    lineOf(bytecode, bci));
            Object[] holder = {HOLDER_EMPTY};
            Object[] keys = Places.varKeys(current(frame, acc, bytecode), holder, ops, spec.arrayKeys);
            Places.destructureStep(frame, engine, keys, item);
            if (holder[0] != HOLDER_EMPTY) {
                store(frame, acc, info, bytecode, holder[0]);
            }
            return state;
        }
    }

    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = boolean.class, name = "byRef")
    public static final class DestructureObjStep {
        @Specialization
        static Object step(VirtualFrame frame, PlaceSpec spec, boolean byRef, DestructureState state,
                           Object sourceKey, @Variadic Object[] ops, @Bind BytecodeNode bytecode,
                           @Bind("$bytecodeIndex") int bci, @Bind PhpBytecodeRootNode root,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create(root)") ArrayAccessNode access,
                           @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            DestructureItem item = Ops.destructureItem(frame, state, sourceKey, byRef, access, location,
                    lineOf(bytecode, bci));
            Places.destructureStep(frame, engine, Places.objKeys(ops, spec.arrayKeys, spec.baseCount), item);
            return state;
        }
    }

    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = boolean.class, name = "byRef")
    public static final class DestructureGlobalStep {
        @Specialization
        static Object step(VirtualFrame frame, PlaceSpec spec, boolean byRef, DestructureState state,
                           Object sourceKey, @Variadic Object[] ops, @Bind BytecodeNode bytecode,
                           @Bind("$bytecodeIndex") int bci, @Bind PhpBytecodeRootNode root,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create(root)") ArrayAccessNode access,
                           @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            DestructureItem item = Ops.destructureItem(frame, state, sourceKey, byRef, access, location,
                    lineOf(bytecode, bci));
            Places.destructureStep(frame, engine, Places.globalKeys(ops, spec.arrayKeys), item);
            return state;
        }
    }

    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = boolean.class, name = "byRef")
    public static final class DestructureStaticStep {
        @Specialization
        static Object step(VirtualFrame frame, PlaceSpec spec, boolean byRef, DestructureState state,
                           Object sourceKey, @Variadic Object[] ops, @Bind BytecodeNode bytecode,
                           @Bind("$bytecodeIndex") int bci, @Bind PhpBytecodeRootNode root,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create(root)") ArrayAccessNode access,
                           @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            DestructureItem item = Ops.destructureItem(frame, state, sourceKey, byRef, access, location,
                    lineOf(bytecode, bci));
            Places.destructureStep(frame, engine,
                    Places.staticKeys(ops, spec.arrayKeys, spec.baseCount), item);
            return state;
        }
    }

    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = boolean.class, name = "byRef")
    public static final class DestructureGlobalsStep {
        @Specialization
        static Object step(VirtualFrame frame, PlaceSpec spec, boolean byRef, DestructureState state,
                           Object sourceKey, @Variadic Object[] ops, @Bind BytecodeNode bytecode,
                           @Bind("$bytecodeIndex") int bci, @Bind PhpBytecodeRootNode root,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create(root)") ArrayAccessNode access,
                           @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            DestructureItem item = Ops.destructureItem(frame, state, sourceKey, byRef, access, location,
                    lineOf(bytecode, bci));
            Places.destructureStep(frame, engine,
                    Places.globalsElementKeys(ops, spec.baseCount, spec.arrayKeys), item);
            return state;
        }
    }

    // ---- 17d: last coverage

    // ------------------------------------------------------------------ named calls without spread

    /** Starts a named call's compact argument buffer and resolves this callee's binding plan. */
    @Operation
    @ConstantOperand(type = NamedArgs.Spec.class)
    public static final class StartNamedArgs {
        @Specialization
        static Object start(NamedArgs.Spec spec, Object callee,
                            @Cached("create()") NamedArgs.Cache cache,
                            @Cached("create()") NamedArgs.PlanProfile profile) {
            return new NamedArgs.Buffer(spec, profile.profile(cache.planFor(callee, spec)));
        }
    }

    /** One variable argument of a named call, stored at its constant call-site index. */
    @Operation
    @ConstantOperand(type = int.class, name = "index")
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    @ConstantOperand(type = LocalAccessor.class, name = "args")
    public static final class NamedVarArg {
        @Specialization
        static void add(VirtualFrame frame, int index, String name, LocalAccessor acc, VarInfo info,
                        LocalAccessor calleeLocal, LocalAccessor argsLocal, @Bind BytecodeNode bytecode,
                        @Bind("$bytecodeIndex") int bci, @Bind Node node,
                        @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                        @Cached("create()") NamedArgs.PlanProfile profile) {
            Object callee = calleeLocal.getObject(bytecode, frame);
            NamedArgs.Buffer args = (NamedArgs.Buffer) argsLocal.getObject(bytecode, frame);
            NamedArgs.Plan plan = profile.profile(args.plan);
            int mode = plan == null ? -1 : plan.mode[index];
            boolean duplicate = (plan != null ? plan.spec : args.spec).duplicate(index);
            if (mode < 0) {
                mode = NamedArgs.genericMode(callee, name.isEmpty() ? null : name, index);
            }
            if (mode == NamedArgs.MODE_REF || (mode == NamedArgs.MODE_BUILTIN_REF && plan != null)) {
                Object cell = "this".equals(info.name())
                        ? new PhpReference(ReadVar.read(frame, acc, info, bytecode, bci, node))
                        : ObtainVarCell.obtain(frame, acc, info, bytecode);
                if (duplicate) {
                    NamedArgs.throwDuplicate(name, location);
                }
                args.setValue(index, cell);
                return;
            }
            if (mode == NamedArgs.MODE_MAGIC) {
                Object v = ReadVar.read(frame, acc, info, bytecode, bci, node);
                if (duplicate) {
                    NamedArgs.throwDuplicate(name, location);
                }
                args.setValue(index, PhpOps.copyOnAssign(v));
                return;
            }
            Object v = current(frame, acc, bytecode);
            v = v == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(v);
            if (duplicate) {
                NamedArgs.throwDuplicate(name, location);
            }
            args.setValue(index, v);
            if (mode == NamedArgs.MODE_BUILTIN_REF) {
                args.setRef(index, localLValue(acc, info, bytecode), dev.phpruntime.nodes.var.LValueNode.NO_KEYS);
            }
        }

        /** Built behind a boundary: a megamorphic site's callee is not a constant. */
        @TruffleBoundary
        private static LocalLValue localLValue(LocalAccessor acc, VarInfo info, BytecodeNode bytecode) {
            return new LocalLValue(acc, info, bytecode);
        }
    }

    /** One place argument of a named call, stored at its constant call-site index. */
    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = int.class, name = "index")
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    @ConstantOperand(type = LocalAccessor.class, name = "args")
    public static final class NamedPlaceArg {
        @Specialization
        static void add(VirtualFrame frame, PlaceSpec spec, int index, String name, LocalAccessor calleeLocal,
                        LocalAccessor argsLocal, Object prepared, @Bind BytecodeNode bytecode,
                        @Bind("$bytecodeIndex") int bci, @Bind Node node,
                        @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                        @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine,
                        @Cached("create()") NamedArgs.PlanProfile profile) {
            addPlaceArg(frame, spec, index, name, calleeLocal, argsLocal, prepared, bytecode, bci, node,
                    location, engine, profile, null, null);
        }
    }

    /**
     * A place argument whose base is a local variable. An index path copies the array value out
     * of the local, so a write that creates the element reports the updated array in the
     * prepared place's holder; it has to be stored back, as the positional path does.
     */
    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = int.class, name = "index")
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    @ConstantOperand(type = LocalAccessor.class, name = "args")
    @ConstantOperand(type = LocalAccessor.class, name = "local")
    @ConstantOperand(type = VarInfo.class, name = "localInfo")
    public static final class NamedVarPlaceArg {
        @Specialization
        static void add(VirtualFrame frame, PlaceSpec spec, int index, String name, LocalAccessor calleeLocal,
                        LocalAccessor argsLocal, LocalAccessor local, VarInfo localInfo, Object prepared,
                        @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci, @Bind Node node,
                        @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                        @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine,
                        @Cached("create()") NamedArgs.PlanProfile profile) {
            addPlaceArg(frame, spec, index, name, calleeLocal, argsLocal, prepared, bytecode, bci, node,
                    location, engine, profile, local, localInfo);
        }
    }

    private static void addPlaceArg(VirtualFrame frame, PlaceSpec spec, int index, String name,
                                    LocalAccessor calleeLocal, LocalAccessor argsLocal, Object prepared,
                                    BytecodeNode bytecode, int bci, Node node, SiteNode location,
                                    dev.phpruntime.nodes.var.LValueNode engine, NamedArgs.PlanProfile profile,
                                    LocalAccessor local, VarInfo localInfo) {
        Object callee = calleeLocal.getObject(bytecode, frame);
        NamedArgs.Buffer args = (NamedArgs.Buffer) argsLocal.getObject(bytecode, frame);
        PreparedPlace p = (PreparedPlace) prepared;
        Object[] keys = p.keys();
        NamedArgs.Plan plan = profile.profile(args.plan);
        int mode = plan == null ? -1 : plan.mode[index];
        boolean duplicate = (plan != null ? plan.spec : args.spec).duplicate(index);
        if (mode < 0) {
            mode = NamedArgs.genericMode(callee, name.isEmpty() ? null : name, index);
        }
        if (mode == NamedArgs.MODE_REF) {
            if (spec.template() instanceof dev.phpruntime.nodes.var.GlobalVarNode gv
                    && gv.isGlobalsSuperglobal()) {
                throw NamedArgs.globalsByRef(args, callee, index, name.isEmpty() ? null : name, location);
            }
            Object cell = engine.obtainCell(frame, keys);
            storeHolder(frame, local, localInfo, p, bytecode);
            if (duplicate) {
                NamedArgs.throwDuplicate(name, location);
            }
            args.setValue(index, cell);
            return;
        }
        if (mode == NamedArgs.MODE_BUILTIN_REF && plan != null) {
            Object cell = engine.obtainCell(frame, keys);
            storeHolder(frame, local, localInfo, p, bytecode);
            if (duplicate) {
                NamedArgs.throwDuplicate(name, location);
            }
            args.setValue(index, cell);
            return;
        }
        if (mode == NamedArgs.MODE_MAGIC) {
            Object v = engine.readValue(frame, keys);
            storeHolder(frame, local, localInfo, p, bytecode);
            if (duplicate) {
                NamedArgs.throwDuplicate(name, location);
            }
            args.setValue(index, PhpOps.copyOnAssign(v));
            return;
        }
        Object v = engine.read(frame, keys);
        storeHolder(frame, local, localInfo, p, bytecode);
        v = v == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(v);
        if (duplicate) {
            NamedArgs.throwDuplicate(name, location);
        }
        args.setValue(index, v);
        if (mode == NamedArgs.MODE_BUILTIN_REF) {
            args.setRef(index, engine, keys);
        }
    }

    /** Writes an index path's updated array back into the local it was read from. */
    private static void storeHolder(VirtualFrame frame, LocalAccessor local, VarInfo localInfo,
                                    PreparedPlace prepared, BytecodeNode bytecode) {
        if (local != null && prepared.holder() != null && prepared.holder()[0] != HOLDER_EMPTY) {
            store(frame, local, localInfo, bytecode, prepared.holder()[0]);
        }
    }

    /** One plain expression argument of a named call. */
    @Operation
    @ConstantOperand(type = int.class, name = "index")
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = int.class, name = "kind")
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    @ConstantOperand(type = LocalAccessor.class, name = "args")
    public static final class NamedValueArg {
        @Specialization
        static void add(VirtualFrame frame, int index, String name, int kind, LocalAccessor calleeLocal,
                        LocalAccessor argsLocal, Object value, @Bind BytecodeNode bytecode,
                        @Bind("$bytecodeIndex") int bci, @Bind Node node,
                        @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                        @Cached("create()") NamedArgs.PlanProfile profile) {
            Object callee = calleeLocal.getObject(bytecode, frame);
            NamedArgs.Buffer args = (NamedArgs.Buffer) argsLocal.getObject(bytecode, frame);
            PhpReference returned = value instanceof PhpReference r ? r : null;
            if (returned != null) {
                value = CallArgHelper.unwrapReturn(returned);
            }
            NamedArgs.Plan plan = profile.profile(args.plan);
            int mode = plan == null ? -1 : plan.mode[index];
            boolean duplicate = (plan != null ? plan.spec : args.spec).duplicate(index);
            if (mode < 0) {
                mode = NamedArgs.genericMode(callee, name.isEmpty() ? null : name, index);
            }
            Object v;
            if (mode == NamedArgs.MODE_REF) {
                if (returned != null) {
                    // A by-reference return becomes the argument cell only where the callee
                    // declares the parameter by reference in guest code; an internal method
                    // takes the value, as the positional path does.
                    if ((plan != null && plan.kind == NamedArgs.KIND_INTERNAL)
                            || (plan == null && NamedArgs.isInternalCallee(callee))) {
                        v = PhpOps.copyOnAssign(value);
                    } else {
                        returned.ensureDefined();
                        v = returned;
                    }
                } else {
                    v = NamedArgs.valueToRef(value, kind, args, callee, index,
                            name.isEmpty() ? null : name, location, lineOf(bytecode, bci));
                }
            } else if (mode == NamedArgs.MODE_BUILTIN_TEMP) {
                if (kind == NamedArgs.VALUE_CALL) {
                    NamedArgs.onlyVariablesNotice(location, lineOf(bytecode, bci));
                }
                v = new PhpReference(PhpOps.copyOnAssign(value));
            } else {
                v = PhpOps.copyOnAssign(value);
            }
            if (duplicate) {
                NamedArgs.throwDuplicate(name, location);
            }
            args.setValue(index, v);
        }
    }

    /** {@code f(name: v)} without spread: bind from the plan and call. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class DoNamedFunction {
        @Specialization
        static Object builtin(VirtualFrame frame, boolean keepRef, Builtins.Def builtin, Object argsObj,
                              @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                              @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                              @Cached("create()") NamedArgs.PlanProfile profile) {
            NamedArgs.Buffer buffer = (NamedArgs.Buffer) argsObj;
            NamedArgs.Plan plan = profile.profile(buffer.plan);
            if (plan != null) {
                if (plan.hasError()) {
                    plan.throwError(location);
                }
                return NamedArgs.callBuiltin(builtin, buffer, plan, location);
            }
            return Ops.callComplexBuiltinBoundary(frame.materialize(), builtin, NamedArgs.toRaw(buffer), location);
        }

        @Specialization
        static Object call(VirtualFrame frame, boolean keepRef, PhpFunction fn, Object argsObj,
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create()") DispatchCallNode dispatch,
                           @Cached("create()") NamedArgs.PlanProfile profile) {
            NamedArgs.Buffer buffer = (NamedArgs.Buffer) argsObj;
            NamedArgs.Plan plan = profile.profile(buffer.plan);
            Object[] args;
            if (plan != null) {
                if (plan.hasError()) {
                    plan.throwError(location);
                }
                args = NamedArgs.bindArgs(plan, null, null, null, buffer.values);
            } else {
                args = genericFunctionArgs(fn, buffer, location);
            }
            return Ops.ret(keepRef, dispatch.call(fn.callTarget(), args, location));
        }

        @TruffleBoundary
        private static Object[] genericFunctionArgs(PhpFunction fn, NamedArgs.Buffer buffer, SiteNode location) {
            PhpFunctionRootNode fnRoot = PhpFunctionRootNode.of(fn.callTarget().getRootNode()) instanceof PhpFunctionRootNode r ? r : null;
            return CallArgHelper.bindToFunction(new Object[0], NamedArgs.toRaw(buffer), fnRoot, location);
        }
    }

    /** {@code $obj->m(name: v)} / {@code C::m(name: v)} without spread. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class DoNamedMethod {
        @Specialization
        static Object call(VirtualFrame frame, boolean keepRef, Object callee, Object argsObj,
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create()") DispatchCallNode dispatch,
                           @Cached("create()") NamedArgs.PlanProfile profile) {
            MethodCallee m = (MethodCallee) callee;
            NamedArgs.Buffer buffer = (NamedArgs.Buffer) argsObj;
            if (m.entry() == null) {
                return Ops.ret(keepRef, dispatch.call(m.magic(), new Object[]{
                        m.obj(), m.cls(), m.name(), NamedArgs.magicArray(buffer)}, location));
            }
            Object[] args = methodArgs(m, buffer, profile.profile(buffer.plan), location);
            return Ops.ret(keepRef, dispatch.callMethod(m.entry(), args, location, m.cls()));
        }

        /**
         * The argument array for a method or constructor, from the plan or, at a megamorphic
         * site, from the generic binder. Kept apart from the dispatch so the callee is called
         * (and inlined) from one place only.
         */
        static Object[] methodArgs(MethodCallee m, NamedArgs.Buffer buffer, NamedArgs.Plan plan, SiteNode location) {
            if (plan != null) {
                if (plan.hasError()) {
                    plan.throwError(location);
                }
                return NamedArgs.bindArgs(plan, m.thisArg(), m.cls(), null, buffer.values);
            }
            return genericMethodArgs(m, buffer, location);
        }

        @TruffleBoundary
        private static Object[] genericMethodArgs(MethodCallee m, NamedArgs.Buffer buffer, SiteNode location) {
            CallArgHelper.RawCallArgs raw = NamedArgs.toRaw(buffer);
            PhpFunctionRootNode fnRoot = PhpFunctionRootNode.of(m.sigTarget().getRootNode()) instanceof PhpFunctionRootNode r ? r : null;
            return fnRoot != null
                    ? CallArgHelper.bindToFunction(new Object[]{m.thisArg(), m.cls()}, raw, fnRoot, location)
                    : CallArgHelper.bindToInternal(new Object[]{m.thisArg(), m.cls()}, raw,
                            m.entry(), location);
        }
    }

    /** {@code new C(name: v)} without spread. */
    @Operation
    public static final class DoNamedConstructor {
        @Specialization
        static Object call(VirtualFrame frame, Object object, Object callee, Object argsObj,
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create()") DispatchCallNode dispatch,
                           @Cached("create()") NamedArgs.PlanProfile profile) {
            PhpObject obj = (PhpObject) object;
            NamedArgs.Buffer buffer = (NamedArgs.Buffer) argsObj;
            if (callee instanceof MethodCallee m) {
                dispatch.callMethod(m.entry(), DoNamedMethod.methodArgs(m, buffer, profile.profile(buffer.plan), location), location, m.cls());
            } else {
                String name = buffer.spec.firstName();
                if (name != null) {
                    CompilerDirectives.transferToInterpreter();
                    throw new PhpError("Unknown named parameter $" + name, location);
                }
            }
            return obj;
        }
    }

    /** {@code $f(name: v)} without spread. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class DoNamedValue {
        @Specialization
        static Object call(VirtualFrame frame, boolean keepRef, Object callee, Object argsObj,
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create()") DispatchCallNode dispatch,
                           @Cached("create()") NamedArgs.PlanProfile profile) {
            ValueCallee v = (ValueCallee) callee;
            dev.phpruntime.runtime.PhpCallable cb = v.callable();
            NamedArgs.Buffer buffer = (NamedArgs.Buffer) argsObj;
            NamedArgs.Plan plan = profile.profile(buffer.plan);
            if (cb.kind() == dev.phpruntime.runtime.PhpCallable.Kind.BUILTIN) {
                if (plan == null) {
                    return Ops.ret(keepRef, Ops.callComplexBuiltinBoundary(frame.materialize(), cb.builtin(),
                            NamedArgs.toRaw(buffer), location));
                }
                if (plan.hasError()) {
                    plan.throwError(location);
                }
                return Ops.ret(keepRef, NamedArgs.callBuiltin(cb.builtin(), buffer, plan, location));
            }
            // Every guest kind builds its argument array first and then shares one dispatch,
            // so the callee is not inlined once per kind.
            Object[] args = switch (cb.kind()) {
                case CLOSURE -> namedValueArgs(plan, buffer, 3, cb.boundThis(), cb.calledClass(),
                        cb.closureObj(), v.sig(), null, location);
                case USER_FUNCTION -> namedValueArgs(plan, buffer, 0, null, null, null, v.sig(), null, location);
                case METHOD -> namedValueArgs(plan, buffer, cb.traitSelf() != null ? 3 : 2, cb.boundThis(),
                        cb.calledClass(), cb.traitSelf(), v.sig(), cb, location);
                case MAGIC_CALL -> new Object[]{
                        cb.boundThis(), cb.calledClass(), cb.magicMethodName(), NamedArgs.magicArray(buffer)};
                default -> throw CompilerDirectives.shouldNotReachHere();
            };
            return Ops.ret(keepRef, dispatch.call(cb.callTarget(), args,
                    dev.phpruntime.runtime.PhpBacktrace.bindingSite(location, v.sig(), cb.boundThis() instanceof PhpObject, cb.calledClass())));
        }

        /** {@code method} is the callable when it is a method (it may be Java-implemented), else null. */
        private static Object[] namedValueArgs(NamedArgs.Plan plan, NamedArgs.Buffer buffer, int prefix,
                                               Object a, Object b, Object c, PhpFunctionRootNode fnRoot,
                                               dev.phpruntime.runtime.PhpCallable method, SiteNode location) {
            if (plan != null) {
                if (plan.hasError()) {
                    plan.throwError(location);
                }
                return NamedArgs.bindArgs(plan, a, b, c, buffer.values);
            }
            return genericValueArgs(buffer, prefix, a, b, c, fnRoot, method, location);
        }

        @TruffleBoundary
        private static Object[] genericValueArgs(NamedArgs.Buffer buffer, int prefix, Object a, Object b, Object c,
                                                 PhpFunctionRootNode fnRoot, dev.phpruntime.runtime.PhpCallable method,
                                                 SiteNode location) {
            Object[] prefixArgs = switch (prefix) {
                case 3 -> new Object[]{a, b, c};
                case 2 -> new Object[]{a, b};
                default -> new Object[0];
            };
            CallArgHelper.RawCallArgs raw = NamedArgs.toRaw(buffer);
            if (fnRoot == null && method != null && method.methodEntry() != null) {
                // A Java-implemented method has no guest signature; it declares its names instead.
                return CallArgHelper.bindToInternal(prefixArgs, raw, method.methodEntry(), location);
            }
            return CallArgHelper.bindToFunction(prefixArgs, raw, fnRoot, location);
        }
    }

    /** A fresh mutable argument list for one call with spread or named arguments. */
    @Operation
    public static final class NewRawArgs {
        @Specialization
        static Object create() {
            return CallArgHelper.newRawCallArgs();
        }
    }

    /** One variable argument of a spread/named call, evaluated against the resolved callee. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = boolean.class, name = "spread")
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    @ConstantOperand(type = LocalAccessor.class, name = "raw")
    public static final class RawVarArg {
        @Specialization
        static void add(VirtualFrame frame, LocalAccessor acc, VarInfo info, String name, boolean spread,
                        LocalAccessor calleeLocal, LocalAccessor rawLocal, @Bind BytecodeNode bytecode,
                        @Bind("$bytecodeIndex") int bci, @Bind Node node,
                        @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location) {
            Object callee = calleeLocal.getObject(bytecode, frame);
            CallArgHelper.RawCallArgs raw = (CallArgHelper.RawCallArgs) rawLocal.getObject(bytecode, frame);
            Ops.addRawVarArgBoundary(frame.materialize(), raw, callee, acc, info, name.isEmpty() ? null : name,
                    spread, bytecode, bci, node, location);
        }
    }

    /** One place argument of a spread/named call; its keys are already evaluated. */
    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = boolean.class, name = "spread")
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    @ConstantOperand(type = LocalAccessor.class, name = "raw")
    public static final class RawPlaceArg {
        @Specialization
        static void add(VirtualFrame frame, PlaceSpec spec, String name, boolean spread,
                        LocalAccessor calleeLocal, LocalAccessor rawLocal, Object prepared,
                        @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci, @Bind Node node,
                        @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                        @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            Object callee = calleeLocal.getObject(bytecode, frame);
            CallArgHelper.RawCallArgs raw = (CallArgHelper.RawCallArgs) rawLocal.getObject(bytecode, frame);
            Ops.addRawPlaceArgBoundary(frame.materialize(), raw, callee, engine, spec, prepared,
                    name.isEmpty() ? null : name, spread, location);
        }
    }

    /** One plain expression argument of a spread/named call. */
    @Operation
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = boolean.class, name = "spread")
    @ConstantOperand(type = int.class, name = "kind")
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    @ConstantOperand(type = LocalAccessor.class, name = "raw")
    public static final class RawValueArg {
        @Specialization
        static void add(VirtualFrame frame, String name, boolean spread, int kind, LocalAccessor calleeLocal,
                        LocalAccessor rawLocal, Object value, @Bind BytecodeNode bytecode,
                        @Cached(value = "create(bytecode, $bytecodeIndex)", neverDefault = true) SiteNode location) {
            Object callee = calleeLocal.getObject(bytecode, frame);
            CallArgHelper.RawCallArgs raw = (CallArgHelper.RawCallArgs) rawLocal.getObject(bytecode, frame);
            Ops.addRawValueArgBoundary(raw, callee, value, kind, name.isEmpty() ? null : name, spread, location);
        }
    }

    /** {@code f(...)}: bind the evaluated raw arguments to the function and call it. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class DoComplexFunction {
        @Specialization
        static Object call(VirtualFrame frame, boolean keepRef, Object callee, Object rawArgs,
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create()") DispatchCallNode dispatch) {
            CallArgHelper.RawCallArgs raw = (CallArgHelper.RawCallArgs) rawArgs;
            Builtins.Def builtin = builtinCallee(callee);
            if (builtin != null) {
                return Ops.callComplexBuiltinBoundary(frame.materialize(), builtin, raw, location);
            }
            PhpFunction fn = (PhpFunction) callee;
            PhpFunctionRootNode fnRoot = PhpFunctionRootNode.of(fn.callTarget().getRootNode()) instanceof PhpFunctionRootNode r ? r : null;
            Object[] args = CallArgHelper.bindToFunction(new Object[0], raw, fnRoot, location);
            return Ops.ret(keepRef, dispatch.call(fn.callTarget(), args, location));
        }
    }

    /** {@code $obj->m(...)} / {@code C::m(...)} with spread or named arguments. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class DoComplexMethod {
        @Specialization
        static Object call(VirtualFrame frame, boolean keepRef, Object callee, Object rawArgs,
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create()") DispatchCallNode dispatch) {
            MethodCallee m = (MethodCallee) callee;
            CallArgHelper.RawCallArgs raw = (CallArgHelper.RawCallArgs) rawArgs;
            if (m.entry() == null) {
                return Ops.ret(keepRef, dispatch.call(m.magic(), new Object[]{
                        m.obj(), m.cls(), m.name(), CallArgHelper.magicArray(raw)}, location));
            }
            PhpFunctionRootNode fnRoot = PhpFunctionRootNode.of(m.sigTarget().getRootNode()) instanceof PhpFunctionRootNode r ? r : null;
            Object[] args = fnRoot != null
                    ? CallArgHelper.bindToFunction(new Object[]{m.thisArg(), m.cls()}, raw, fnRoot, location)
                    : CallArgHelper.bindToInternal(new Object[]{m.thisArg(), m.cls()}, raw,
                            m.entry(), location);
            return Ops.ret(keepRef, dispatch.callMethod(m.entry(), args, location, m.cls()));
        }
    }

    /** {@code new C(...)} with spread or named constructor arguments. */
    @Operation
    public static final class DoComplexConstructor {
        @Specialization
        static Object call(VirtualFrame frame, Object object, Object callee, Object rawArgs,
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create()") DispatchCallNode dispatch) {
            PhpObject obj = (PhpObject) object;
            CallArgHelper.RawCallArgs raw = (CallArgHelper.RawCallArgs) rawArgs;
            if (callee instanceof MethodCallee m) {
                PhpFunctionRootNode fnRoot = PhpFunctionRootNode.of(m.sigTarget().getRootNode()) instanceof PhpFunctionRootNode r ? r : null;
                Object[] args = fnRoot != null
                        ? CallArgHelper.bindToFunction(new Object[]{m.thisArg(), m.cls()}, raw, fnRoot, location)
                        : CallArgHelper.bindToInternal(new Object[]{m.thisArg(), m.cls()}, raw,
                                m.entry(), location);
                dispatch.callMethod(m.entry(), args, location, m.cls());
            } else if (raw.named() != null && !raw.named().isEmpty()) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Unknown named parameter $" + CallArgHelper.firstKey(raw.named()), location);
            }
            return obj;
        }
    }

    /** {@code $f(...)} with spread or named arguments. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class DoComplexValue {
        @Specialization
        static Object call(VirtualFrame frame, boolean keepRef, Object callee, Object rawArgs,
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create()") DispatchCallNode dispatch) {
            ValueCallee v = (ValueCallee) callee;
            dev.phpruntime.runtime.PhpCallable cb = v.callable();
            CallArgHelper.RawCallArgs raw = (CallArgHelper.RawCallArgs) rawArgs;
            PhpFunctionRootNode fnRoot = v.sig();
            Object result = switch (cb.kind()) {
                case CLOSURE -> dispatch.call(cb.callTarget(), CallArgHelper.bindToFunction(
                        new Object[]{cb.boundThis(), cb.calledClass(), cb.closureObj()}, raw, fnRoot, location),
                        dev.phpruntime.runtime.PhpBacktrace.bindingSite(location, fnRoot, cb.boundThis() instanceof PhpObject, cb.calledClass()));
                case USER_FUNCTION -> dispatch.call(cb.callTarget(),
                        CallArgHelper.bindToFunction(new Object[0], raw, fnRoot, location), location);
                case METHOD -> {
                    Object[] prefix = cb.traitSelf() != null
                            ? new Object[]{cb.boundThis(), cb.calledClass(), cb.traitSelf()}
                            : new Object[]{cb.boundThis(), cb.calledClass()};
                    yield dispatch.call(cb.callTarget(), CallArgHelper.bindToFunction(prefix, raw, fnRoot, location), location);
                }
                case MAGIC_CALL -> dispatch.call(cb.callTarget(), new Object[]{
                        cb.boundThis(), cb.calledClass(), cb.magicMethodName(), CallArgHelper.magicArray(raw)}, location);
                case BUILTIN -> Ops.callComplexBuiltinBoundary(frame.materialize(), cb.builtin(), raw, location);
            };
            return Ops.ret(keepRef, result);
        }
    }

    /** Whether a resolved callee is the named builtin (namespaced {@code assert}). */
    @Operation
    @ConstantOperand(type = String.class, name = "builtinName")
    public static final class IsBuiltinNamed {
        @Specialization
        static boolean test(String builtinName, Object callee) {
            return callee instanceof Builtins.Def def && def.name().equals(builtinName);
        }
    }

    /** A prepared place's current value as {@code LValueNode.read}: no warning for a miss. */
    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    public static final class PreparedReadNullable {
        @Specialization
        static Object read(VirtualFrame frame, PlaceSpec spec, Object prepared,
                           @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            return engine.read(frame, ((PreparedPlace) prepared).keys());
        }
    }

    /** {@code extract($place, EXTR_REFS)}: make writable, write back, then bind the names. */
    @Operation
    @ConstantOperand(type = ExtractSpec.class)
    @ConstantOperand(type = PlaceSpec.class, name = "place")
    public static final class ExtractRefs {
        @Specialization
        static Object run(VirtualFrame frame, ExtractSpec spec, PlaceSpec placeSpec, Object table, Object array,
                          Object flags, Object prefix, Object prepared,
                          @Cached("spec.newEngine()") dev.phpruntime.nodes.expr.ExtractNode engine,
                          @Cached("placeSpec.newEngine()") dev.phpruntime.nodes.var.LValueNode placeEngine) {
            dev.phpruntime.nodes.expr.ExtractNode.Prepared p = engine.prepare(array, flags, prefix);
            if (p.refs()) {
                placeEngine.write(frame, ((PreparedPlace) prepared).keys(), p.input());
            }
            return engine.extractPrepared(table, p);
        }
    }

    /** {@code __CLASS__} inside a trait method: the using class's name. */
    @Operation
    public static final class TraitClassName {
        @Specialization
        static String name(Object traitSelf) {
            return dev.phpruntime.nodes.expr.TraitClassNameNode.nameOf(traitSelf);
        }
    }

    /** {@code match} with no matching arm and no default. */
    @Operation
    public static final class UnhandledMatch {
        @Specialization
        static Object fail(Object subject) {
            CompilerDirectives.transferToInterpreter();
            throw dev.phpruntime.nodes.expr.MatchNode.unhandled(subject, null);
        }
    }

    /** {@code strlen(...)}: a closure for a function or builtin name. */
    @Operation
    @ConstantOperand(type = String.class, name = "nsName")
    @ConstantOperand(type = int.class, name = "nsFunctionId")
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = int.class, name = "functionId")
    public static final class FirstClassFunction {
        @Specialization
        static Object create(String nsName, int nsFunctionId, String name, int functionId, @Bind Node node,
                             @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                             @Cached(value = "lookup(name)", neverDefault = false) Builtins.Def builtin,
                             @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location) {
            return FirstClassCallableNode.createFunction(PhpContext.get(node),
                    nsName.isEmpty() ? null : nsName, nsFunctionId, name, functionId, builtin, location);
        }

        @TruffleBoundary
        static Builtins.Def lookup(String name) {
            return Builtins.lookup(name);
        }
    }

    /** {@code $obj->method(...)}: a closure bound to the receiver. */
    @Operation
    @ConstantOperand(type = MethodSite.class)
    public static final class FirstClassMethod {
        @Specialization
        static Object create(MethodSite site, Object target, Object nameValue, @Bind Node node,
                             @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                             @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location) {
            String name = site.name() != null ? site.name() : Ops.toStr(nameValue);
            return FirstClassCallableNode.createMethod(PhpContext.get(node), target, name, site.methodId(), location);
        }
    }

    /** {@code Foo::method(...)}: a closure, with the AST's forwarding rules. */
    @Operation
    @ConstantOperand(type = MethodSite.class)
    @ConstantOperand(type = boolean.class, name = "forwarding")
    @ConstantOperand(type = boolean.class, name = "hasCalledClass")
    public static final class FirstClassStaticMethod {
        @Specialization
        static Object create(MethodSite site, boolean forwarding, boolean hasCalledClass, PhpClass targetClass,
                             Object nameValue, Object thisValue, Object calledClassValue, @Bind Node node,
                             @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                             @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location) {
            String name = site.name() != null ? site.name() : Ops.toStr(nameValue);
            Object calledArg = forwarding && hasCalledClass ? calledClassValue : targetClass;
            return FirstClassCallableNode.createStaticMethod(PhpContext.get(node), targetClass, name, site.methodId(),
                    forwarding, thisValue, calledArg, location);
        }
    }

    /** {@code $callable(...)}: a closure for any callable value. */
    @Operation
    public static final class FirstClassCallable {
        @Specialization
        static Object create(Object target, Object callerThis, @Bind Node node,
                             @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                             @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location) {
            return dev.phpruntime.runtime.PhpCallable.toClosure(
                    PhpContext.get(node), target, callerThis, location);
        }
    }

    /** {@code func_num_args()}: the frame's positional arguments past the prefix. */
    @Operation
    @ConstantOperand(type = FuncArgsNode.Kind.class, name = "kind")
    @ConstantOperand(type = int.class, name = "argOffset")
    public static final class FuncArgs {
        @Specialization
        static Object run(VirtualFrame frame, FuncArgsNode.Kind kind, int argOffset) {
            return FuncArgsNode.numArgs(argOffset, frame.getArguments(), null);
        }
    }

    /**
     * {@code func_get_args()}/{@code func_get_arg($i)}: {@code current} holds the current
     * values of the declared parameters; {@code index} is the argument index for
     * {@code func_get_arg} and unused for {@code func_get_args}. Arguments past the declared
     * parameters come from the frame.
     */
    @Operation
    @ConstantOperand(type = FuncArgsNode.Kind.class, name = "kind")
    @ConstantOperand(type = int.class, name = "argOffset")
    @ConstantOperand(type = int.class, name = "normalCount")
    public static final class FuncArgsCurrent {
        @Specialization
        static Object run(VirtualFrame frame, FuncArgsNode.Kind kind, int argOffset, int normalCount,
                          PhpArray current, Object index) {
            Object[] values = new Object[normalCount];
            for (int i = 0; i < normalCount; i++) {
                Object value = current.get((long) i);
                values[i] = value == UNDEFINED ? null : value;
            }
            Object[] args = frame.getArguments();
            if (kind == FuncArgsNode.Kind.GET_ARGS) {
                return FuncArgsNode.getArgs(argOffset, normalCount, values, args, null);
            }
            return FuncArgsNode.getArg(argOffset, normalCount, values, args, index, null);
        }
    }

    /** {@code func_get_arg}/{@code func_get_args} in the global scope: php's error, no index run. */
    @Operation
    @ConstantOperand(type = FuncArgsNode.Kind.class, name = "kind")
    public static final class FuncArgsGlobal {
        @Specialization
        static Object fail(FuncArgsNode.Kind kind) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError(kind.phpName + "(): Cannot be called from the global scope");
        }
    }

    // ---- foreach by reference

    /** A {@code foreach} subject that is a plain local: read it, then take its cell if an array. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = String.class, name = "describe")
    @ConstantOperand(type = String.class, name = "declaringClass")
    public static final class ForeachRefStartVar {
        @Specialization
        static Object start(VirtualFrame frame, LocalAccessor acc, VarInfo info, String describe, String declaringClass,
                            Object traitSelf, @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                            @Bind Node node) {
            Object cur = current(frame, acc, bytecode);
            if (cur == null) {
                PhpContext.get(node).warning("Undefined variable " + describe, lineOf(bytecode, bci));
                cur = PhpNull.INSTANCE;
            }
            if (!(cur instanceof PhpArray)) {
                return Ops.refStartValue(cur, traitSelf, declaringClass, lineOf(bytecode, bci), node);
            }
            PhpReference cell = ObtainVarCell.obtain(frame, acc, info, bytecode);
            return Ops.refStartValue(cell, traitSelf, declaringClass, lineOf(bytecode, bci), node);
        }
    }

    /** A {@code foreach} subject that is an index path on a local. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = String.class, name = "describe")
    @ConstantOperand(type = String.class, name = "declaringClass")
    public static final class ForeachRefStartVarPlace {
        @Specialization
        static Object start(VirtualFrame frame, LocalAccessor acc, VarInfo info, PlaceSpec spec, String describe,
                            String declaringClass, Object traitSelf, @Variadic Object[] ops,
                            @Bind BytecodeNode bytecode,
                            @Bind("$bytecodeIndex") int bci, @Bind Node node,
                            @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            Object[] holder = {HOLDER_EMPTY};
            Object[] keys = Places.varKeys(current(frame, acc, bytecode), holder, ops, spec.arrayKeys);
            Object result = Ops.refStartPlace(frame, engine, keys, describe, lineOf(bytecode, bci), node,
                    traitSelf, declaringClass);
            if (holder[0] != HOLDER_EMPTY) {
                store(frame, acc, info, bytecode, holder[0]);
            }
            return result;
        }
    }

    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = String.class, name = "describe")
    @ConstantOperand(type = String.class, name = "declaringClass")
    public static final class ForeachRefStartObj {
        @Specialization
        static Object start(VirtualFrame frame, PlaceSpec spec, String describe, String declaringClass,
                            Object traitSelf, @Variadic Object[] ops,
                            @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci, @Bind Node node,
                            @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            Object[] keys = Places.objKeys(ops, spec.arrayKeys, spec.baseCount);
            return Ops.refStartPlace(frame, engine, keys, describe, lineOf(bytecode, bci), node,
                    traitSelf, declaringClass);
        }
    }

    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = String.class, name = "describe")
    @ConstantOperand(type = String.class, name = "declaringClass")
    public static final class ForeachRefStartGlobal {
        @Specialization
        static Object start(VirtualFrame frame, PlaceSpec spec, String describe, String declaringClass,
                            Object traitSelf, @Variadic Object[] ops,
                            @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci, @Bind Node node,
                            @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            Object[] keys = Places.globalKeys(ops, spec.arrayKeys);
            return Ops.refStartPlace(frame, engine, keys, describe, lineOf(bytecode, bci), node,
                    traitSelf, declaringClass);
        }
    }

    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = String.class, name = "describe")
    @ConstantOperand(type = String.class, name = "declaringClass")
    public static final class ForeachRefStartStatic {
        @Specialization
        static Object start(VirtualFrame frame, PlaceSpec spec, String describe, String declaringClass,
                            Object traitSelf, @Variadic Object[] ops,
                            @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci, @Bind Node node,
                            @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            Object[] keys = Places.staticKeys(ops, spec.arrayKeys, spec.baseCount);
            return Ops.refStartPlace(frame, engine, keys, describe, lineOf(bytecode, bci), node,
                    traitSelf, declaringClass);
        }
    }

    @Operation
    @ConstantOperand(type = PlaceSpec.class)
    @ConstantOperand(type = String.class, name = "describe")
    @ConstantOperand(type = String.class, name = "declaringClass")
    public static final class ForeachRefStartGlobals {
        @Specialization
        static Object start(VirtualFrame frame, PlaceSpec spec, String describe, String declaringClass,
                            Object traitSelf, @Variadic Object[] ops,
                            @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci, @Bind Node node,
                            @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            Object[] keys = Places.globalsElementKeys(ops, spec.baseCount, spec.arrayKeys);
            return Ops.refStartPlace(frame, engine, keys, describe, lineOf(bytecode, bci), node,
                    traitSelf, declaringClass);
        }
    }

    @Operation
    @ConstantOperand(type = String.class, name = "declaringClass")
    public static final class ForeachRefStartValue {
        @Specialization
        static Object start(String declaringClass, Object subject, Object traitSelf,
                            @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci, @Bind Node node) {
            return Ops.refStartValue(subject, traitSelf, declaringClass, lineOf(bytecode, bci), node);
        }
    }

    @Operation
    public static final class ForeachRefNext {
        @Specialization
        static boolean next(RefCursor c, @Bind Node node, @Bind PhpBytecodeRootNode root,
                            @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                            @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                            @Cached("create(root)") IteratorNode protocol) {
            TruffleSafepoint.poll(node);
            if (c.generator != null) {
                if (c.generatorStarted) {
                    protocol.next(c.generatorObject, location);
                } else {
                    c.generatorStarted = true;
                    protocol.rewind(c.generatorObject, location);
                }
                if (!protocol.valid(c.generatorObject, location)) {
                    return false;
                }
                c.key = protocol.key(c.generatorObject, location);
                c.element = c.generator.currentCell();
                return true;
            }
            if (c.array != null) {
                while (true) {
                    c.pos++;
                    if (c.pos >= c.keys.size()) {
                        return false;
                    }
                    Object key = c.keys.get(c.pos);
                    PhpArray current = c.cell != null && c.cell.getValue() instanceof PhpArray ca ? ca : c.array;
                    PhpReference ref = current.elementRefIfPresent(key);
                    if (ref == null) {
                        continue;
                    }
                    c.key = key;
                    c.element = ref;
                    return true;
                }
            }
            if (c.entries != null) {
                c.pos++;
                if (c.pos >= c.entries.size()) {
                    return false;
                }
                c.key = c.entryKey();
                c.element = c.entryElement();
                return true;
            }
            return false;
        }
    }

    @Operation
    public static final class ForeachRefKey {
        @Specialization
        static Object key(RefCursor c) {
            return c.key;
        }
    }

    @Operation
    public static final class ForeachRefCell {
        @Specialization
        static PhpReference cell(RefCursor c) {
            return c.element;
        }
    }

    // ---- generators (phase 6)

    /**
     * Before a {@code yield}: the generator records the key and the copied value it suspends
     * with, as {@code YieldNode} does. {@code keyMode} 0 is an auto key, 1 an explicit key.
     * The result is what the following Yield suspends with (unused).
     */
    @Operation
    @ConstantOperand(type = int.class, name = "keyMode")
    public static final class GenSuspend {
        @Specialization
        static Object suspend(int keyMode, Object generator, Object key, Object value,
                              @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                              @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location) {
            PhpGenerator gen = (PhpGenerator) generator;
            Object copied = PhpOps.copyOnAssign(value);
            gen.checkYield(location);
            gen.suspendWith(keyMode == 0 ? gen.autoKey() : gen.explicitKey(key), copied);
            return PhpNull.INSTANCE;
        }
    }

    /**
     * After a {@code yield} resumes: the resumed value is the generator; the yield's value is
     * the sent input, or the exception thrown in (including the close signal) is raised here.
     */
    @Operation
    public static final class GenTakeInput {
        @Specialization
        static Object take(Object resumed) {
            return ((PhpGenerator) resumed).takeInput();
        }
    }

    /** The state of one {@code yield from}: an array's entries or a Traversable's iterator. */
    public static final class YieldFromCursor {
        java.util.List<java.util.Map.Entry<Object, Object>> entries;
        PhpObject iterator;
        int index;
    }

    /** {@code yield from $source}: the cursor, as {@code YieldFromNode} builds it. */
    @Operation
    public static final class YieldFromStart {
        @Specialization
        static YieldFromCursor start(Object source, @Bind PhpBytecodeRootNode root,
                                     @Cached("create(root)") IteratorNode protocol) {
            YieldFromCursor cursor = new YieldFromCursor();
            if (source instanceof PhpArray array) {
                cursor.entries = array.entries();
            } else if (source instanceof PhpObject obj
                    && dev.phpruntime.runtime.SplInterfaces.has(obj, dev.phpruntime.runtime.SplInterfaces.TRAVERSABLE)) {
                cursor.iterator = protocol.resolve(obj);
                if (!(cursor.iterator.nativeState() instanceof PhpGenerator)) {
                    protocol.rewind(cursor.iterator);
                }
            } else {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Can use \"yield from\" only with arrays and Traversables");
            }
            return cursor;
        }
    }

    /** Whether the delegate has a current element. */
    @Operation
    public static final class YieldFromValid {
        @Specialization
        static boolean valid(YieldFromCursor cursor, @Bind PhpBytecodeRootNode root,
                             @Cached("create(root)") IteratorNode protocol) {
            return cursor.iterator != null ? protocol.valid(cursor.iterator) : cursor.index < cursor.entries.size();
        }
    }

    /** Suspends with the delegate's current key and value; delegated keys do not advance auto keys. */
    @Operation
    public static final class YieldFromSuspend {
        @Specialization
        static Object suspend(Object generator, YieldFromCursor cursor, @Bind PhpBytecodeRootNode root,
                              @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                              @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                              @Cached("create(root)") IteratorNode protocol) {
            PhpGenerator outer = (PhpGenerator) generator;
            Object value = cursor.iterator != null ? protocol.current(cursor.iterator) : Ops.cursorValue(cursor);
            Object key = cursor.iterator != null ? protocol.key(cursor.iterator) : Ops.cursorKey(cursor);
            outer.checkYield(location);
            outer.suspendWith(key, PhpOps.copyOnAssign(value));
            return PhpNull.INSTANCE;
        }
    }

    /**
     * After a delegated yield resumes: forwards the sent value, a thrown-in exception or the
     * close to an inner generator, else advances the delegate, as {@code YieldFromNode} does.
     */
    @Operation
    public static final class YieldFromResume {
        @Specialization
        static void resume(Object generator, YieldFromCursor cursor, Object resumed,
                           @Bind PhpBytecodeRootNode root, @Bind BytecodeNode bytecode,
                           @Bind("$bytecodeIndex") int bci,
                           @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location,
                           @Cached("create(root)") IteratorNode protocol,
                           @Cached("create()") DispatchCallNode dispatch) {
            PhpGenerator outer = (PhpGenerator) generator;
            if (cursor.iterator != null && cursor.iterator.nativeState() instanceof PhpGenerator inner) {
                Object input;
                try {
                    input = outer.takeInput();
                } catch (PhpException error) {
                    inner.throwInto(error.exceptionObject(), dispatch, location);
                    input = null;
                } catch (dev.phpruntime.runtime.GeneratorCloseSignal close) {
                    inner.close(dispatch, location);
                    throw close;
                }
                if (input != null) {
                    inner.send(input, dispatch, location);
                }
            } else {
                outer.takeInput();
                cursor.index++;
                if (cursor.iterator != null) {
                    protocol.next(cursor.iterator);
                }
            }
        }
    }

    /** The value of {@code yield from}: an inner generator's return value, else null. */
    @Operation
    public static final class YieldFromResult {
        @Specialization
        static Object result(YieldFromCursor cursor, @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci,
                             @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location) {
            return cursor.iterator != null && cursor.iterator.nativeState() instanceof PhpGenerator inner
                    ? inner.getReturn(location) : PhpNull.INSTANCE;
        }
    }

    // ---- task 20

    /**
     * A yield of a by-reference generator: the generator suspends with the cell of an lvalue
     * (or a by-reference call result), so a by-reference foreach writes through it. A value
     * that is not a reference gets php's "Only variable references should be yielded by
     * reference" notice and a temporary cell, as {@code YieldNode} does on the AST.
     */
    @Operation
    @ConstantOperand(type = int.class, name = "keyMode")
    @ConstantOperand(type = boolean.class, name = "hasValue")
    public static final class GenSuspendRef {
        @Specialization
        static Object suspend(int keyMode, boolean hasValue, Object generator, Object key, Object value,
                              @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci, @Bind Node node,
                              @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location) {
            PhpGenerator gen = (PhpGenerator) generator;
            Object cell;
            if (value instanceof PhpReference ref) {
                cell = ref;
            } else if (!hasValue) {
                cell = new PhpReference(PhpNull.INSTANCE);
            } else {
                PhpContext.get(node).warning("Only variable references should be yielded by reference",
                        lineOf(bytecode, bci));
                cell = new PhpReference(PhpOps.copyOnAssign(value));
            }
            gen.checkYield(location);
            gen.suspendWith(keyMode == 0 ? gen.autoKey() : gen.explicitKey(key), cell);
            return PhpNull.INSTANCE;
        }
    }
}
