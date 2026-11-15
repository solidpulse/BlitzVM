package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpError;

/** Declares a class or interface at runtime when not already hoisted at file start. */
public final class DeclareClassNode extends PhpNode {

    private final PhpClassTemplate template;
    private final int hoistedSlot;
    private final int topLevelIndex;

    public DeclareClassNode(PhpClassTemplate template, int hoistedSlot, int topLevelIndex) {
        this.template = template;
        this.hoistedSlot = hoistedSlot;
        this.topLevelIndex = topLevelIndex;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (hoistedSlot >= 0) {
            boolean[] hoisted = (boolean[]) frame.getObject(hoistedSlot);
            if (hoisted[topLevelIndex]) {
                return null;
            }
        }
        try {
            PhpContext.get(this).declareClass(template);
        } catch (PhpError e) {
            throw e.at(this);
        }
        return null;
    }
}
