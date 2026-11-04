package dev.phpruntime.nodes;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import dev.phpruntime.PhpLanguage;

/** Common base for script and function roots: knows its Source so nodes can report lines. */
public abstract class PhpRootNode extends RootNode {

    private final Source source;
    private final int line;

    protected PhpRootNode(PhpLanguage language, FrameDescriptor fd, Source source, int line) {
        super(language, fd);
        this.source = source;
        this.line = line;
    }

    public final Source source() {
        return source;
    }

    @Override
    public SourceSection getSourceSection() {
        return PhpNode.section(source, line);
    }
}
