package dev.phpruntime;

import com.oracle.truffle.api.TruffleLanguage;
import dev.phpruntime.snapshot.SnapshotSupport;

/**
 * The language context of one polyglot context: a holder for the {@link PhpContext} of the
 * request currently running in it.
 *
 * <p>A polyglot context is expensive to build (instrument context locals, language
 * initialization) and can serve many requests one after another. The holder keeps that
 * context alive while every request still gets a brand new {@link PhpContext}, so the PHP
 * object graph (globals, functions, classes, constants, buffers) stays shared-nothing exactly
 * as it did when every request built its own polyglot context.
 *
 * <p>The request bodies themselves only ever run on one thread at a time. The pool hands the
 * context to the next thread through a queue, which publishes the new {@link #current}.
 * {@link #current} is deliberately not {@code @CompilationFinal}: replacing it every request
 * must not invalidate compiled guest code.
 */
public final class PhpContextHolder {

    /**
     * The holder a host wants {@code createContext} to bind, so the host keeps the reference
     * it needs to start each request. Context creation is lazy and runs on the thread calling
     * {@code Context.initialize} or the first {@code eval}, hence a thread local.
     */
    private static final ThreadLocal<PhpContextHolder> PENDING = new ThreadLocal<>();

    private PhpLanguage language;
    private TruffleLanguage.Env env;
    private PhpContext current;
    /** True while {@link #create} should leave {@link #current} for the first request start. */
    private boolean hostManaged;

    /** Only used through {@link #prepare} and {@link #create}, or as a direct-eval context. */
    PhpContextHolder() {
    }

    /**
     * Host side, before building a pooled polyglot context: the {@code createContext} call made
     * while the context initializes binds this holder instead of a new one. The first request
     * then calls {@link #install}, so no throwaway {@code PhpContext} is created.
     */
    public static PhpContextHolder prepare() {
        PhpContextHolder holder = new PhpContextHolder();
        holder.hostManaged = true;
        PENDING.set(holder);
        return holder;
    }

    /** Stops the next language context creation from claiming the prepared holder. */
    public static void finishPrepare() {
        PENDING.remove();
    }

    /**
     * Called by {@link PhpLanguage#createContext}. An ordinary context (CLI, snapshot-check)
     * gets its one {@code PhpContext} immediately; a prepared pooled context leaves it null.
     */
    static PhpContextHolder create(PhpLanguage language, TruffleLanguage.Env env) {
        PhpContextHolder holder = PENDING.get();
        PENDING.remove();
        if (holder == null) {
            holder = new PhpContextHolder();
        }
        holder.language = language;
        holder.env = env;
        if (!holder.hostManaged) {
            holder.install();
        }
        return holder;
    }

    public PhpContext current() {
        return current;
    }

    /** Whether {@link PhpLanguage#createContext} has bound this holder to its language and env. */
    public boolean isBound() {
        return language != null;
    }

    /**
     * Starts a request: a fresh {@link PhpContext} with the snapshot hook attached, published
     * for {@link PhpContext#get(com.oracle.truffle.api.nodes.Node)} to find. Any state the
     * previous request left is unreachable from this point on.
     */
    public PhpContext install() {
        PhpContext ctx = new PhpContext(language, env);
        current = ctx;
        SnapshotSupport.attachNewContext(ctx);
        return ctx;
    }

    /** Drops the finished request's context so an idle pooled context retains no PHP state. */
    public void clear() {
        current = null;
    }
}
