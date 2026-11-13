package dev.phpruntime.js.nodes;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import dev.phpruntime.js.JsLanguage;
import dev.phpruntime.js.runtime.JsValues.Undefined;

/**
 * A function body. Call arguments are laid out as {@code [moduleScope, this, a0, a1, ...]}.
 * The safepoint poll on entry is what lets the scheduler preempt deep recursion as well
 * as loops (loops poll on their back-edges through Truffle's LoopNode).
 */
public final class JsFunctionRootNode extends RootNode {

    @Child private JsNode body;
    @CompilationFinal(dimensions = 1) private final int[] paramSlots;
    @CompilationFinal(dimensions = 1) private final JsVariables.SlotKind[] paramKinds;
    private final String name;
    private final Source source;
    private final int line;

    public JsFunctionRootNode(JsLanguage language, FrameDescriptor fd, JsNode body, int[] paramSlots,
                              JsVariables.SlotKind[] paramKinds, String name, Source source, int line) {
        super(language, fd);
        this.body = body;
        this.paramSlots = paramSlots;
        this.paramKinds = paramKinds;
        this.name = name;
        this.source = source;
        this.line = line;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        TruffleSafepoint.poll(this);
        Object[] args = frame.getArguments();
        for (int i = 0; i < paramSlots.length; i++) {
            Object v = i + 2 < args.length ? args[i + 2] : Undefined.INSTANCE;
            if (v instanceof Integer n) {
                if (paramKinds[i].holdsDoubles()) {
                    frame.setDouble(paramSlots[i], n);
                } else {
                    frame.setInt(paramSlots[i], n);
                }
            } else if (v instanceof Double d) {
                paramKinds[i].sawDouble();
                frame.setDouble(paramSlots[i], d);
            } else {
                frame.setObject(paramSlots[i], v);
            }
        }
        try {
            body.executeVoid(frame);
        } catch (JsControl.Return r) {
            return r.value;
        }
        return Undefined.INSTANCE;
    }

    @Override
    public String getName() {
        return name.isEmpty() ? "(anonymous)" : name;
    }

    @Override
    public SourceSection getSourceSection() {
        return source != null && line > 0 && line <= source.getLineCount() ? source.createSection(line) : null;
    }
}
