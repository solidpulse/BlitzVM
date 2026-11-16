package dev.phpruntime.nodes.generator;

import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.LoopNode;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.stmt.IteratorNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.*;

import java.util.List;
import java.util.Map;

/** Stores the snapshot/iterator, current position, and body phase in a frame-owned cursor. */
public final class ResumableForeachNode extends PhpNode {
    private static final class Cursor {
        List<? extends Map.Entry<?, ?>> entries;
        PhpObject iterator;
        int index;
        boolean inBody;
    }

    @Child private PhpNode subject;
    @Child private LValueNode subjectLValue;
    @Child private LValueNode keyTarget;
    @Child private LValueNode valueTarget;
    @Child private PhpNode body;
    @Child private IteratorNode protocol;
    private final boolean byRef;
    private final int slot;

    public ResumableForeachNode(PhpLanguage language, PhpNode subject, LValueNode subjectLValue, LValueNode key,
                                LValueNode value, boolean byRef, PhpNode body, int slot) {
        this.subject = subject;
        this.subjectLValue = subjectLValue;
        keyTarget = key;
        valueTarget = value;
        this.byRef = byRef;
        this.body = body;
        this.slot = slot;
        protocol = new IteratorNode(language);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Cursor cursor = (Cursor) frame.getObject(slot);
        if (cursor == null) {
            cursor = new Cursor();
            Object value = subject.execute(frame);
            if (value instanceof PhpObject obj && SplInterfaces.has(obj, SplInterfaces.TRAVERSABLE)) {
                if (byRef) {
                    throw new PhpError("An iterator cannot be used with foreach by reference", this);
                }
                cursor.iterator = protocol.resolve(obj);
                protocol.rewind(cursor.iterator);
            } else if (value instanceof PhpArray arr) {
                if (byRef) {
                    arr = arr.forWrite();
                    if (subjectLValue != null) {
                        subjectLValue.write(frame, subjectLValue.evalKeys(frame), arr);
                    }
                    for (Object key : arr.keys()) {
                        arr.obtainElementRef(key);
                    }
                }
                cursor.entries = arr.rawEntries();
            } else if (value instanceof PhpObject obj) {
                PhpFunctionRootNode fn = getRootNode() instanceof GeneratorResumeRootNode root ? root.signature : null;
                String caller = fn != null ? fn.traitSelfSlot() >= 0 && frame.getObject(fn.traitSelfSlot()) instanceof PhpClass ts
                        ? ts.name() : fn.declaringClass() : null;
                cursor.entries = byRef ? PhpClass.visibleRefEntries(PhpContext.get(this), obj, caller)
                        : PhpClass.visibleEntries(PhpContext.get(this), obj, caller);
            } else {
                PhpContext.get(this).warning("foreach() argument must be of type array|object, " + PhpOps.typeName(value) + " given", line());
                return null;
            }
            frame.setObject(slot, cursor);
        }
        int count = 0;
        try {
            while (cursor.inBody || (cursor.iterator != null ? protocol.valid(cursor.iterator) : cursor.index < cursor.entries.size())) {
                TruffleSafepoint.poll(this);
                count++;
                if (!cursor.inBody) {
                    Object value = cursor.iterator != null ? protocol.current(cursor.iterator) : cursor.entries.get(cursor.index).getValue();
                    if (keyTarget != null) {
                        Object key = cursor.iterator != null ? protocol.key(cursor.iterator) : cursor.entries.get(cursor.index).getKey();
                        keyTarget.write(frame, keyTarget.evalKeys(frame), key);
                    }
                    if (byRef && value instanceof PhpReference ref) {
                        valueTarget.bindCell(frame, valueTarget.evalKeys(frame), ref);
                    } else {
                        valueTarget.write(frame, valueTarget.evalKeys(frame), PhpOps.copyOnAssign(value instanceof PhpReference ref ? ref.value : value));
                    }
                    cursor.inBody = true;
                }
                try {
                    body.executeVoid(frame);
                } catch (ControlFlow.Break stop) {
                    if (stop.levels > 1) { throw new ControlFlow.Break(stop.levels - 1); }
                    break;
                } catch (ControlFlow.Continue next) {
                    if (next.levels > 1) { throw new ControlFlow.Continue(next.levels - 1); }
                }
                cursor.inBody = false;
                cursor.index++;
                if (cursor.iterator != null) {
                    protocol.next(cursor.iterator);
                }
            }
        } catch (ControlFlow.Yield suspend) {
            throw suspend;
        } catch (RuntimeException | Error error) {
            frame.setObject(slot, null);
            throw error;
        } finally {
            LoopNode.reportLoopCount(this, count);
        }
        frame.setObject(slot, null);
        return null;
    }
}
