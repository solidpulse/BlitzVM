package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.source.Source;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.PhpNode;

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

    /**
     * Parses eval'd code: into the global scope, or ({@code local}) into the caller's symbol
     * table, which the target receives as its second argument. A parse error is located in
     * the eval'd code, as php reports it: {@code caller.php(12) : eval()'d code}, line within.
     */
    @TruffleBoundary
    public static com.oracle.truffle.api.CallTarget prepare(PhpContext ctx, String code, boolean local,
                                                           String callerPath, int callerLine,
                                                           com.oracle.truffle.api.nodes.Node site) {
        Source source = Source.newBuilder(PhpLanguage.ID, "<?php " + code, "eval()'d code").cached(true).build();
        try {
            return local ? ctx.language().parseLocalBytes(source) : ctx.language().parseEvalBytes(source);
        } catch (dev.phpruntime.parser.PhpParseError e) {
            String file = (callerPath != null ? callerPath : "") + "(" + callerLine + ") : eval()'d code";
            throw new dev.phpruntime.runtime.PhpError(dev.phpruntime.runtime.PhpError.PARSE_ERROR, e.getMessage(),
                    file, e.line(), site);
        }
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginEval();
        e.expr(code);
        e.loadTable(tableSlot);
        b.endEval();
    }
}
