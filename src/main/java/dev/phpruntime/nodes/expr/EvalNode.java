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
    private final int tableSlot;

    public EvalNode(PhpNode code) {
        this(code, -1);
    }

    public EvalNode(PhpNode code, int tableSlot) {
        this.code = code;
        this.tableSlot = tableSlot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return evaluate(PhpOps.toStr(code.execute(frame)), tableSlot >= 0 ? frame.getObject(tableSlot) : null);
    }

    @TruffleBoundary
    private Object evaluate(String code, Object symbols) {
        PhpContext ctx = PhpContext.get(this);
        Source source = Source.newBuilder(PhpLanguage.ID, "<?php " + code, "eval()'d code").cached(true).build();
        try {
            return symbols == null ? ctx.language().parseBytes(source).call(new Object[]{EVAL_MODE})
                    : ctx.language().parseLocalBytes(source).call(new Object[]{EVAL_MODE, symbols});
        } catch (dev.phpruntime.parser.PhpParseError e) {
            throw new dev.phpruntime.runtime.PhpError(dev.phpruntime.runtime.PhpError.PARSE_ERROR, e.getMessage(), this);
        }
    }
}
