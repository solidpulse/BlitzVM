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
            return matches(ctx, classIds, matchAllThrowables, thrownClass);
        }

        static boolean matches(PhpContext ctx, int[] classIds, boolean[] matchAllThrowables, PhpClass thrownClass) {
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

        public int[] classIds() {
            return classIds;
        }

        public boolean[] matchAllThrowables() {
            return matchAllThrowables;
        }

        public LValueNode varTarget() {
            return varTarget;
        }

        public PhpNode body() {
            return body;
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

    public PhpNode tryBlock() {
        return tryBlock;
    }

    public CatchClause[] catches() {
        return catches;
    }

    public PhpNode finallyBlock() {
        return finallyBlock;
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

    /** Whether an active {@code finally} runs for this pending throwable (the AST's rule). */
    public static boolean shouldRunFinallyFor(Throwable t) {
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

    /** The PHP object for a pending throwable, or null when it has none (non-catchable error). */
    public static PhpObject throwableObject(PhpContext ctx, Throwable pending, Node fallback) {
        if (pending instanceof PhpException pe) {
            return pe.exceptionObject();
        }
        if (pending instanceof PhpError err && err.isCatchable()) {
            return err.toPhpObject(ctx, fallback);
        }
        return null;
    }

    private PhpObject extractPendingThrowableObject(Throwable pending) {
        return throwableObject(PhpContext.get(this), pending, this);
    }

    @SuppressWarnings("unchecked")
    public static <E extends Throwable> void rethrowUnchecked(Throwable t) throws E {
        throw (E) t;
    }

    /** Whether {@code thrown} is caught by any clause (php's matching), or -1. */
    public static int catchIndex(PhpContext ctx, CatchClause[] catches, Throwable thrown) {
        if (thrown instanceof PhpException ex) {
            return findMatchingCatch(ctx, catches, ex.exceptionObject().phpClass());
        }
        if (thrown instanceof PhpError err && err.isCatchable()) {
            return findMatchingCatch(ctx, catches, err.resolveErrorClass(ctx));
        }
        return -1;
    }

    @ExplodeLoop
    public static int findMatchingCatch(PhpContext ctx, CatchClause[] catches, PhpClass thrownClass) {
        for (int i = 0; i < catches.length; i++) {
            if (catches[i].matches(ctx, thrownClass)) {
                return i;
            }
        }
        return -1;
    }

    @ExplodeLoop
    private int findMatchingCatch(PhpContext ctx, PhpClass thrownClass) {
        return findMatchingCatch(ctx, catches, thrownClass);
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

    /**
     * Bytecode form. The catch dispatch is a DSL {@code TryCatch} whose handler asks the cached
     * clauses which one matches. {@code finally} is emitted explicitly at every exit point
     * (normal end, {@code return}, {@code break}/{@code continue}) and around uncaught exceptions,
     * because PHP skips it for {@code exit} (which is an unwind exception) and lets a
     * {@code finally} that returns override a pending return or exception. A pending error is
     * stored so a throw from the finalizer can link it as {@code previous}, as the AST does. A
     * finally run inline for a return or jump sits inside the guarded range; {@code exiting}
     * keeps the handler from running it again when it throws.
     */
    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (finallyBlock == null) {
            emitTryCatch(e);
            return;
        }
        Runnable fin = () -> {
            var fb = e.builder();
            fb.beginBlock();
            e.stmt(finallyBlock);
            fb.endBlock();
        };
        var b = e.builder();
        var pending = e.temp();
        var exiting = e.temp();
        b.beginBlock();
        b.beginStoreLocal(exiting);
        b.emitLoadConstant(Boolean.FALSE);
        b.endStoreLocal();
        e.pushFinally(fin, exiting);
        b.beginTryCatch();
        emitTryCatch(e);
        // An exception escaped the try block and the catch clauses: run finally, then rethrow.
        // The whole handler is one Block: TryCatch has exactly two children.
        b.beginBlock();
        b.beginStoreLocal(pending);
        b.emitLoadException();
        b.endStoreLocal();
        b.beginIfThen();
        b.beginShouldRunFinally();
        b.emitLoadLocal(pending);
        b.emitLoadLocal(exiting);
        b.endShouldRunFinally();
        b.beginBlock();
        e.popFinally();
        b.beginTryCatch();
        fin.run();
        b.beginBlock();
        b.beginLinkPrevious();
        b.emitLoadLocal(pending);
        b.emitLoadException();
        b.endLinkPrevious();
        b.beginRethrow();
        b.emitLoadException();
        b.endRethrow();
        b.endBlock();
        b.endTryCatch();
        e.pushFinally(fin, exiting);
        b.beginRethrow();
        b.emitLoadLocal(pending);
        b.endRethrow();
        b.endBlock();
        b.endIfThen();
        // Non-catchable errors pass through without running finally, as on the AST.
        b.beginRethrow();
        b.emitLoadLocal(pending);
        b.endRethrow();
        b.endBlock();
        b.endTryCatch();
        e.popFinally();
        fin.run();
        b.endBlock();
    }

    /** try + catch clauses, without a finally block. */
    private void emitTryCatch(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        b.beginTryCatch();
        b.beginBlock();
        e.stmt(tryBlock);
        b.endBlock();
        b.beginBlock();
        if (catches.length == 0) {
            b.beginRethrow();
            b.emitLoadException();
            b.endRethrow();
        } else {
            var index = e.temp();
            b.beginStoreLocal(index);
            b.beginCatchMatch(new dev.phpruntime.bytecode.PhpBytecodeRootNode.CatchSpec(catches));
            b.emitLoadException();
            b.endCatchMatch();
            b.endStoreLocal();
            for (int i = 0; i < catches.length; i++) {
                b.beginIfThen();
                b.beginIdentical(false);
                b.emitLoadLocal(index);
                b.emitLoadConstant((long) i);
                b.endIdentical();
                b.beginBlock();
                if (catches[i].varTarget() != null) {
                    e.assign(catches[i].varTarget(), () -> {
                        b.beginCatchValue();
                        b.emitLoadException();
                        b.endCatchValue();
                    });
                }
                e.stmt(catches[i].body());
                b.endBlock();
                b.endIfThen();
            }
        }
        b.endBlock();
        b.endTryCatch();
    }
}
