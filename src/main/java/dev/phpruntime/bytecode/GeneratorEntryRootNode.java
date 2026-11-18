package dev.phpruntime.bytecode;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.runtime.PhpGenerator;
import dev.phpruntime.runtime.PhpObject;

/**
 * The call target of a generator function on bytecode. The bytecode root binds the arguments
 * (so argument errors are raised by the call, as in php) and suspends at its start yield; this
 * wraps that continuation in a Generator object. It is not guest code: backtraces skip it.
 */
public final class GeneratorEntryRootNode extends RootNode {
    private final PhpFunctionRootNode function;
    @Child private DirectCallNode body;

    GeneratorEntryRootNode(PhpLanguage language, PhpFunctionRootNode function, RootCallTarget bytecode) {
        super(language);
        this.function = function;
        this.body = DirectCallNode.create(bytecode);
    }

    public PhpFunctionRootNode function() {
        return function;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        ContinuationResult start = (ContinuationResult) body.call(frame.getArguments());
        PhpContext ctx = PhpContext.get(this);
        PhpObject object = new PhpObject(ctx.lookupClass("Generator"), ctx.nextObjectId());
        object.setNativeState(new PhpGenerator(start, function));
        return object;
    }

    @Override
    public String getName() {
        return function.getName();
    }

    @Override
    public SourceSection getSourceSection() {
        return function.getSourceSection();
    }

    @Override
    public boolean isCloningAllowed() {
        return true;
    }
}
