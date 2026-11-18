package dev.phpruntime.nodes;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.Source;
import dev.phpruntime.PhpLanguage;

/** Lightweight root node for evaluating class constant and property default expressions. */
public final class PhpExprRootNode extends PhpRootNode {

    @Child private PhpNode expr;
    private final int traitSelfSlot;

    public PhpExprRootNode(PhpLanguage language, Source source, PhpNode expr) {
        this(language, FrameDescriptor.newBuilder().build(), source, expr, -1);
    }

    public PhpExprRootNode(PhpLanguage language, FrameDescriptor fd, Source source, PhpNode expr, int traitSelfSlot) {
        super(language, fd, source, expr.line());
        this.expr = expr;
        this.traitSelfSlot = traitSelfSlot;
    }

    public boolean hasTraitSelf() {
        return traitSelfSlot >= 0;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (dev.phpruntime.bytecode.BytecodeEmitter.ALL) {
            throw dev.phpruntime.bytecode.BytecodeEmitter.astExecuted(this);
        }
        if (traitSelfSlot >= 0) {
            Object[] args = frame.getArguments();
            frame.setObject(traitSelfSlot, args.length > 0 ? args[0] : null);
        }
        return expr.execute(frame);
    }
}
