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

    private final String declaringClass;
    private final String name;
    private final boolean isStatic;
    private final int thisSlot;
    private final int calledClassSlot;
    private final int closureObjSlot;
    @CompilationFinal(dimensions = 1) private final int[] captureSlots;
    @CompilationFinal(dimensions = 1) private final VarKind[] captureKinds;
    @CompilationFinal(dimensions = 1) private final boolean[] captureByRef;
    @CompilationFinal(dimensions = 1) private final int[] paramSlots;
    @CompilationFinal(dimensions = 1) private final VarKind[] paramKinds;
    @Children private final PhpNode[] defaults;
    private final boolean variadic;
    private final int requiredArgs;
    @Child private PhpNode body;

    public PhpFunctionRootNode(PhpLanguage language, FrameDescriptor fd, Source source, int line, String name,
                               int[] paramSlots, VarKind[] paramKinds, PhpNode[] defaults, boolean variadic, PhpNode body) {
        this(language, fd, source, line, null, name, false, -1, -1, -1, new int[0], new VarKind[0], new boolean[0],
                paramSlots, paramKinds, defaults, variadic, body);
    }

    public PhpFunctionRootNode(PhpLanguage language, FrameDescriptor fd, Source source, int line,
                               String declaringClass, String name, boolean isStatic, int thisSlot, int calledClassSlot,
                               int[] paramSlots, VarKind[] paramKinds, PhpNode[] defaults, boolean variadic, PhpNode body) {
        this(language, fd, source, line, declaringClass, name, isStatic, thisSlot, calledClassSlot, -1,
                new int[0], new VarKind[0], new boolean[0], paramSlots, paramKinds, defaults, variadic, body);
    }

    public PhpFunctionRootNode(PhpLanguage language, FrameDescriptor fd, Source source, int line,
                               String declaringClass, String name, boolean isStatic,
                               int thisSlot, int calledClassSlot, int closureObjSlot,
                               int[] captureSlots, VarKind[] captureKinds, boolean[] captureByRef,
                               int[] paramSlots, VarKind[] paramKinds, PhpNode[] defaults, boolean variadic, PhpNode body) {
        super(language, fd, source, line);
        this.declaringClass = declaringClass;
        this.name = name;
        this.isStatic = isStatic;
        this.thisSlot = thisSlot;
        this.calledClassSlot = calledClassSlot;
        this.closureObjSlot = closureObjSlot;
        this.captureSlots = captureSlots;
        this.captureKinds = captureKinds;
        this.captureByRef = captureByRef;
        this.paramSlots = paramSlots;
        this.paramKinds = paramKinds;
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

    public int argOffset() {
        if (closureObjSlot >= 0) {
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
            Object[] caps = closureObj.closure().captures();
            for (int i = 0; i < captureSlots.length; i++) {
                if (captureByRef[i]) {
                    frame.setObject(captureSlots[i], caps[i]);
                } else {
                    writeSlot(frame, captureSlots[i], captureKinds[i], PhpOps.copyOnAssign(caps[i]));
                }
            }
        }
        int normalParams = variadic ? paramSlots.length - 1 : paramSlots.length;
        for (int i = 0; i < normalParams; i++) {
            Object v = i < passed ? args[offset + i] : defaults[i].execute(frame);
            writeSlot(frame, paramSlots[i], paramKinds[i], v);
        }
        if (variadic) {
            PhpArray rest = new PhpArray();
            for (int i = offset + normalParams; i < args.length; i++) {
                rest.append(PhpOps.copyOnAssign(args[i]));
            }
            writeSlot(frame, paramSlots[normalParams], paramKinds[normalParams], rest);
        }
        try {
            body.executeVoid(frame);
            return PhpNull.INSTANCE;
        } catch (ControlFlow.Return r) {
            return r.value;
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
