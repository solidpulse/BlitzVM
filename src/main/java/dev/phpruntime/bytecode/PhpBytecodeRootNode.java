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
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpRootNode;
import dev.phpruntime.nodes.call.CallArgHelper;
import dev.phpruntime.nodes.call.CallNode;
import dev.phpruntime.nodes.call.DispatchCallNode;
import dev.phpruntime.nodes.call.SplBuiltinNode;
import dev.phpruntime.nodes.expr.CompareNode;
import dev.phpruntime.nodes.expr.CondNode;
import dev.phpruntime.nodes.stmt.IteratorNode;
import dev.phpruntime.nodes.var.ArrayAccessNode;
import dev.phpruntime.nodes.var.IndexReadNode;
import dev.phpruntime.nodes.var.VarKind;
import dev.phpruntime.runtime.PhpArith;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpBacktrace;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
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
        enableBlockScoping = false)
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
            if (!(callee instanceof Builtins.Def)) {
                PhpFunctionRootNode root = signature(callee);
                if (root != null && root.isParamByRef(param)) {
                    if ("this".equals(info.name())) {
                        return new PhpReference(ReadVar.read(frame, acc, info, bytecode, bci, node));
                    }
                    return ObtainVarCell.obtain(frame, acc, info, bytecode);
                }
                return PhpOps.copyOnAssign(ReadVar.read(frame, acc, info, bytecode, bci, node));
            }
            return ReadVar.read(frame, acc, info, bytecode, bci, node);
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
            if (!(callee instanceof Builtins.Def)) {
                PhpFunctionRootNode root = signature(callee);
                if (root != null && root.isParamByRef(param)) {
                    return Ops.valueToRefParam(root, param, kind, value, node, lineOf(bytecode, bci));
                }
                return PhpOps.copyOnAssign(value);
            }
            return value;
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
        return null;
    }

    /** A method call resolved on its receiver; {@code entry} is null for a {@code __call} fallback. */
    public record MethodCallee(PhpClass.MethodEntry entry, Object thisArg, PhpClass cls, RootCallTarget sigTarget,
                               PhpObject obj, String name, RootCallTarget magic) {
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

    /**
     * A non-local lvalue (an index path or a property). {@code template} is a copy of its AST
     * node without expressions; each instruction caches its own copy and calls its read, write,
     * cell and unset methods with the keys the bytecode evaluated, so the semantics are the AST's.
     */
    public static final class PlaceSpec {
        private final dev.phpruntime.nodes.var.LValueNode template;
        final int arrayKeys;

        public PlaceSpec(dev.phpruntime.nodes.var.LValueNode template, int arrayKeys) {
            this.template = template;
            this.arrayKeys = arrayKeys;
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
            if (!(callee instanceof Builtins.Def)) {
                PhpFunctionRootNode root = signature(callee);
                if (root != null && root.isParamByRef(param)) {
                    return P_CELL;
                }
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
                    return engine.execute(frame);
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
                default:
                    throw CompilerDirectives.shouldNotReachHere();
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
                return spl.execute(args);
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
}
