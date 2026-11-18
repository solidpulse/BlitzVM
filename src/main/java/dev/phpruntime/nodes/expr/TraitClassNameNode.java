package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpClass;

/** Resolves {@code __CLASS__} inside a trait method to the using class's name. */
public final class TraitClassNameNode extends PhpNode {

    private final int traitSelfSlot;

    public TraitClassNameNode(int traitSelfSlot) {
        this.traitSelfSlot = traitSelfSlot;
    }

    public int traitSelfSlot() {
        return traitSelfSlot;
    }

    /** The using class's name for {@code __CLASS__}, or an empty string when there is none. */
    public static String nameOf(Object traitSelf) {
        return traitSelf instanceof PhpClass cls ? cls.name() : "";
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginTraitClassName();
        e.loadSlotOrNull(traitSelfSlot);
        b.endTraitClassName();
    }
}
