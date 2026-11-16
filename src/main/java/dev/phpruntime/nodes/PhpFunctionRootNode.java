package dev.phpruntime.nodes;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
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
    @CompilationFinal private com.oracle.truffle.api.RootCallTarget generatorTarget;
    @CompilationFinal private int generatorSlot = -1;
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

    public PhpFunctionRootNode asGenerator(PhpLanguage language, int slot) {
        generatorSlot = slot;
        PhpNode generatorBody = body;
        body = null;
        generatorTarget = new dev.phpruntime.nodes.generator.GeneratorResumeRootNode(
                language, getFrameDescriptor(), this, generatorBody).getCallTarget();
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

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        TruffleSafepoint.poll(this);
        Object[] args = frame.getArguments();
        int offset = argOffset();
        int passed = args.length - offset;
        if (passed < requiredArgs) {
            throw tooFewArguments(passed);
        }
        if (thisSlot >= 0) {
            frame.setObject(thisSlot, args[0]);
        }
        if (calledClassSlot >= 0) {
            frame.setObject(calledClassSlot, args[1]);
        }
        if (closureObjSlot >= 0) {
            PhpObject closureObj = (PhpObject) args[2];
            frame.setObject(closureObjSlot, closureObj);
            if (traitSelfSlot >= 0) {
                frame.setObject(traitSelfSlot, closureObj.closure().traitSelf());
            }
            Object[] caps = closureObj.closure().captures();
            for (int i = 0; i < captureSlots.length; i++) {
                if (captureByRef[i]) {
                    frame.setObject(captureSlots[i], caps[i]);
                } else {
                    writeSlot(frame, captureSlots[i], captureKinds[i], PhpOps.copyOnAssign(caps[i]));
                }
            }
        } else if (traitSelfSlot >= 0) {
            frame.setObject(traitSelfSlot, args[2]);
        }
        int normalParams = variadic ? paramSlots.length - 1 : paramSlots.length;
        for (int i = 0; i < normalParams; i++) {
            Object raw = i < passed ? args[offset + i] : UNPASSED;
            if (raw == UNPASSED) {
                if (defaults[i] == null) {
                    throw missingArgument(i);
                }
                Object defVal = PhpOps.copyOnAssign(defaults[i].execute(frame));
                writeSlot(frame, paramSlots[i], paramKinds[i], defVal);
            } else if (paramByRef[i]) {
                PhpReference cell = raw instanceof PhpReference ref ? ref : new PhpReference(PhpOps.copyOnAssign(raw));
                frame.setObject(paramSlots[i], cell);
            } else {
                Object val = raw instanceof PhpReference ref ? PhpOps.copyOnAssign(ref.getValue()) : raw;
                writeSlot(frame, paramSlots[i], paramKinds[i], val);
            }
        }
        if (variadic) {
            PhpArray rest;
            boolean varByRef = paramByRef[normalParams];
            if (passed == normalParams + 1 && args[offset + normalParams] instanceof NamedVariadic nv) {
                rest = nv.entries();
            } else {
                rest = new PhpArray();
                for (int i = offset + normalParams; i < args.length; i++) {
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
            }
            writeSlot(frame, paramSlots[normalParams], paramKinds[normalParams], rest);
        }
        if (scopeLayout != null) { initializeSymbols(frame); }
        if (generatorTarget != null) {
            dev.phpruntime.PhpContext ctx = dev.phpruntime.PhpContext.get(this);
            initializeGeneratorSlots(frame);
            dev.phpruntime.runtime.PhpGenerator generator = new dev.phpruntime.runtime.PhpGenerator(
                    frame.materialize(), generatorTarget, this);
            frame.setObject(generatorSlot, generator);
            PhpObject object = new PhpObject(ctx.lookupClass("Generator"), ctx.nextObjectId());
            object.setNativeState(generator);
            return object;
        }
        try {
            body.executeVoid(frame);
            return PhpNull.INSTANCE;
        } catch (ControlFlow.Return r) {
            return r.value;
        }
    }

    @ExplodeLoop
    private void initializeSymbols(VirtualFrame frame) {
        dev.phpruntime.runtime.PhpClass lexical = traitSelfSlot >= 0
                ? (dev.phpruntime.runtime.PhpClass) frame.getObject(traitSelfSlot)
                : declaringClassId >= 0 ? dev.phpruntime.PhpContext.get(this).classById(declaringClassId) : null;
        dev.phpruntime.runtime.PhpSymbolTable table = new dev.phpruntime.runtime.PhpSymbolTable(scopeLayout,
                thisSlot >= 0 ? frame.getObject(thisSlot) : PhpNull.INSTANCE,
                calledClassSlot >= 0 ? (dev.phpruntime.runtime.PhpClass) frame.getObject(calledClassSlot) : null, lexical);
        for (int i = 0; i < captureSlots.length; i++) {
            int slot = captureSlots[i];
            Object value = frame.getValue(slot);
            table.cells[slot] = captureByRef[i] ? ((PhpReference) value).alias()
                    : PhpReference.local(value instanceof PhpReference ref ? ref.getValue() : value);
        }
        for (int i = 0; i < paramSlots.length; i++) {
            int slot = paramSlots[i];
            Object value = frame.getValue(slot);
            table.cells[slot] = paramByRef[i] && !(variadic && i == paramSlots.length - 1) ? ((PhpReference) value).alias()
                    : PhpReference.local(value instanceof PhpReference ref ? ref.getValue() : value);
        }
        frame.setObject(scopeLayout.tableSlot(), table);
    }

    @ExplodeLoop
    private void initializeGeneratorSlots(VirtualFrame frame) {
        com.oracle.truffle.api.frame.FrameDescriptor descriptor = getFrameDescriptor();
        for (int i = 0; i < descriptor.getNumberOfSlots(); i++) {
            Object name = descriptor.getSlotName(i);
            if (name instanceof String slot && slot.startsWith("@resume")) {
                frame.setInt(i, 0);
            }
        }
    }

    private static void writeSlot(VirtualFrame frame, int slot, VarKind kind, Object v) {
        if (kind.isCell()) {
            frame.setObject(slot, new PhpReference(v));
        } else if (v instanceof Long l && !kind.longBoxed()) {
            frame.setLong(slot, l);
        } else if (v instanceof Double d && !kind.doubleBoxed()) {
            frame.setDouble(slot, d);
        } else {
            frame.setObject(slot, v);
        }
    }

    public record NamedVariadic(PhpArray entries) {
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private PhpError missingArgument(int index) {
        String qualified = declaringClass == null ? name : declaringClass + "::" + name;
        String pName = paramName(index);
        String suffix = pName != null ? " ($" + pName + ")" : "";
        return new PhpError(PhpError.ARGUMENT_COUNT,
                qualified + "(): Argument #" + (index + 1) + suffix + " not passed",
                this);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private PhpError tooFewArguments(int passed) {
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
