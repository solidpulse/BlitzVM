package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.LoopNode;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;

import java.util.List;
import java.util.Map;

/** {@code foreach ($arr as $k => $v)}. Iterates a snapshot, so the body may modify $arr. */
public final class ForeachNode extends PhpNode {

    @Child private PhpNode subject;
    @Child private LValueNode keyTarget;
    @Child private LValueNode valueTarget;
    @Child private PhpNode body;

    public ForeachNode(PhpNode subject, LValueNode keyTarget, LValueNode valueTarget, PhpNode body) {
        this.subject = subject;
        this.keyTarget = keyTarget;
        this.valueTarget = valueTarget;
        this.body = body;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object s = subject.execute(frame);
        List<Map.Entry<Object, Object>> entries;
        if (s instanceof PhpArray arr) {
            entries = arr.entries();
        } else if (s instanceof PhpObject obj) {
            String caller = getRootNode() instanceof PhpFunctionRootNode fn ? fn.declaringClass() : null;
            entries = PhpClass.visibleEntries(PhpContext.get(this), obj, caller);
        } else {
            PhpContext.get(this).warning("foreach() argument must be of type array|object, " + PhpOps.typeName(s) + " given", line());
            return null;
        }
        int count = 0;
        try {
            for (Map.Entry<Object, Object> e : entries) {
                TruffleSafepoint.poll(this);
                count++;
                if (keyTarget != null) {
                    keyTarget.write(frame, keyTarget.evalKeys(frame), e.getKey());
                }
                valueTarget.write(frame, valueTarget.evalKeys(frame), PhpOps.copyOnAssign(e.getValue()));
                try {
                    body.executeVoid(frame);
                } catch (ControlFlow.Break b) {
                    if (b.levels > 1) {
                        throw new ControlFlow.Break(b.levels - 1);
                    }
                    break;
                } catch (ControlFlow.Continue c) {
                    if (c.levels > 1) {
                        throw new ControlFlow.Continue(c.levels - 1);
                    }
                }
            }
        } finally {
            LoopNode.reportLoopCount(this, count);
        }
        return null;
    }
}
