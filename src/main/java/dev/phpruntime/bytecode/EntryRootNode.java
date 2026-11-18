package dev.phpruntime.bytecode;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.PhpMainRootNode;

/**
 * The call target of a file whose top-level code runs as bytecode. Called with no arguments it
 * is the request's entry script: it starts the request, runs the file's bytecode as
 * {@code {main}}, reports an uncaught exception and finishes the request, as
 * {@link PhpMainRootNode#execute} does for the AST. An include or eval passes its arguments
 * through to the bytecode. Not a {@code PhpRootNode}, so backtraces skip it.
 */
public final class EntryRootNode extends RootNode {

    private final PhpMainRootNode main;
    @Child private DirectCallNode body;

    EntryRootNode(PhpLanguage language, PhpMainRootNode main, RootCallTarget bodyTarget) {
        super(language);
        this.main = main;
        this.body = DirectCallNode.create(bodyTarget);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        if (args.length != 0) {
            return body.call(args);
        }
        PhpContext ctx = PhpContext.get(this);
        return main.runEntry(ctx, () -> body.call(PhpMainRootNode.ENTRY_BODY_ARGS));
    }

    @Override
    public String getName() {
        return "{main}";
    }

    @Override
    public SourceSection getSourceSection() {
        return main.getSourceSection();
    }

    @Override
    public boolean isCloningAllowed() {
        return true;
    }
}
