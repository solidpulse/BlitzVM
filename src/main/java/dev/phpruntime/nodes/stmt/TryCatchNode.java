package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpException;
import dev.phpruntime.runtime.PhpExceptions;
import dev.phpruntime.runtime.PhpObject;

/**
 * {@code try { ... } catch (T1 | T2 $e) { ... } finally { ... }}.
 *
 * <p>Follows PHP 8 semantics:
 * <ul>
 *   <li>Catch clauses match user {@link PhpException}s and catchable engine {@link PhpError}s,
 *       never Truffle control-flow exceptions ({@code Return}, {@code Break}, {@code Continue},
 *       {@code Exit}), scheduler cancellation, or uncatchable compile/declaration fatals.</li>
 *   <li>Unknown class names in a {@code catch} clause never match and do not error.</li>
 *   <li>{@code finally} runs on normal completion, caught/uncaught exceptions, {@code return}
 *       (after evaluating the return value), {@code break} and {@code continue}, and does NOT
 *       run on {@code exit}/{@code die}.</li>
 *   <li>If {@code finally} returns or jumps, it overrides any pending return, jump or exception.</li>
 *   <li>If {@code finally} throws a new PHP throwable while another PHP throwable was pending,
 *       the pending throwable is linked at the tail of the new throwable's {@code previous} chain.</li>
 * </ul>
 */
public final class TryCatchNode extends PhpNode implements GotoEnterable {

    public static final class CatchClause extends Node {
        @CompilationFinal(dimensions = 1) private final int[] classIds;
        @CompilationFinal(dimensions = 1) private final boolean[] matchAllThrowables;
        @Child private LValueNode varTarget;
        @Child private PhpNode body;

        public CatchClause(int[] classIds, boolean[] matchAllThrowables, LValueNode varTarget, PhpNode body) {
            this.classIds = classIds;
            this.matchAllThrowables = matchAllThrowables;
            this.varTarget = varTarget;
            this.body = body;
        }

        @ExplodeLoop
        public boolean matches(PhpContext ctx, PhpClass thrownClass) {
            for (int i = 0; i < classIds.length; i++) {
                if (matchAllThrowables[i] && thrownClass.isThrowable()) {
                    return true;
                }
                PhpClass target = ctx.classById(classIds[i]);
                if (target != null && matchesClass(thrownClass, target)) {
                    return true;
                }
            }
            return false;
        }

        public void executeCatch(VirtualFrame frame, PhpObject exObj) {
            if (varTarget != null) {
                varTarget.write(frame, varTarget.evalKeys(frame), exObj);
            }
            body.executeVoid(frame);
        }

        public void resumeCatch(VirtualFrame frame) {
            body.executeVoid(frame);
        }

        private static boolean matchesClass(PhpClass actual, PhpClass target) {
            if (actual == target) {
                return true;
            }
            if (!target.isInterface()) {
                for (PhpClass c = actual.parent(); c != null; c = c.parent()) {
                    if (c == target) {
                        return true;
                    }
                }
                return false;
            }
            return actual.isInstanceOf(target);
        }
    }

    @Child private PhpNode tryBlock;
    @Children private final CatchClause[] catches;
    @Child private PhpNode finallyBlock;

    public TryCatchNode(PhpNode tryBlock, CatchClause[] catches, PhpNode finallyBlock) {
        this.tryBlock = tryBlock;
        this.catches = catches;
        this.finallyBlock = finallyBlock;
    }

    @Override
    public int[] exportedLabelIds() {
        return tryBlock instanceof GotoEnterable ge ? ge.exportedLabelIds() : new int[0];
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (finallyBlock == null) {
            executeTryCatchOnly(frame, -1);
            return null;
        }
        executeWithFinally(frame, -1);
        return null;
    }

    @Override
    public void executeEnter(VirtualFrame frame, int labelId) {
        if (finallyBlock == null) {
            executeTryCatchOnly(frame, labelId);
        } else {
            executeWithFinally(frame, labelId);
        }
    }

    private void runTryBlock(VirtualFrame frame, int enterLabel) {
        if (enterLabel >= 0 && tryBlock instanceof GotoEnterable ge) {
            ge.executeEnter(frame, enterLabel);
        } else {
            tryBlock.executeVoid(frame);
        }
    }

    private void executeTryCatchOnly(VirtualFrame frame, int enterLabel) {
        try {
            runTryBlock(frame, enterLabel);
        } catch (PhpException ex) {
            if (!dispatchCatch(frame, ex.exceptionObject())) {
                throw ex;
            }
        } catch (PhpError err) {
            if (!err.isCatchable()) {
                throw err;
            }
            PhpContext ctx = PhpContext.get(this);
            PhpClass errCls = err.resolveErrorClass(ctx);
            int idx = findMatchingCatch(ctx, errCls);
            if (idx < 0) {
                throw err;
            }
            catches[idx].executeCatch(frame, err.toPhpObject(ctx, this));
        }
    }

    private void executeWithFinally(VirtualFrame frame, int enterLabel) {
        Throwable pending = null;
        try {
            try {
                runTryBlock(frame, enterLabel);
            } catch (PhpException ex) {
                if (!dispatchCatch(frame, ex.exceptionObject())) {
                    pending = ex;
                }
            } catch (PhpError err) {
                if (!err.isCatchable()) {
                    throw err;
                }
                PhpContext ctx = PhpContext.get(this);
                PhpClass errCls = err.resolveErrorClass(ctx);
                int idx = findMatchingCatch(ctx, errCls);
                if (idx < 0) {
                    pending = err;
                } else {
                    catches[idx].executeCatch(frame, err.toPhpObject(ctx, this));
                }
            }
        } catch (ControlFlow.Exit exit) {
            // PHP skips finally blocks when exit/die is executed.
            throw exit;
        } catch (Throwable t) {
            pending = t;
        }

        // If pending is non-catchable host/Truffle cancellation (neither ControlFlow, PhpException nor catchable PhpError):
        if (pending != null && !shouldRunFinallyFor(pending)) {
            rethrowUnchecked(pending);
        }

        try {
            finallyBlock.executeVoid(frame);
        } catch (PhpException newEx) {
            if (pending != null) {
                PhpObject prevObj = extractPendingThrowableObject(pending);
                if (prevObj != null) {
                    PhpExceptions.linkPrevious(newEx.exceptionObject(), prevObj);
                }
            }
            throw newEx;
        } catch (PhpError newErr) {
            if (newErr.isCatchable() && pending != null) {
                PhpObject prevObj = extractPendingThrowableObject(pending);
                if (prevObj != null) {
                    PhpObject newObj = newErr.toPhpObject(PhpContext.get(this), finallyBlock);
                    PhpExceptions.linkPrevious(newObj, prevObj);
                    throw new PhpException(newObj, finallyBlock);
                }
            }
            throw newErr;
        }

        if (pending != null) {
            rethrowUnchecked(pending);
        }
    }

    private static boolean shouldRunFinallyFor(Throwable t) {
        if (t instanceof ControlFlow.Return || t instanceof ControlFlow.Break
                || t instanceof ControlFlow.Continue || t instanceof ControlFlow.Goto
                || t instanceof ControlFlow.GeneratorClose) {
            return true;
        }
        if (t instanceof PhpException) {
            return true;
        }
        if (t instanceof PhpError err) {
            return err.isCatchable();
        }
        return false;
    }

    private PhpObject extractPendingThrowableObject(Throwable pending) {
        if (pending instanceof PhpException pe) {
            return pe.exceptionObject();
        }
        if (pending instanceof PhpError err && err.isCatchable()) {
            return err.toPhpObject(PhpContext.get(this), this);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void rethrowUnchecked(Throwable t) throws E {
        throw (E) t;
    }

    @ExplodeLoop
    private int findMatchingCatch(PhpContext ctx, PhpClass thrownClass) {
        for (int i = 0; i < catches.length; i++) {
            if (catches[i].matches(ctx, thrownClass)) {
                return i;
            }
        }
        return -1;
    }

    private boolean dispatchCatch(VirtualFrame frame, PhpObject exObj) {
        PhpContext ctx = PhpContext.get(this);
        int idx = findMatchingCatch(ctx, exObj.phpClass());
        if (idx < 0) {
            return false;
        }
        catches[idx].executeCatch(frame, exObj);
        return true;
    }
}
