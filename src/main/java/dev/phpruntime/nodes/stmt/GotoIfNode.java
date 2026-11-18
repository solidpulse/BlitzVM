package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.expr.CondNode;

/** {@code if/else} whose branches contain labels reachable from outside via {@code goto}. */
public final class GotoIfNode extends PhpNode implements GotoEnterable {

    @Child private CondNode condition;
    @Child private PhpNode thenBranch;
    @Child private PhpNode elseBranch;
    private final CountingConditionProfile profile = CountingConditionProfile.create();
    @CompilationFinal(dimensions = 1) private final int[] thenLabelIds;
    @CompilationFinal(dimensions = 1) private final int[] elseLabelIds;
    @CompilationFinal(dimensions = 1) private final int[] allLabelIds;

    public GotoIfNode(PhpNode condition, PhpNode thenBranch, PhpNode elseBranch) {
        this.condition = new CondNode(condition);
        this.thenBranch = thenBranch;
        this.elseBranch = elseBranch;
        this.thenLabelIds = thenBranch instanceof GotoEnterable ge ? ge.exportedLabelIds() : new int[0];
        this.elseLabelIds = elseBranch instanceof GotoEnterable ge ? ge.exportedLabelIds() : new int[0];
        this.allLabelIds = new int[thenLabelIds.length + elseLabelIds.length];
        System.arraycopy(thenLabelIds, 0, allLabelIds, 0, thenLabelIds.length);
        System.arraycopy(elseLabelIds, 0, allLabelIds, thenLabelIds.length, elseLabelIds.length);
    }

    @Override
    public int[] exportedLabelIds() {
        return allLabelIds;
    }

    public PhpNode thenNode() {
        return thenBranch;
    }

    public PhpNode elseNode() {
        return elseBranch;
    }

    public PhpNode conditionNode() {
        return condition;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (profile.profile(condition.executeCondition(frame))) {
            thenBranch.executeVoid(frame);
        } else if (elseBranch != null) {
            elseBranch.executeVoid(frame);
        }
        return null;
    }

    @Override
    public void executeEnter(VirtualFrame frame, int labelId) {
        if (contains(thenLabelIds, labelId)) {
            if (thenBranch instanceof GotoEnterable ge) {
                ge.executeEnter(frame, labelId);
            } else {
                thenBranch.executeVoid(frame);
            }
        } else if (elseBranch != null) {
            if (elseBranch instanceof GotoEnterable ge) {
                ge.executeEnter(frame, labelId);
            } else {
                elseBranch.executeVoid(frame);
            }
        }
    }

    @ExplodeLoop
    private static boolean contains(int[] ids, int target) {
        for (int id : ids) {
            if (id == target) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.emitGotoMachine(this);
    }
}
