package dev.phpruntime.nodes;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.Source;
import dev.phpruntime.PhpLanguage;

/** Lightweight root node for evaluating class constant and property default expressions. */
public final class PhpExprRootNode extends PhpRootNode {

    @Child private PhpNode expr;

    public PhpExprRootNode(PhpLanguage language, Source source, PhpNode expr) {
        super(language, FrameDescriptor.newBuilder().build(), source, expr.line());
        this.expr = expr;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return expr.execute(frame);
    }
}
