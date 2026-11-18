package dev.phpruntime.nodes;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.source.Source;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.var.VarKind;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

/**
 * Root of a user function, method, or closure. Binds arguments (or defaults) and
 * closure captures to frame slots, runs the body and turns a Return control-flow
 * exception into the result. Polls a safepoint on entry so deep recursion without
 * loops can still be preempted.
 */
public final class PhpFunctionRootNode extends PhpRootNode {

    public static final Object UNPASSED = new Object();

    private final String declaringClass;
    private final String name;
    private final boolean isStatic;
    private final boolean returnsRef;
    private final int thisSlot;
    private final int calledClassSlot;
    private final int closureObjSlot;
    private final int traitSelfSlot;
    @CompilationFinal(dimensions = 1) private final int[] captureSlots;
    @CompilationFinal(dimensions = 1) private final VarKind[] captureKinds;
    @CompilationFinal(dimensions = 1) private final boolean[] captureByRef;
    @CompilationFinal(dimensions = 1) private final String[] paramNames;
    @CompilationFinal(dimensions = 1) private final int[] paramSlots;
    @CompilationFinal(dimensions = 1) private final VarKind[] paramKinds;
    @CompilationFinal(dimensions = 1) private final boolean[] paramByRef;
    private final boolean hasAnyRefParam;
    @Children private final PhpNode[] defaults;
    private final boolean variadic;
    private final int requiredArgs;
    @Child private PhpNode body;
    /** A generator's body as written, which the bytecode compiles; never executed here. */
    @Child private PhpNode generatorSource;
    @CompilationFinal private boolean generator;
    @CompilationFinal private boolean usesThis;
    private final dev.phpruntime.runtime.PhpScopeLayout scopeLayout;
    private final int declaringClassId;

    public PhpFunctionRootNode(PhpLanguage language, FrameDescriptor fd, Source source, int line, String name,
                               int[] paramSlots, VarKind[] paramKinds, PhpNode[] defaults, boolean variadic, PhpNode body) {
        this(language, fd, source, line, null, name, false, false, -1, -1, -1, -1, new int[0], new VarKind[0],
                new boolean[0], new String[paramSlots.length], paramSlots, paramKinds, new boolean[paramSlots.length],
                defaults, variadic, body);
    }

    public PhpFunctionRootNode(PhpLanguage language, FrameDescriptor fd, Source source, int line,
                               String declaringClass, String name, boolean isStatic, int thisSlot, int calledClassSlot,
                               int[] paramSlots, VarKind[] paramKinds, PhpNode[] defaults, boolean variadic, PhpNode body) {
        this(language, fd, source, line, declaringClass, name, isStatic, thisSlot, calledClassSlot, -1,
                paramSlots, paramKinds, defaults, variadic, body);
    }

    public PhpFunctionRootNode(PhpLanguage language, FrameDescriptor fd, Source source, int line,
                               String declaringClass, String name, boolean isStatic, int thisSlot, int calledClassSlot,
                               int traitSelfSlot, int[] paramSlots, VarKind[] paramKinds, PhpNode[] defaults,
                               boolean variadic, PhpNode body) {
        this(language, fd, source, line, declaringClass, name, isStatic, false, thisSlot, calledClassSlot, -1,
                traitSelfSlot, new int[0], new VarKind[0], new boolean[0], new String[paramSlots.length], paramSlots,
                paramKinds, new boolean[paramSlots.length], defaults, variadic, body);
    }

    public PhpFunctionRootNode(PhpLanguage language, FrameDescriptor fd, Source source, int line,
                               String declaringClass, String name, boolean isStatic,
                               int thisSlot, int calledClassSlot, int closureObjSlot, int traitSelfSlot,
                               int[] captureSlots, VarKind[] captureKinds, boolean[] captureByRef,
                               int[] paramSlots, VarKind[] paramKinds, PhpNode[] defaults, boolean variadic, PhpNode body) {
        this(language, fd, source, line, declaringClass, name, isStatic, false, thisSlot, calledClassSlot, closureObjSlot,
                traitSelfSlot, captureSlots, captureKinds, captureByRef, new String[paramSlots.length], paramSlots,
                paramKinds, new boolean[paramSlots.length], defaults, variadic, body);
    }

    public PhpFunctionRootNode(PhpLanguage language, FrameDescriptor fd, Source source, int line,
                               String declaringClass, String name, boolean isStatic, boolean returnsRef,
                               int thisSlot, int calledClassSlot, int closureObjSlot,
                               int[] captureSlots, VarKind[] captureKinds, boolean[] captureByRef,
                               String[] paramNames, int[] paramSlots, VarKind[] paramKinds, boolean[] paramByRef,
                               PhpNode[] defaults, boolean variadic, PhpNode body) {
        this(language, fd, source, line, declaringClass, name, isStatic, returnsRef, thisSlot, calledClassSlot,
                closureObjSlot, -1, captureSlots, captureKinds, captureByRef, paramNames, paramSlots, paramKinds,
                paramByRef, defaults, variadic, body);
    }

    /** The full form: {@code traitSelfSlot} >= 0 for methods declared in a trait. */
    public PhpFunctionRootNode(PhpLanguage language, FrameDescriptor fd, Source source, int line,
                               String declaringClass, String name, boolean isStatic, boolean returnsRef,
                               int thisSlot, int calledClassSlot, int closureObjSlot, int traitSelfSlot,
                               int[] captureSlots, VarKind[] captureKinds, boolean[] captureByRef,
                               String[] paramNames, int[] paramSlots, VarKind[] paramKinds, boolean[] paramByRef,
                               PhpNode[] defaults, boolean variadic, PhpNode body) {
        super(language, fd, source, line);
        this.scopeLayout = fd.getInfo() instanceof dev.phpruntime.runtime.PhpScopeLayout layout ? layout : null;
        this.declaringClassId = declaringClass == null ? -1 : language.classId(declaringClass);
        this.declaringClass = declaringClass;
        this.name = name;
        this.isStatic = isStatic;
        this.returnsRef = returnsRef;
        this.thisSlot = thisSlot;
        this.calledClassSlot = calledClassSlot;
        this.closureObjSlot = closureObjSlot;
        this.traitSelfSlot = traitSelfSlot;
        this.captureSlots = captureSlots;
        this.captureKinds = captureKinds;
        this.captureByRef = captureByRef;
        this.paramNames = paramNames;
        this.paramSlots = paramSlots;
        this.paramKinds = paramKinds;
        this.paramByRef = paramByRef;
        boolean anyRef = false;
        for (boolean b : paramByRef) {
            if (b) {
                anyRef = true;
                break;
            }
        }
        this.hasAnyRefParam = anyRef;
        this.defaults = defaults;
        this.variadic = variadic;
        this.body = body;
        int normalParams = variadic ? defaults.length - 1 : defaults.length;
        int required = 0;
        for (int i = 0; i < normalParams; i++) {
            if (defaults[i] == null) {
                required = i + 1;
            }
        }
        this.requiredArgs = required;
    }

    public String declaringClass() {
        return declaringClass;
    }

    /**
     * The function metadata of a call target's root: this node itself, or the AST root a
     * bytecode root was compiled from. Null for any other root.
     */
    public static PhpFunctionRootNode of(com.oracle.truffle.api.nodes.RootNode root) {
        if (root instanceof PhpFunctionRootNode f) {
            return f;
        }
        if (root instanceof dev.phpruntime.bytecode.PhpBytecodeRootNode b) {
            return b.function();
        }
        if (root instanceof dev.phpruntime.bytecode.GeneratorEntryRootNode g) {
            return g.function();
        }
        if (root instanceof com.oracle.truffle.api.bytecode.ContinuationRootNode c
                && c.getSourceRootNode() instanceof dev.phpruntime.bytecode.PhpBytecodeRootNode b) {
            return b.function();
        }
        return null;
    }

    /**
     * The PHP function a stack frame runs, also for a resumed generator (a bytecode
     * continuation), else null. Pair with {@link #functionFrame}.
     */
    public static PhpFunctionRootNode ofFrame(com.oracle.truffle.api.nodes.RootNode root) {
        return root instanceof dev.phpruntime.bytecode.GeneratorEntryRootNode ? null : of(root);
    }

    /** The frame holding a function's arguments and locals for a stack frame of {@code root}. */
    public static com.oracle.truffle.api.frame.Frame functionFrame(com.oracle.truffle.api.nodes.RootNode root,
                                                                   com.oracle.truffle.api.frame.Frame frame) {
        if (frame == null) {
            return null;
        }
        if (root instanceof com.oracle.truffle.api.bytecode.ContinuationRootNode) {
            return (com.oracle.truffle.api.frame.Frame) frame.getArguments()[0];
        }
        return frame;
    }

    /**
     * The class the innermost PHP function on the stack runs in, for builtins such as
     * {@code get_class()} without arguments: the using class inside a trait method, else the
     * declaring class. Null when the innermost guest frame is not a function (top-level code).
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static String callingClassName() {
        String[] found = new String[1];
        com.oracle.truffle.api.Truffle.getRuntime().iterateFrames(frameInstance -> {
            if (!(frameInstance.getCallTarget() instanceof com.oracle.truffle.api.RootCallTarget rct)
                    || !isGuestFrame(rct.getRootNode())) {
                return null;
            }
            com.oracle.truffle.api.nodes.RootNode root = rct.getRootNode();
            PhpFunctionRootNode fn = ofFrame(root);
            if (fn != null) {
                Object self = null;
                if (fn.traitSelfSlot() >= 0) {
                    self = traitSelf(root, fn, functionFrame(root,
                            frameInstance.getFrame(com.oracle.truffle.api.frame.FrameInstance.FrameAccess.READ_ONLY)));
                }
                found[0] = self instanceof dev.phpruntime.runtime.PhpClass c ? c.name() : fn.declaringClass();
            }
            return Boolean.TRUE;
        });
        return found[0];
    }

    /**
     * The trait's using class in a running trait method. The AST keeps it in its frame slot;
     * bytecode receives it as argument 2, or from the closure object for a closure.
     */
    private static Object traitSelf(com.oracle.truffle.api.nodes.RootNode root, PhpFunctionRootNode fn,
                                    com.oracle.truffle.api.frame.Frame frame) {
        if (frame == null) {
            return null;
        }
        if (root instanceof PhpFunctionRootNode) {
            return frame.getObject(fn.traitSelfSlot());
        }
        Object[] args = frame.getArguments();
        if (args.length <= 2) {
            return null;
        }
        if (fn.closureObjSlot() >= 0) {
            return args[2] instanceof dev.phpruntime.runtime.PhpObject obj && obj.closure() != null
                    ? obj.closure().traitSelf() : null;
        }
        return args[2];
    }

    /** Whether a stack frame of {@code root} is guest PHP code (backtraces skip the others). */
    public static boolean isGuestFrame(com.oracle.truffle.api.nodes.RootNode root) {
        return root instanceof PhpRootNode || root instanceof com.oracle.truffle.api.bytecode.ContinuationRootNode c
                && c.getSourceRootNode() instanceof dev.phpruntime.bytecode.PhpBytecodeRootNode;
    }

    // Read by the bytecode compiler.
    public PhpNode body() { return body; }
    public PhpNode[] defaults() { return defaults; }
    public int[] paramSlots() { return paramSlots; }
    public VarKind[] paramKinds() { return paramKinds; }
    public int thisSlot() { return thisSlot; }
    public int calledClassSlot() { return calledClassSlot; }
    public int closureObjSlot() { return closureObjSlot; }
    public int[] captureSlots() { return captureSlots; }
    public VarKind[] captureKinds() { return captureKinds; }
    public boolean[] captureByRef() { return captureByRef; }
    public boolean usesSymbolTable() { return scopeLayout != null; }
    public boolean isGenerator() { return generator; }
    public int line() { return getSourceSection() != null ? getSourceSection().getStartLine() : -1; }

    public PhpFunctionRootNode withGeneratorSource(PhpNode source) {
        this.generatorSource = source;
        return this;
    }

    public PhpNode generatorSource() { return generatorSource; }

    public PhpFunctionRootNode asGenerator() {
        generator = true;
        return this;
    }

    public PhpFunctionRootNode closureMetadata(boolean usesThis) {
        this.usesThis = usesThis;
        return this;
    }

    public boolean usesThis() {
        return usesThis;
    }

    public int traitSelfSlot() {
        return traitSelfSlot;
    }

    public int argOffset() {
        if (closureObjSlot >= 0 || traitSelfSlot >= 0) {
            return 3;
        }
        return thisSlot >= 0 ? 2 : 0;
    }

    public String rawFunctionName() {
        return name;
    }

    public boolean isMethod() {
        return thisSlot >= 0 && closureObjSlot < 0;
    }

    public boolean isClosure() {
        return closureObjSlot >= 0;
    }

    public boolean isStaticMethod() {
        return isStatic;
    }

    public boolean returnsRef() {
        return returnsRef;
    }

    public boolean hasAnyRefParam() {
        return hasAnyRefParam;
    }

    public boolean isParamByRef(int i) {
        if (i < paramByRef.length) {
            return paramByRef[i];
        }
        return variadic && paramByRef.length > 0 && paramByRef[paramByRef.length - 1];
    }

    public boolean[] paramByRef() {
        return paramByRef;
    }

    public String[] paramNames() {
        return paramNames;
    }

    public String paramName(int i) {
        return i >= 0 && i < paramNames.length ? paramNames[i] : null;
    }

    public boolean isVariadic() {
        return variadic;
    }

    public int normalParamCount() {
        return variadic ? paramSlots.length - 1 : paramSlots.length;
    }

    public int requiredArgs() {
        return requiredArgs;
    }

    public String qualifiedCallableName() {
        if (isClosure()) {
            String f = source() != null && source().getPath() != null && !source().getPath().isEmpty()
                    ? source().getPath()
                    : (source() != null ? source().getName() : "");
            int l = getSourceSection() != null ? getSourceSection().getStartLine() : 0;
            return "{closure:" + dev.phpruntime.runtime.PhpStrings.fromJava(f) + ":" + l + "}";
        }
        return declaringClass == null ? name : declaringClass + "::" + name;
    }

    /**
     * The invocation's symbol table: {@code values} holds the captured variables' then the
     * parameters' frame values (cells for by-reference ones), in slot order. Shared with the
     * bytecode prologue.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public dev.phpruntime.runtime.PhpSymbolTable newSymbolTable(dev.phpruntime.PhpContext ctx, Object thisValue,
                                                                Object calledClass, Object traitSelf, Object[] values) {
        dev.phpruntime.runtime.PhpClass lexical = traitSelf instanceof dev.phpruntime.runtime.PhpClass c ? c
                : declaringClassId >= 0 ? ctx.classById(declaringClassId) : null;
        dev.phpruntime.runtime.PhpSymbolTable table = new dev.phpruntime.runtime.PhpSymbolTable(scopeLayout,
                thisValue == null ? PhpNull.INSTANCE : thisValue,
                calledClass instanceof dev.phpruntime.runtime.PhpClass c ? c : null, lexical);
        for (int i = 0; i < captureSlots.length; i++) {
            Object value = values[i];
            table.cells[captureSlots[i]] = captureByRef[i] ? ((PhpReference) value).alias()
                    : PhpReference.local(value instanceof PhpReference ref ? ref.getValue() : value);
        }
        for (int i = 0; i < paramSlots.length; i++) {
            Object value = values[captureSlots.length + i];
            table.cells[paramSlots[i]] = paramByRef[i] && !(variadic && i == paramSlots.length - 1) ? ((PhpReference) value).alias()
                    : PhpReference.local(value instanceof PhpReference ref ? ref.getValue() : value);
        }
        return table;
    }

    public dev.phpruntime.runtime.PhpScopeLayout scopeLayout() { return scopeLayout; }

    /**
     * Collects the arguments from {@code from} on into the variadic parameter's array. Behind a
     * boundary: its bound is the call's argument count, which the caller cannot unroll
     * (apply_filters and do_action failed to compile on it).
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static PhpArray packVariadic(Object[] args, int from, boolean varByRef) {
        PhpArray rest = new PhpArray();
        for (int i = from; i < args.length; i++) {
            Object item = args[i];
            if (item instanceof NamedVariadic nv) {
                for (java.util.Map.Entry<Object, Object> e : nv.entries().rawEntries()) {
                    if (e.getValue() instanceof PhpReference ref && varByRef) {
                        rest.putRef(e.getKey(), ref);
                    } else {
                        Object val = e.getValue() instanceof PhpReference ref ? ref.getValue() : e.getValue();
                        rest.put(e.getKey(), PhpOps.copyOnAssign(val));
                    }
                }
            } else if (varByRef && item instanceof PhpReference ref) {
                rest.appendRef(ref);
            } else {
                Object val = item instanceof PhpReference ref ? ref.getValue() : item;
                rest.append(PhpOps.copyOnAssign(val));
            }
        }
        return rest;
    }

    public record NamedVariadic(PhpArray entries) {
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public PhpError missingArgument(int index) {
        String qualified = declaringClass == null ? name : declaringClass + "::" + name;
        String pName = paramName(index);
        String suffix = pName != null ? " ($" + pName + ")" : "";
        return new PhpError(PhpError.ARGUMENT_COUNT,
                qualified + "(): Argument #" + (index + 1) + suffix + " not passed",
                this);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public PhpError tooFewArguments(int passed) {
        String exactly = requiredArgs == paramSlots.length ? "exactly" : "at least";
        String qualified = declaringClass == null ? name : declaringClass + "::" + name;
        dev.phpruntime.runtime.PhpBacktrace.LazyTrace trace = dev.phpruntime.runtime.PhpBacktrace.capture(this, false);
        dev.phpruntime.runtime.PhpBacktrace.FrameEntry[] frames = trace.frames();
        String passedWhere = "";
        if (frames.length > 0 && frames[0].file() != null && !frames[0].file().isEmpty() && frames[0].line() > 0) {
            passedWhere = " in " + frames[0].file() + " on line " + frames[0].line();
        }
        String declFile = source() != null && source().getPath() != null ? source().getPath() : "";
        int declLine = getSourceSection() != null ? getSourceSection().getStartLine() : 0;
        return new PhpError(PhpError.ARGUMENT_COUNT, "Too few arguments to function " + qualified + "(), " + passed
                + " passed" + passedWhere + " and " + exactly + " " + requiredArgs + " expected",
                declFile, declLine, this).withTrace(trace);
    }

    @Override
    public String getName() {
        return declaringClass == null ? name : declaringClass + "::" + name;
    }

    @Override
    public String toString() {
        return name + "()";
    }
}
