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
    @Child private com.oracle.truffle.api.nodes.IndirectCallNode call = com.oracle.truffle.api.nodes.IndirectCallNode.create();
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
        com.oracle.truffle.api.CallTarget target = prepare(PhpOps.toStr(code.execute(frame)));
        return call.call(target, tableSlot < 0 ? new Object[]{EVAL_MODE}
                : new Object[]{EVAL_MODE, frame.getObject(tableSlot)});
    }

    @TruffleBoundary
    private com.oracle.truffle.api.CallTarget prepare(String code) {
        PhpContext ctx = PhpContext.get(this);
        Source source = Source.newBuilder(PhpLanguage.ID, "<?php " + code, "eval()'d code").cached(true).build();
        try {
            return tableSlot < 0 ? ctx.language().parseEvalBytes(source) : ctx.language().parseLocalBytes(source);
        } catch (dev.phpruntime.parser.PhpParseError e) {
            throw new dev.phpruntime.runtime.PhpError(dev.phpruntime.runtime.PhpError.PARSE_ERROR, e.getMessage(), this);
        }
    }
}
