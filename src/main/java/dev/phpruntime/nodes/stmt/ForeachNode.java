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
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

import java.util.List;
import java.util.Map;

/** {@code foreach ($arr as $k => [$v | &$v | [...] ])}. */
public final class ForeachNode extends PhpNode {

    @Child private PhpNode subject;
    @Child private LValueNode subjectLValue;
    @Child private LValueNode keyTarget;
    @Child private LValueNode valueTarget;
    private final boolean byRef;
    @Child private PhpNode body;

    public ForeachNode(PhpNode subject, LValueNode keyTarget, LValueNode valueTarget, PhpNode body) {
        this(subject, null, keyTarget, valueTarget, false, body);
    }

    public ForeachNode(PhpNode subject, LValueNode subjectLValue, LValueNode keyTarget, LValueNode valueTarget,
                       boolean byRef, PhpNode body) {
        this.subject = subject;
        this.subjectLValue = subjectLValue;
        this.keyTarget = keyTarget;
        this.valueTarget = valueTarget;
        this.byRef = byRef;
        this.body = body;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (byRef) {
            return executeByRef(frame);
        }
        Object s = subject.execute(frame);
        List<Map.Entry<Object, Object>> entries;
        if (s instanceof PhpArray arr) {
            entries = arr.rawEntries();
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
                Object rawVal = e.getValue();
                Object curVal = rawVal instanceof PhpReference ref ? ref.value : rawVal;
                valueTarget.write(frame, valueTarget.evalKeys(frame), PhpOps.copyOnAssign(curVal));
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

    private Object executeByRef(VirtualFrame frame) {
        Object s;
        PhpReference subjectCell = null;
        if (subjectLValue != null) {
            Object[] sk = subjectLValue.evalKeys(frame);
            Object cur = subjectLValue.read(frame, sk);
            if (cur == null) {
                PhpContext.get(this).warning("Undefined variable " + subjectLValue.describe(), line());
                cur = PhpNull.INSTANCE;
            }
            if (cur instanceof PhpArray arr) {
                subjectCell = subjectLValue.obtainCell(frame, sk);
                PhpArray writable = arr.forWrite();
                subjectCell.value = writable;
                s = writable;
            } else {
                s = cur;
            }
        } else {
            Object raw = subject.executeForRef(frame);
            if (raw instanceof PhpReference ref) {
                subjectCell = ref;
                if (ref.value instanceof PhpArray arr) {
                    PhpArray writable = arr.forWrite();
                    ref.value = writable;
                    s = writable;
                } else {
                    s = ref.value == null ? PhpNull.INSTANCE : ref.value;
                }
            } else {
                s = raw;
            }
        }

        if (s instanceof PhpArray arr) {
            List<Object> keys = arr.keys();
            int count = 0;
            try {
                for (Object k : keys) {
                    TruffleSafepoint.poll(this);
                    count++;
                    PhpArray currentArr = subjectCell != null && subjectCell.value instanceof PhpArray ca ? ca : arr;
                    if (!currentArr.containsKey(k)) {
                        continue;
                    }
                    PhpReference elemCell = currentArr.obtainElementRef(k);
                    if (keyTarget != null) {
                        keyTarget.write(frame, keyTarget.evalKeys(frame), k);
                    }
                    valueTarget.bindCell(frame, valueTarget.evalKeys(frame), elemCell);
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
        if (s instanceof PhpObject obj) {
            String caller = getRootNode() instanceof PhpFunctionRootNode fn ? fn.declaringClass() : null;
            List<Map.Entry<Object, PhpReference>> entries = PhpClass.visibleRefEntries(PhpContext.get(this), obj, caller);
            int count = 0;
            try {
                for (Map.Entry<Object, PhpReference> e : entries) {
                    TruffleSafepoint.poll(this);
                    count++;
                    if (keyTarget != null) {
                        keyTarget.write(frame, keyTarget.evalKeys(frame), e.getKey());
                    }
                    valueTarget.bindCell(frame, valueTarget.evalKeys(frame), e.getValue());
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
        PhpContext.get(this).warning("foreach() argument must be of type array|object, " + PhpOps.typeName(s) + " given", line());
        return null;
    }
}
