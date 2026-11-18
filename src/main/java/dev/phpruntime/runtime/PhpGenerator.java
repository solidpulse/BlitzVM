package dev.phpruntime.runtime;

import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.call.DispatchCallNode;

/** A request-thread continuation. No threads, context switching, or AST-local mutable state. */
public final class PhpGenerator implements PhpNativeState {
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

    /**
     * A bytecode generator: the continuation of its latest yield (the first one is the start
     * yield after the arguments are bound), or null once the body has finished.
     */
    private com.oracle.truffle.api.bytecode.ContinuationResult continuation;

    /** A bytecode generator, suspended at its start yield. */
    public PhpGenerator(com.oracle.truffle.api.bytecode.ContinuationResult start, PhpFunctionRootNode signature) {
        this.signature = signature;
        this.continuation = start;
    }

    /** The key and value a bytecode yield suspends with. */
    public void suspendWith(Object key, Object value) {
        this.key = key;
        this.value = value;
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
            // Resume the continuation; the yield (or the start yield) receives this
            // generator, and a yield sets the key and value before suspending.
            com.oracle.truffle.api.bytecode.ContinuationResult c = continuation;
            Object result = dispatch.call(c.getContinuationCallTarget(), new Object[]{c.getFrame(), this});
            if (result instanceof com.oracle.truffle.api.bytecode.ContinuationResult next) {
                continuation = next;
                yields++;
                return;
            }
            continuation = null;
            returnValue = result;
            returned = true;
            closed = true;
            value = key = PhpNull.INSTANCE;
        } catch (GeneratorCloseSignal close) {
            continuation = null;
            closed = true;
            value = key = PhpNull.INSTANCE;
        } catch (RuntimeException | Error error) {
            continuation = null;
            closed = true;
            value = key = PhpNull.INSTANCE;
            throw error;
        } finally {
            running = false;
        }
    }

    public Object current(DispatchCallNode dispatch, Node site) {
        ensureStarted(dispatch, site);
        return currentValue();
    }

    /** The yielded value: a by-reference generator stores the cell it suspends with. */
    private Object currentValue() {
        return value instanceof PhpReference ref ? ref.getValue() : value;
    }

    /** The cell a by-reference generator suspends with, for {@code foreach (... as &$x)}. */
    public PhpReference currentCell() {
        return value instanceof PhpReference ref ? ref : new PhpReference(value);
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
        return currentValue();
    }

    public Object throwInto(PhpObject exception, DispatchCallNode dispatch, Node site) {
        ensureStarted(dispatch, site);
        PhpException error = new PhpException(exception, site);
        if (closed) {
            throw error;
        }
        injected = error;
        resume(dispatch, site);
        return currentValue();
    }

    public void close(DispatchCallNode dispatch, Node site) {
        if (!closed && started) {
            closing = true;
            try {
                injected = new GeneratorCloseSignal();
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

    /** A by-value generator cannot be iterated with {@code foreach (... as &$x)}. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static PhpException referenceIterationError(Node site) {
        return referenceIterationError(site, PhpBacktrace.sourceLine(site));
    }

    /** The same, with the line of a bytecode instruction instead of the root's. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static PhpException referenceIterationError(Node site, int line) {
        dev.phpruntime.PhpContext ctx = dev.phpruntime.PhpContext.get(site);
        return new PhpException(PhpExceptions.createBuiltinThrowable(ctx, "Exception",
                "You can only iterate a generator by-reference if it declared that it yields by-reference",
                0L, PhpBacktrace.sourcePath(site), line, null), site);
    }

    @Override
    public PhpNativeState copy() {
        throw new PhpError("Trying to clone an uncloneable object of class Generator");
    }
}
