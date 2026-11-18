package dev.phpruntime.nodes.stmt;

import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpClassTemplate;

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
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.builder().beginDeclareClass(template, hoistedSlot >= 0 ? topLevelIndex : -1);
        e.loadHoisted();
        e.builder().endDeclareClass();
    }
}
