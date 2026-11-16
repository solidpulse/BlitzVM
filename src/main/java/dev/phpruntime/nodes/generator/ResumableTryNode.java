package dev.phpruntime.nodes.generator;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.stmt.TryCatchNode;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpException;
import dev.phpruntime.runtime.PhpExceptions;
import dev.phpruntime.runtime.PhpObject;

/** try, selected catch, finally, and pending exit/exception survive suspension independently. */
public final class ResumableTryNode extends PhpNode implements dev.phpruntime.nodes.stmt.GotoEnterable {
    @Child private PhpNode body;
    @Children private final TryCatchNode.CatchClause[] catches;
    @Child private PhpNode finallyBody;
    private final int phaseSlot;
    private final int pendingSlot;
    private final int enterSlot;

    public ResumableTryNode(PhpNode body, TryCatchNode.CatchClause[] catches, PhpNode finallyBody, int phase, int pending, int enter) {
        this.body = body;
        this.catches = catches;
        this.finallyBody = finallyBody;
        phaseSlot = phase;
        pendingSlot = pending;
        enterSlot = enter;
    }

    @Override
    public int[] exportedLabelIds() {
        return body instanceof dev.phpruntime.nodes.stmt.GotoEnterable enterable ? enterable.exportedLabelIds() : new int[0];
    }

    @Override
    public void executeEnter(VirtualFrame frame, int labelId) {
        frame.setObject(enterSlot, labelId);
        executeVoid(frame);
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        int phase = frame.getInt(phaseSlot);
        if (phase != -1) {
            try {
                if (phase == 0) {
                    try {
                        Object enter = frame.getObject(enterSlot);
                        frame.setObject(enterSlot, null);
                        if (enter instanceof Integer label && body instanceof dev.phpruntime.nodes.stmt.GotoEnterable enterable) {
                            enterable.executeEnter(frame, label);
                        } else {
                            body.executeVoid(frame);
                        }
                    } catch (PhpException exception) {
                        dispatch(frame, exception.exceptionObject(), exception);
                    } catch (PhpError error) {
                        if (!error.isCatchable()) { throw error; }
                        dispatch(frame, error.toPhpObject(PhpContext.get(this), this), error);
                    }
                } else {
                    for (int i = 0; i < catches.length; i++) {
                        if (phase == i + 1) {
                            catches[i].resumeCatch(frame);
                        }
                    }
                }
            } catch (ControlFlow.Yield suspend) {
                throw suspend;
            } catch (ControlFlow.Exit exit) {
                throw exit;
            } catch (RuntimeException exception) {
                if (!(exception instanceof ControlFlow.Return || exception instanceof ControlFlow.Break
                        || exception instanceof ControlFlow.Continue || exception instanceof ControlFlow.Goto
                        || exception instanceof ControlFlow.GeneratorClose || exception instanceof PhpException
                        || exception instanceof PhpError error && error.isCatchable())) {
                    throw exception;
                }
                frame.setObject(pendingSlot, exception);
            }
            frame.setInt(phaseSlot, -1);
        }
        RuntimeException pending = (RuntimeException) frame.getObject(pendingSlot);
        try {
            if (finallyBody != null) {
                finallyBody.executeVoid(frame);
            }
        } catch (ControlFlow.Yield suspend) {
            throw suspend;
        } catch (PhpException exception) {
            PhpObject previous = pending instanceof PhpException old ? old.exceptionObject()
                    : pending instanceof PhpError old ? old.toPhpObject(PhpContext.get(this), this) : null;
            if (previous != null) { PhpExceptions.linkPrevious(exception.exceptionObject(), previous); }
            frame.setInt(phaseSlot, 0);
            frame.setObject(pendingSlot, null);
            throw exception;
        } catch (PhpError error) {
            PhpObject previous = pending instanceof PhpException old ? old.exceptionObject()
                    : pending instanceof PhpError old && old.isCatchable() ? old.toPhpObject(PhpContext.get(this), this) : null;
            frame.setInt(phaseSlot, 0);
            frame.setObject(pendingSlot, null);
            if (previous != null && error.isCatchable()) {
                PhpObject replacement = error.toPhpObject(PhpContext.get(this), this);
                PhpExceptions.linkPrevious(replacement, previous);
                throw new PhpException(replacement, this);
            }
            throw error;
        } catch (RuntimeException exception) {
            frame.setInt(phaseSlot, 0);
            frame.setObject(pendingSlot, null);
            throw exception;
        }
        frame.setInt(phaseSlot, 0);
        frame.setObject(pendingSlot, null);
        if (pending != null) { throw pending; }
        return null;
    }

    @ExplodeLoop
    private void dispatch(VirtualFrame frame, PhpObject object, RuntimeException pending) {
        for (int i = 0; i < catches.length; i++) {
            if (catches[i].matches(PhpContext.get(this), object.phpClass())) {
                frame.setInt(phaseSlot, i + 1);
                catches[i].executeCatch(frame, object);
                return;
            }
        }
        throw pending;
    }
}
