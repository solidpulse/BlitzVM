package dev.phpruntime.nodes;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.source.Source;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.var.VarKind;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;

/**
 * Root of a user function. Binds arguments (or defaults) to frame slots, runs the
 * body and turns a Return control-flow exception into the result. Polls a safepoint
 * on entry so deep recursion without loops can still be preempted.
 */
public final class PhpFunctionRootNode extends PhpRootNode {

    private final String declaringClass;
    private final String name;
    private final int thisSlot;
    private final int calledClassSlot;
    @CompilationFinal(dimensions = 1) private final int[] paramSlots;
    @CompilationFinal(dimensions = 1) private final VarKind[] paramKinds;
    @Children private final PhpNode[] defaults;
    private final int requiredArgs;
    @Child private PhpNode body;

    public PhpFunctionRootNode(PhpLanguage language, FrameDescriptor fd, Source source, int line, String name,
                               int[] paramSlots, VarKind[] paramKinds, PhpNode[] defaults, PhpNode body) {
        this(language, fd, source, line, null, name, -1, -1, paramSlots, paramKinds, defaults, body);
    }

    public PhpFunctionRootNode(PhpLanguage language, FrameDescriptor fd, Source source, int line,
                               String declaringClass, String name, int thisSlot, int calledClassSlot,
                               int[] paramSlots, VarKind[] paramKinds, PhpNode[] defaults, PhpNode body) {
        super(language, fd, source, line);
        this.declaringClass = declaringClass;
        this.name = name;
        this.thisSlot = thisSlot;
        this.calledClassSlot = calledClassSlot;
        this.paramSlots = paramSlots;
        this.paramKinds = paramKinds;
        this.defaults = defaults;
        this.body = body;
        int required = 0;
        for (int i = 0; i < defaults.length; i++) {
            if (defaults[i] == null) {
                required = i + 1;
            }
        }
        this.requiredArgs = required;
    }

    public String declaringClass() {
        return declaringClass;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        TruffleSafepoint.poll(this);
        Object[] args = frame.getArguments();
        int offset = thisSlot >= 0 ? 2 : 0;
        int passed = args.length - offset;
        if (passed < requiredArgs) {
            throw tooFewArguments(passed);
        }
        if (thisSlot >= 0) {
            frame.setObject(thisSlot, args[0]);
            frame.setObject(calledClassSlot, args[1]);
        }
        for (int i = 0; i < paramSlots.length; i++) {
            Object v = i < passed ? args[offset + i] : defaults[i].execute(frame);
            if (v instanceof Long l && !paramKinds[i].longBoxed()) {
                frame.setLong(paramSlots[i], l);
            } else if (v instanceof Double d && !paramKinds[i].doubleBoxed()) {
                frame.setDouble(paramSlots[i], d);
            } else {
                frame.setObject(paramSlots[i], v);
            }
        }
        try {
            body.executeVoid(frame);
            return PhpNull.INSTANCE;
        } catch (ControlFlow.Return r) {
            return r.value;
        }
    }

    private PhpError tooFewArguments(int passed) {
        String exactly = requiredArgs == paramSlots.length ? "exactly" : "at least";
        String qualified = declaringClass == null ? name : declaringClass + "::" + name;
        return new PhpError(PhpError.ARGUMENT_COUNT, "Too few arguments to function " + qualified + "(), " + passed
                + " passed and " + exactly + " " + requiredArgs + " expected", body);
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
