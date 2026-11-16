package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.Source;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpOps;

/** eval's source is already a PHP byte string, not Java text. */
public final class EvalNode extends PhpNode {
    public static final Object EVAL_MODE = new Object();
    @Child private PhpNode code;

    public EvalNode(PhpNode code) {
        this.code = code;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return evaluate(PhpOps.toStr(code.execute(frame)));
    }

    @TruffleBoundary
    private Object evaluate(String code) {
        PhpContext ctx = PhpContext.get(this);
        Source source = Source.newBuilder(PhpLanguage.ID, "<?php " + code, "eval()'d code").cached(true).build();
        // Match the existing include implementation's global-scope execution. No request
        // object or parsed target is stored in this shared AST node.
        try {
            return ctx.language().parseBytes(source).call(new Object[]{EVAL_MODE});
        } catch (dev.phpruntime.parser.PhpParseError e) {
            throw new dev.phpruntime.runtime.PhpError(dev.phpruntime.runtime.PhpError.PARSE_ERROR, e.getMessage(), this);
        }
    }
}
