package dev.phpruntime.nodes;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.source.Source;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;

/**
 * Root of a user function. Binds arguments (or defaults) to frame slots, runs the
 * body and turns a Return control-flow exception into the result. Polls a safepoint
 * on entry so deep recursion without loops can still be preempted.
 */
public final class PhpFunctionRootNode extends PhpRootNode {

    private final String name;
    @CompilationFinal(dimensions = 1) private final int[] paramSlots;
    @Children private final PhpNode[] defaults;
    private final int requiredArgs;
    @Child private PhpNode body;

    public PhpFunctionRootNode(PhpLanguage language, FrameDescriptor fd, Source source, int line, String name,
                               int[] paramSlots, PhpNode[] defaults, PhpNode body) {
        super(language, fd, source, line);
        this.name = name;
        this.paramSlots = paramSlots;
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

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        TruffleSafepoint.poll(this);
        Object[] args = frame.getArguments();
        if (args.length < requiredArgs) {
            throw tooFewArguments(args.length);
        }
        for (int i = 0; i < paramSlots.length; i++) {
            Object v = i < args.length ? args[i] : defaults[i].execute(frame);
            frame.setObject(paramSlots[i], v);
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
        return new PhpError(PhpError.ARGUMENT_COUNT, "Too few arguments to function " + name + "(), " + passed
                + " passed and " + exactly + " " + requiredArgs + " expected", body);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name + "()";
    }
}
