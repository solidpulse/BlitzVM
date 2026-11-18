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
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.source.SourceSection;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpRootNode;
import dev.phpruntime.nodes.call.CallArgHelper;
import dev.phpruntime.nodes.call.CallNode;
import dev.phpruntime.nodes.call.DispatchCallNode;
import dev.phpruntime.nodes.call.SplBuiltinNode;
import dev.phpruntime.nodes.expr.CastNode;
import dev.phpruntime.nodes.expr.CompareNode;
import dev.phpruntime.nodes.expr.CondNode;
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
import dev.phpruntime.runtime.PhpExceptions;
import dev.phpruntime.runtime.PhpFunction;
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
@GenerateBytecode(languageClass = PhpLanguage.class, enableQuickening = true,
        boxingEliminationTypes = {long.class, double.class, boolean.class},
        enableBlockScoping = false,
        // exit/die skips finally blocks in PHP: it must pass through guest handlers.
        unwindExceptions = {ControlFlow.Exit.class})
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

    private PhpFunctionRootNode function;
    private dev.phpruntime.nodes.PhpMainRootNode main;

    protected PhpBytecodeRootNode(PhpLanguage language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    dev.phpruntime.nodes.PhpMainRootNode main() {
        return main;
    }

    void init(PhpFunctionRootNode fn) {
        this.function = fn;
        SourceSection ss = fn.getSourceSection();
        initSource(fn.source(), ss != null ? ss.getStartLine() : -1);
    }

    void initMain(dev.phpruntime.nodes.PhpMainRootNode m) {
        this.main = m;
        initSource(m.source(), 1);
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
        return main != null ? main.getName() : function.getName();
    }

    @Override
    public String toString() {
        return main != null ? main.getName() : function.toString();
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
        BytecodeLocation loc = BytecodeLocation.get(fi);
        return loc == null ? null : loc.getSourceLocation();
    }

    /** What a variable operation needs besides its local: the AST's kind and the name for warnings. */
    public record VarInfo(VarKind kind, String name) {
    }

    /** A call site: the function name and ids as {@code CallNode} has them. */
    public record CallSite(String name, int functionId, String nsName, int nsFunctionId, String splName) {
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
        static SiteNode create(BytecodeNode bytecode, int bci) {
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
        if (acc.isCleared(bytecode, frame)) {
            return null;
        }
        Object v = acc.getObject(bytecode, frame);
        if (v instanceof PhpReference ref) {
            return ref.getValue();
        }
        if (v instanceof PhpStringBuffer) {
            return PhpStringBuffer.flatten(v);
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
            acc.setLong(bytecode, frame, l);
        } else if (v instanceof Double d) {
            acc.setDouble(bytecode, frame, d);
        } else {
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
        @Specialization(rewriteOn = UnexpectedResultException.class)
        static long readLong(VirtualFrame frame, LocalAccessor acc, VarInfo info, @Bind BytecodeNode bytecode,
                             @Bind("$bytecodeIndex") int bci, @Bind Node node) throws UnexpectedResultException {
            try {
                return acc.getLong(bytecode, frame);
            } catch (UnexpectedResultException e) {
                throw new UnexpectedResultException(read(frame, acc, info, bytecode, bci, node));
            }
        }

        @Specialization(rewriteOn = UnexpectedResultException.class)
        static double readDouble(VirtualFrame frame, LocalAccessor acc, VarInfo info, @Bind BytecodeNode bytecode,
                                 @Bind("$bytecodeIndex") int bci, @Bind Node node) throws UnexpectedResultException {
            try {
                return acc.getDouble(bytecode, frame);
            } catch (UnexpectedResultException e) {
                throw new UnexpectedResultException(read(frame, acc, info, bytecode, bci, node));
            }
        }

        @Specialization(replaces = {"readLong", "readDouble"})
        static Object read(VirtualFrame frame, LocalAccessor acc, VarInfo info, @Bind BytecodeNode bytecode,
                           @Bind("$bytecodeIndex") int bci, @Bind Node node) {
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
        static Object read(VirtualFrame frame, LocalAccessor acc, VarInfo info, @Bind BytecodeNode bytecode) {
            Object v = current(frame, acc, bytecode);
            return v == null ? PhpNull.INSTANCE : v;
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = VarInfo.class)
    public static final class IssetVar {
        @Specialization
        static boolean isset(VirtualFrame frame, LocalAccessor acc, VarInfo info, @Bind BytecodeNode bytecode) {
            Object v = current(frame, acc, bytecode);
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
            acc.setLong(bytecode, frame, value);
            return value;
        }

        @Specialization(guards = "!info.kind().isCell()")
        static double writeDouble(VirtualFrame frame, LocalAccessor acc, VarInfo info, double value,
                                  @Bind BytecodeNode bytecode) {
            acc.setDouble(bytecode, frame, value);
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
            acc.setLong(bytecode, frame, value);
        }

        @Specialization(guards = "!info.kind().isCell()")
        static void storeDouble(VirtualFrame frame, LocalAccessor acc, VarInfo info, double value,
                                @Bind BytecodeNode bytecode) {
            acc.setDouble(bytecode, frame, value);
        }

        @Specialization(replaces = {"storeLong", "storeDouble"})
        static void store(VirtualFrame frame, LocalAccessor acc, VarInfo info, Object value,
                          @Bind BytecodeNode bytecode) {
            PhpBytecodeRootNode.store(frame, acc, info, bytecode, PhpOps.copyOnAssign(value));
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
            if (!info.kind().isCell()) {
                try {
                    long l = acc.getLong(bytecode, frame);
                    if (l != Long.MAX_VALUE && l != Long.MIN_VALUE) {
                        acc.setLong(bytecode, frame, increment ? l + 1 : l - 1);
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
        @Specialization(guards = "!info.kind().isCell()", rewriteOn = UnexpectedResultException.class)
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
            acc.setLong(bytecode, frame, u);
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

        @Specialization
        static Object addLongChecked(long a, long b) {
            try {
                return Math.addExact(a, b);
            } catch (ArithmeticException e) {
                return (double) a + (double) b;
            }
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

        @Specialization
        static Object subLongChecked(long a, long b) {
            try {
                return Math.subtractExact(a, b);
            } catch (ArithmeticException e) {
                return (double) a - (double) b;
            }
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

        @Specialization
        static Object mulLongChecked(long a, long b) {
            try {
                return Math.multiplyExact(a, b);
            } catch (ArithmeticException e) {
                return (double) a * (double) b;
            }
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

    // ------------------------------------------------------------------ arrays

    @Operation
    public static final class NewArray {
        @Specialization
        static PhpArray create() {
            return new PhpArray();
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

    @Operation
    public static final class Index {
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
        int pos = -1;
        boolean started;

        Cursor(PhpArray array, PhpObject iterator, List<Map.Entry<Object, Object>> entries) {
            this.array = array;
            this.iterator = iterator;
            this.entries = entries;
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
                             @Cached("create(root)") IteratorNode iterator, @Bind Node node) {
            if (object.phpClass().hasSplInterface(SplInterfaces.TRAVERSABLE)) {
                PhpObject it = iterator.resolve(object);
                iterator.rewind(it);
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
                    iterator.next(c.iterator);
                }
                c.started = true;
                return iterator.valid(c.iterator);
            }
            c.pos++;
            return c.pos < c.entries.size();
        }
    }

    @Operation
    public static final class ForeachKey {
        @Specialization
        static Object key(Cursor c, @Bind PhpBytecodeRootNode root,
                          @Cached("create(root)") IteratorNode iterator) {
            if (c.array != null) {
                return c.array.keyAt(c.pos);
            }
            if (c.iterator != null) {
                return iterator.key(c.iterator);
            }
            return c.entryKey();
        }
    }

    @Operation
    public static final class ForeachValue {
        @Specialization
        static Object value(Cursor c, @Bind PhpBytecodeRootNode root,
                            @Cached("create(root)") IteratorNode iterator) {
            Object raw;
            if (c.array != null) {
                raw = c.array.rawValueAt(c.pos);
            } else if (c.iterator != null) {
                raw = iterator.current(c.iterator);
            } else {
                raw = c.entryValue();
            }
            Object v = raw instanceof PhpReference ref ? ref.getValue() : raw;
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

    // ------------------------------------------------------------------ calls

    /** The function a call site names in this request: a {@link PhpFunction} or a builtin. */
    @Operation
    @ConstantOperand(type = CallSite.class)
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class ResolveFunction {
        @Specialization
        static void resolve(VirtualFrame frame, CallSite site, LocalAccessor callee, @Bind BytecodeNode bytecode,
                            @Bind Node node) {
            callee.setObject(bytecode, frame, lookup(site, node));
        }

        static Object lookup(CallSite site, Node node) {
            PhpContext ctx = PhpContext.get(node);
            if (site.nsFunctionId() >= 0) {
                PhpFunction nsFn = ctx.functionById(site.nsFunctionId());
                if (nsFn != null) {
                    return nsFn;
                }
            }
            PhpFunction fn = ctx.functionById(site.functionId());
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
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci, @Bind Node node) {
            Object callee = calleeLocal.getObject(bytecode, frame);
            Builtins.Def builtin = builtinCallee(callee);
            if (builtin != null) {
                if (builtin.isRef(param)) {
                    if ("this".equals(info.name())) {
                        return new PhpReference(ReadVar.read(frame, acc, info, bytecode, bci, node));
                    }
                    return ObtainVarCell.obtain(frame, acc, info, bytecode);
                }
                return callee instanceof Builtins.Def ? ReadVar.read(frame, acc, info, bytecode, bci, node)
                        : PhpOps.copyOnAssign(ReadVar.read(frame, acc, info, bytecode, bci, node));
            }
            PhpFunctionRootNode root = signature(callee);
            if (root != null && root.isParamByRef(param)) {
                if ("this".equals(info.name())) {
                    return new PhpReference(ReadVar.read(frame, acc, info, bytecode, bci, node));
                }
                return ObtainVarCell.obtain(frame, acc, info, bytecode);
            }
            return PhpOps.copyOnAssign(ReadVar.read(frame, acc, info, bytecode, bci, node));
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
                           @Bind BytecodeNode bytecode, @Bind("$bytecodeIndex") int bci, @Bind Node node) {
            Object callee = calleeLocal.getObject(bytecode, frame);
            Builtins.Def builtin = builtinCallee(callee);
            if (builtin != null) {
                if (builtin.isRef(param)) {
                    return Ops.builtinValueToRefParam(builtin, param, kind, value, node, lineOf(bytecode, bci));
                }
                return callee instanceof Builtins.Def ? value : PhpOps.copyOnAssign(value);
            }
            PhpFunctionRootNode root = signature(callee);
            if (root != null && root.isParamByRef(param)) {
                return Ops.valueToRefParam(root, param, kind, value, node, lineOf(bytecode, bci));
            }
            return PhpOps.copyOnAssign(value);
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
        @Specialization
        static void resolve(VirtualFrame frame, MethodSite site, LocalAccessor callee, Object target, Object nameValue,
                            @Bind BytecodeNode bytecode) {
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
            RootCallTarget sig = entry.callTarget();
            if (obj.closure() != null && obj.closure().callTarget() != null
                    && sig.getRootNode() instanceof dev.phpruntime.nodes.call.ClosureInvokeRootNode) {
                sig = obj.closure().callTarget();
            }
            callee.setObject(bytecode, frame, new MethodCallee(entry, thisArg, cls, sig, obj, name, null));
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class DoMethodCall {
        @Specialization
        static Object call(VirtualFrame frame, LocalAccessor calleeLocal, @Variadic Object[] args,
                           @Bind BytecodeNode bytecode, @Cached("create()") DispatchCallNode dispatch) {
            MethodCallee m = (MethodCallee) calleeLocal.getObject(bytecode, frame);
            if (m.entry() == null) {
                return CallArgHelper.unwrapReturn(dispatch.call(m.magic(),
                        new Object[]{m.obj(), m.cls(), m.name(), Ops.packArgs(args)}));
            }
            Object[] callArgs = new Object[2 + args.length];
            callArgs[0] = m.thisArg();
            callArgs[1] = m.cls();
            System.arraycopy(args, 0, callArgs, 2, args.length);
            return CallArgHelper.unwrapReturn(dispatch.callMethod(m.entry(), callArgs));
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
                          @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            Object[] holder = {HOLDER_EMPTY};
            Object[] keys = Places.varKeys(current(frame, acc, bytecode), holder, ops, spec.arrayKeys);
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
            Object[] keys = Places.objKeys(ops, spec.arrayKeys);
            return Places.run(frame, engine, keys, mode, arg, Places.value(ops, 2 + spec.arrayKeys));
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
            return Places.run(frame, engine, Places.globalKeys(ops, spec.arrayKeys), mode, arg,
                    Places.value(ops, spec.arrayKeys));
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
                           @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            int mode = Places.sendMode(calleeLocal.getObject(bytecode, frame), param);
            Object[] keys = Places.staticKeys(ops, spec.arrayKeys, spec.baseCount);
            Object result = Places.run(frame, engine, keys, mode, 0, null);
            return Places.sendValue(calleeLocal.getObject(bytecode, frame), mode, result);
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
                           @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            int mode = Places.sendMode(calleeLocal.getObject(bytecode, frame), param);
            Object result = Places.run(frame, engine, Places.globalKeys(ops, spec.arrayKeys), mode, 0, null);
            return Places.sendValue(calleeLocal.getObject(bytecode, frame), mode, result);
        }
    }

    // ------------------------------------------------------------------ top-level code

    /** True when this file is the request's entry script (called with no arguments). */
    @Operation
    public static final class IsEntry {
        @Specialization
        static boolean test(VirtualFrame frame) {
            return frame.getArguments().length == 0;
        }
    }

    /** The entry script runs on its AST root, which owns request start, finish and uncaught errors. */
    @Operation
    public static final class RunEntry {
        @Specialization
        static Object run(@Bind PhpBytecodeRootNode root, @Cached("create(root.mainRoot().getCallTarget())") DirectCallNode call) {
            return call.call();
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
                           @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            int mode = Places.sendMode(calleeLocal.getObject(bytecode, frame), param);
            Object[] holder = {HOLDER_EMPTY};
            Object[] keys = Places.varKeys(current(frame, acc, bytecode), holder, ops, spec.arrayKeys);
            Object result = Places.run(frame, engine, keys, mode, 0, null);
            if (holder[0] != HOLDER_EMPTY) {
                store(frame, acc, info, bytecode, holder[0]);
            }
            return Places.sendValue(calleeLocal.getObject(bytecode, frame), mode, result);
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
                           @Cached("spec.newEngine()") dev.phpruntime.nodes.var.LValueNode engine) {
            int mode = Places.sendMode(calleeLocal.getObject(bytecode, frame), param);
            Object result = Places.run(frame, engine, Places.objKeys(ops, spec.arrayKeys), mode, 0, null);
            return Places.sendValue(calleeLocal.getObject(bytecode, frame), mode, result);
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

        static Object[] objKeys(Object[] ops, int n) {
            Object[] base = {ops[0], ops[1]};
            if (n == 0) {
                return base;
            }
            Object[] arrayKeys = new Object[n];
            System.arraycopy(ops, 2, arrayKeys, 0, n);
            return dev.phpruntime.nodes.var.IndexLValueNode.pack(base, arrayKeys);
        }

        static Object value(Object[] ops, int index) {
            return index < ops.length ? ops[index] : null;
        }

        static int sendMode(Object callee, int param) {
            Builtins.Def builtin = builtinCallee(callee);
            if (builtin != null) {
                return builtin.isRef(param) ? P_CELL : P_READ;
            }
            PhpFunctionRootNode root = signature(callee);
            if (root != null && root.isParamByRef(param)) {
                return P_CELL;
            }
            return P_READ;
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
                        return p.executeOn(frame, keys[0], (String) keys[1]);
                    }
                    if (engine instanceof dev.phpruntime.nodes.var.StaticPropertyLValueNode sp) {
                        return sp.executeWithKeys(frame, keys);
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
        @Specialization
        static Object read(String constName, PhpClass cls, @Bind Node node) {
            Object val = Ops.classConstant(PhpContext.get(node), cls, constName);
            if (val == null) {
                CompilerDirectives.transferToInterpreter();
                throw Ops.error("Undefined constant " + cls.name() + "::" + constName);
            }
            return val;
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
                             @Cached("create(cachedTarget)") DirectCallNode call) {
            return CallArgHelper.unwrapReturn(call.call(args));
        }

        @Specialization(replaces = "direct")
        static Object indirect(CallSite site, PhpFunction fn, Object[] args, SiteNode location,
                               @Cached IndirectCallNode call) {
            return CallArgHelper.unwrapReturn(call.call(fn.callTarget(), args));
        }

        @Specialization
        static Object builtin(CallSite site, Builtins.Def def, Object[] args, SiteNode location,
                              @Bind Node node,
                              @Cached(value = "createSpl(node, site)", neverDefault = false) SplBuiltinNode spl) {
            if (spl != null) {
                CallNode.checkArity(def, args.length, location);
                return spl.execute(args, location);
            }
            if (def.refMask() != 0) {
                return Ops.callBuiltinValue(def, args, location);
            }
            return Ops.callBuiltin(def, args, location);
        }

        static SplBuiltinNode createSpl(Node node, CallSite site) {
            return SplBuiltinNode.create(node.getRootNode(), site.splName());
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
            StringBuilder sb = new StringBuilder();
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

        @TruffleBoundary
        static Object builtinValueToRefParam(Builtins.Def def, int param, int kind, Object value, Node node, int line) {
            // CallNode.callBuiltin: a call result may be passed with a notice; anything else
            // (a plain expression or a new object) is an error for a builtin.
            if (kind != 1) {
                throw new PhpError(def.name() + "(): Argument #" + (param + 1) + " could not be passed by reference");
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

        @TruffleBoundary
        static Object callBuiltin(Builtins.Def def, Object[] args, SiteNode site) {
            if (def.name().equals("assert")) {
                return true;
            }
            CallNode.checkArity(def, args.length, site);
            PhpContext ctx = PhpContext.get(site);
            Node prev = ctx.currentBuiltinCallSite();
            ctx.setCurrentBuiltinCallSite(site);
            try {
                return def.call(ctx, args);
            } catch (PhpError e) {
                throw e.withTrace(PhpBacktrace.captureFromBuiltin(site, def.name())).at(site);
            } finally {
                ctx.setCurrentBuiltinCallSite(prev);
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
                            Object calledClassValue, @Bind BytecodeNode bytecode) {
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
            RootCallTarget sig = entry.callTarget();
            PhpObject obj = thisValue instanceof PhpObject o ? o : null;
            calleeLocal.setObject(bytecode, frame,
                    new MethodCallee(entry, thisArg, calledArg, sig, obj, name, null));
        }
    }

    /** {@code new Cls(...)}: validates the class, creates the object and populates its defaults. */
    @Operation
    public static final class NewObject {
        @Specialization
        static PhpObject create(PhpClass cls, @Bind Node node, @Bind BytecodeNode bytecode,
                                @Bind("$bytecodeIndex") int bci,
                                @Cached(value = "create(bytecode, bci)", neverDefault = true) SiteNode location) {
            PhpContext ctx = PhpContext.get(node);
            if (cls == ctx.closureClass()) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Instantiation of Closure is not allowed");
            }
            if (cls.isInterface() || cls.isTrait() || cls.isAbstract()) {
                String kind = cls.isInterface() ? "interface " : (cls.isTrait() ? "trait " : "abstract class ");
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Cannot instantiate " + kind + cls.name());
            }
            PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
            cls.populateDefaults(obj);
            if (cls.isThrowable()) {
                dev.phpruntime.runtime.PhpExceptions.initNewThrowable(obj, location);
            }
            return obj;
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
                            @Bind BytecodeNode bytecode) {
            PhpClass cls = obj.phpClass();
            PhpClass.MethodEntry ctor = cls.constructMethod();
            if (ctor == null) {
                calleeLocal.setObject(bytecode, frame, PhpNull.INSTANCE);
                return;
            }
            calleeLocal.setObject(bytecode, frame,
                    new MethodCallee(ctor, obj, cls, ctor.callTarget(), obj, ctor.name(), null));
        }
    }

    /** Runs the constructor resolved by {@link ResolveConstructor}; the object is the result. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class DoConstructorCall {
        @Specialization
        static Object call(VirtualFrame frame, LocalAccessor calleeLocal, PhpObject obj, @Variadic Object[] args,
                           @Bind BytecodeNode bytecode, @Cached("create()") DispatchCallNode dispatch) {
            Object callee = calleeLocal.getObject(bytecode, frame);
            if (callee instanceof MethodCallee m) {
                Object[] callArgs = new Object[2 + args.length];
                callArgs[0] = m.thisArg();
                callArgs[1] = m.cls();
                System.arraycopy(args, 0, callArgs, 2, args.length);
                dispatch.callMethod(m.entry(), callArgs);
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
        @Specialization
        static boolean test(ClassRefSpec spec, Object value, Object operand,
                            @Cached("spec.newEngine()") dev.phpruntime.nodes.expr.ClassRefNode engine) {
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
                            @Cached("create()") DispatchCallNode dispatch) {
            return dev.phpruntime.nodes.expr.CloneNode.cloneValue(value, location, dispatch);
        }
    }

    /** Resolves a {@code $f} operand into a {@link ValueCallee}, as {@code CallValueNode} does. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class ResolveCallable {
        @Specialization
        static void resolve(VirtualFrame frame, LocalAccessor calleeLocal, Object targetValue, Object callerThis,
                            @Bind BytecodeNode bytecode, @Bind Node node) {
            dev.phpruntime.runtime.PhpCallable callable =
                    Ops.resolveCallable(PhpContext.get(node), targetValue, callerThis);
            // A magic call collects its arguments into an array, so its target's parameters do
            // not describe the call site (the AST evaluates those arguments as plain values).
            PhpFunctionRootNode sig = callable.kind() == dev.phpruntime.runtime.PhpCallable.Kind.MAGIC_CALL
                    || callable.callTarget() == null ? null
                    : PhpFunctionRootNode.of(callable.callTarget().getRootNode());
            calleeLocal.setObject(bytecode, frame, new ValueCallee(callable, sig));
        }
    }

    /** Calls the {@link ValueCallee} written by {@link ResolveCallable}, laying out its frame. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "callee")
    public static final class DoValueCall {
        @Specialization
        static Object call(VirtualFrame frame, LocalAccessor calleeLocal, @Variadic Object[] args,
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
                    yield dispatch.call(cb.callTarget(), frameArgs);
                }
                case USER_FUNCTION -> dispatch.call(cb.callTarget(), args);
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
                    yield dispatch.call(cb.callTarget(), frameArgs);
                }
                case MAGIC_CALL -> dispatch.call(cb.callTarget(), new Object[]{
                        cb.boundThis(), cb.calledClass(), cb.magicMethodName(), Ops.magicArgs(args)});
                case BUILTIN -> Ops.callBuiltinValue(cb.builtin(), args, location);
            };
            return CallArgHelper.unwrapReturn(result);
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
            Places.destructureStep(frame, engine, Places.objKeys(ops, spec.arrayKeys), item);
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
}
