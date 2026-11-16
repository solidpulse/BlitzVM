package dev.phpruntime.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.call.DispatchCallNode;

/** A request-thread continuation. No threads, context switching, or AST-local mutable state. */
public final class PhpGenerator implements PhpNativeState {
    public final MaterializedFrame frame;
    public final RootCallTarget resumeTarget;
    public final PhpFunctionRootNode signature;
    private boolean started;
    private boolean closed;
    private boolean running;
    private boolean returned;
    private int yields;
    private boolean advanced;
    private long nextKey;
    private Object key = PhpNull.INSTANCE;
    private Object value = PhpNull.INSTANCE;
    private Object returnValue = PhpNull.INSTANCE;
    private Object input = PhpNull.INSTANCE;
    private RuntimeException injected;
    private boolean closing;

    public PhpGenerator(MaterializedFrame frame, RootCallTarget resumeTarget, PhpFunctionRootNode signature) {
        this.frame = frame;
        this.resumeTarget = resumeTarget;
        this.signature = signature;
    }

    public long autoKey() {
        return nextKey++;
    }

    public Object explicitKey(Object key) {
        if (key instanceof Long n && n >= nextKey) {
            nextKey = n == Long.MAX_VALUE ? Long.MIN_VALUE : n + 1;
        }
        return key;
    }

    public Object takeInput() {
        RuntimeException exception = injected;
        injected = null;
        Object result = input;
        input = PhpNull.INSTANCE;
        if (exception != null) {
            throw exception;
        }
        return result;
    }

    public void ensureStarted(DispatchCallNode dispatch, Node site) {
        if (running) {
            throw new PhpError("Cannot resume an already running generator", site);
        }
        if (!started && !closed) {
            resume(dispatch, site);
        }
    }

    public void resume(DispatchCallNode dispatch, Node site) {
        if (running) {
            throw new PhpError("Cannot resume an already running generator", site);
        }
        if (closed) {
            return;
        }
        started = true;
        if (yields > 0) { advanced = true; }
        running = true;
        try {
            returnValue = dispatch.call(resumeTarget, new Object[]{this});
            returned = true;
            closed = true;
            value = key = PhpNull.INSTANCE;
        } catch (ControlFlow.Yield yield) {
            key = yield.key;
            value = yield.value;
            yields++;
        } catch (ControlFlow.GeneratorClose close) {
            closed = true;
            value = key = PhpNull.INSTANCE;
        } catch (RuntimeException | Error error) {
            closed = true;
            value = key = PhpNull.INSTANCE;
            throw error;
        } finally {
            running = false;
        }
    }

    public Object current(DispatchCallNode dispatch, Node site) {
        ensureStarted(dispatch, site);
        return value;
    }

    public Object key(DispatchCallNode dispatch, Node site) {
        ensureStarted(dispatch, site);
        return key;
    }

    public boolean valid(DispatchCallNode dispatch, Node site) {
        ensureStarted(dispatch, site);
        return !closed;
    }

    public void next(DispatchCallNode dispatch, Node site) {
        ensureStarted(dispatch, site);
        resume(dispatch, site);
    }

    public void rewind(DispatchCallNode dispatch, Node site) {
        ensureStarted(dispatch, site);
        if (advanced) {
            throw exception("Cannot rewind a generator that was already run", site);
        }
    }

    public Object send(Object input, DispatchCallNode dispatch, Node site) {
        ensureStarted(dispatch, site);
        this.input = PhpOps.copyOnAssign(input);
        resume(dispatch, site);
        return value;
    }

    public Object throwInto(PhpObject exception, DispatchCallNode dispatch, Node site) {
        ensureStarted(dispatch, site);
        PhpException error = new PhpException(exception, site);
        if (closed) {
            throw error;
        }
        injected = error;
        resume(dispatch, site);
        return value;
    }

    public void close(DispatchCallNode dispatch, Node site) {
        if (!closed && started) {
            closing = true;
            try {
                injected = new ControlFlow.GeneratorClose();
                resume(dispatch, site);
            } finally {
                closed = true;
                closing = false;
            }
        }
        closed = true;
    }

    public void checkYield(Node site) {
        if (closing) { throw new PhpError("Cannot yield from finally in a force-closed generator", site); }
    }

    public Object getReturn(Node site) {
        if (!returned) {
            throw exception("Cannot get return value of a generator that hasn't returned", site);
        }
        return returnValue;
    }

    public boolean isClosed() {
        return closed;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static PhpException exception(String message, Node site) {
        dev.phpruntime.PhpContext ctx = dev.phpruntime.PhpContext.get(site);
        return new PhpException(PhpExceptions.createBuiltinThrowable(ctx, "Exception", message, 0L,
                PhpBacktrace.sourcePath(site), PhpBacktrace.sourceLine(site), null), site);
    }

    @Override
    public PhpNativeState copy() {
        throw new PhpError("Trying to clone an uncloneable object of class Generator");
    }
}
