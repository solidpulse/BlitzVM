package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpClass;

/** Resolves {@code __CLASS__} inside a trait method to the using class's name. */
public final class TraitClassNameNode extends PhpNode {

    private final int traitSelfSlot;

    public TraitClassNameNode(int traitSelfSlot) {
        this.traitSelfSlot = traitSelfSlot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpClass cls = (PhpClass) frame.getObject(traitSelfSlot);
        return cls != null ? cls.name() : "";
    }
}
