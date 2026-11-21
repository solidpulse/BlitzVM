package dev.phpruntime.host;

import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpContextHolder;
import dev.phpruntime.js.JsLanguage;
import dev.phpruntime.runtime.GuestScheduler;
import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.sched.Scheduler;
import dev.phpruntime.snapshot.SnapshotRun;
import dev.phpruntime.snapshot.SnapshotSupport;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.io.IOAccess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Runs PHP scripts. One polyglot {@link Engine} is shared by all requests so parsed
 * ASTs, their profiling state and any JIT-compiled code are reused. Server requests also
 * reuse a bounded pool of polyglot {@link Context}s, each of which installs a brand new
 * {@link PhpContext} per request so requests share nothing at the PHP level. CLI runs and
 * JavaScript keep one fresh context per execution.
 */
public final class PhpEngine implements AutoCloseable {

    /** A pooled context is replaced after this many requests, even when every one was clean. */
    private static final int DEFAULT_MAX_REQUESTS_PER_CONTEXT = 10_000;

    /** Result of one execution: the response body plus whatever the script set via header(). */
    public record Result(int status, byte[] body, PhpRequest request, String errors) {
        public String bodyText() {
            return PhpStrings.toJava(PhpStrings.of(body));
        }
    }

    private final Engine engine;
    private final Scheduler scheduler;
    private final ContextPool pool;
    private final int maxRequestsPerContext;
    private final ConcurrentHashMap<Path, CachedSource> sources = new ConcurrentHashMap<>();

    private record CachedSource(long modified, Source source) {
    }

    /**
     * Compiler threads for a server: the cores the scheduler leaves free (it runs PHP on
     * {@code cpus} slots), at least 2. WordPress reaches peak speed when its Tier 2 compile
     * backlog drains, which took about 4 minutes with Truffle's default on 12 cores with 4 slots
     * and about 2 with 8 threads. An explicit {@code -Dpolyglot.engine.CompilerThreads} wins;
     * CLI runs (no scheduler) keep Truffle's default.
     */
    private static int defaultCompilerThreads(Scheduler scheduler) {
        if (scheduler == null || System.getProperty("polyglot.engine.CompilerThreads") != null) {
            return 0;
        }
        return Math.max(2, Runtime.getRuntime().availableProcessors() - scheduler.cpus());
    }

    public PhpEngine(Scheduler scheduler) {
        this(scheduler, scheduler == null ? 0 : Math.max(16, scheduler.cpus() * 8));
    }

    /**
     * @param poolSize maximum number of polyglot contexts to keep, or 0 for one context per
     *                 execution as CLI runs need
     */
    public PhpEngine(Scheduler scheduler, int poolSize) {
        this.scheduler = scheduler;
        // Experimental engine options (compilation thresholds and the like) can then be set
        // with -Dpolyglot.engine.<Option>=... for tuning runs.
        Engine.Builder builder = Engine.newBuilder("php", JsLanguage.ID)
                .allowExperimentalOptions(true)
                .option("engine.WarnInterpreterOnly", "false");
        int compilerThreads = defaultCompilerThreads(scheduler);
        if (compilerThreads > 0) {
            builder.option("engine.CompilerThreads", Integer.toString(compilerThreads));
        }
        this.engine = builder.build();
        this.pool = poolSize > 0 && scheduler != null ? new ContextPool(poolSize) : null;
        this.maxRequestsPerContext = Math.max(1,
                Integer.getInteger("blitz.pool.maxRequests", DEFAULT_MAX_REQUESTS_PER_CONTEXT));
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    /** Test hook: polyglot contexts built by the pool so far. */
    int contextsCreated() {
        return pool == null ? 0 : pool.created();
    }

    /** Executes a script and buffers its output, as the HTTP server needs. */
    public Result execute(Path script, PhpRequest request) throws IOException {
        return execute(script, request, null);
    }

    /**
     * Executes a script with a snapshot run attached to the request's context: a template run
     * that captures and aborts, or a restored run that fills the context from a snapshot.
     */
    public Result execute(Path script, PhpRequest request, SnapshotRun snapshotRun) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = run(source(script), request, out, err, snapshotRun);
        return new Result(status, out.toByteArray(), request, PhpStrings.toJava(PhpStrings.of(err.toByteArray())));
    }

    /** Executes a script streaming output, as the CLI needs. Returns a process exit code. */
    public int executeStreaming(Path script, PhpRequest request, OutputStream out, OutputStream err) throws IOException {
        return run(source(script), request, out, err, null) >= 500 ? 255 : 0;
    }

    private int run(Source source, PhpRequest request, OutputStream out, OutputStream err,
                    SnapshotRun snapshotRun) {
        Scheduler.Task task = scheduler == null ? null : scheduler.newTask(request.uri);
        request.scheduler = task == null ? GuestScheduler.NONE : task;
        request.install();
        if (snapshotRun != null) {
            SnapshotSupport.setPending(snapshotRun);
        }
        boolean js = JsLanguage.ID.equals(source.getLanguage());
        if (!js && pool != null) {
            return runPooled(source, request, out, err, snapshotRun, task);
        }
        return runSingleUse(source, request, out, err, snapshotRun, task, js);
    }

    /** Today's path: one polyglot context per execution, for CLI runs and JavaScript. */
    private int runSingleUse(Source source, PhpRequest request, OutputStream out, OutputStream err,
                             SnapshotRun snapshotRun, Scheduler.Task task, boolean js) {
        try (Context ctx = Context.newBuilder(source.getLanguage()).engine(engine).allowIO(IOAccess.ALL).out(out).err(err).build()) {
            if (task != null) {
                task.bindContext(ctx);
                task.acquire();
            }
            try {
                ctx.eval(source);
                return request.status;
            } catch (PolyglotException e) {
                return fail(request, out, task, e, js);
            } finally {
                if (task != null) {
                    task.release();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            request.status = 503;
            return request.status;
        } catch (PolyglotException e) {
            // close() of a cancelled context rethrows the cancellation; report it only once
            return request.status >= 500 ? request.status : fail(request, out, task, e, js);
        } finally {
            cleanup(request, snapshotRun);
        }
    }

    /**
     * A server request on a borrowed context. The context was built with forwarding streams,
     * so {@code env.out()} and {@code env.err()} reach this request's buffers; a fresh
     * {@link PhpContext} is installed before the first guest instruction runs.
     */
    private int runPooled(Source source, PhpRequest request, OutputStream out, OutputStream err,
                          SnapshotRun snapshotRun, Scheduler.Task task) {
        PooledContext borrowed;
        try {
            borrowed = pool.borrow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            request.status = 503;
            cleanup(request, snapshotRun);
            return request.status;
        } catch (RuntimeException | Error e) {
            cleanup(request, snapshotRun);
            throw e;
        }
        boolean reusable = false;
        try {
            borrowed.streams.install(out, err);
            PhpContext php = borrowed.holder.install();
            borrowed.uses++;
            php.bindRequest(request);
            Context ctx = borrowed.context;
            if (task != null) {
                task.bindContext(ctx);
                task.acquire();
            }
            try {
                ctx.eval(source);
                // A clean run may be pooled again; everything else closes the context below.
                reusable = (task == null || !task.timedOut()) && !php.endedAbnormally()
                        && borrowed.uses < maxRequestsPerContext;
                return request.status;
            } catch (PolyglotException e) {
                // Guest errors, internal errors and cancellation all leave the context's fate
                // unproven; keep it out of the pool.
                return fail(request, out, task, e, false);
            } finally {
                if (task != null) {
                    task.release();
                }
                // release() stops the ticker from closing this task's context, so this is the
                // final answer: a timeout that won the race still discards the context.
                if (task != null && task.timedOut()) {
                    reusable = false;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            request.status = 503;
            return request.status;
        } catch (PolyglotException e) {
            // close() of a cancelled context rethrows the cancellation; report it only once
            return request.status >= 500 ? request.status : fail(request, out, task, e, false);
        } catch (RuntimeException | Error e) {
            reusable = false;
            throw e;
        } finally {
            cleanup(request, snapshotRun);
            borrowed.streams.clear();
            borrowed.holder.clear();
            pool.release(borrowed, reusable);
        }
    }

    private static void cleanup(PhpRequest request, SnapshotRun snapshotRun) {
        if (snapshotRun != null) {
            SnapshotSupport.clearPending();
        }
        // Close host resources (database links, PDO connections) before the context is reused.
        request.closeResources();
        PhpRequest.uninstall();
    }

    /** Prints the failure the way php-cli does and turns it into an HTTP 500. */
    private int fail(PhpRequest request, OutputStream out, Scheduler.Task task, PolyglotException e, boolean js) {
        if (e.isInternalError() && Boolean.getBoolean("blitz.internal.trace")) {
            // -Dblitz.internal.trace=true: the Java stack of a runtime bug.
            e.printStackTrace();
        }
        SourceSection where = e.getSourceLocation();
        String file = where != null && where.getSource().getPath() != null ? where.getSource().getPath() : request.scriptFilename;
        if (!js) {
            file = PhpStrings.fromJava(file);
        }
        int line = where != null ? where.getStartLine() : 0;
        String text;
        if (js) {
            text = task != null && task.timedOut()
                    ? "Error: Maximum execution time of " + scheduler.maxExecutionMillis() + " ms exceeded"
                    : "Uncaught " + e.getMessage() + (line > 0 ? "\n    at " + file + ":" + line : "");
        } else if (task != null && task.timedOut()) {
            double secs = scheduler.maxExecutionMillis() / 1000.0;
            String shown = secs == Math.rint(secs) ? Long.toString((long) secs) : Double.toString(secs);
            text = "PHP Fatal error:  Maximum execution time of " + shown + " second" + (secs == 1 ? "" : "s") + " exceeded"
                    + (file.isEmpty() ? "" : " in " + file);
        } else if (e.isSyntaxError()) {
            text = "PHP Parse error:  " + e.getMessage() + " in " + file + " on line " + line;
        } else if (e.isCancelled()) {
            text = "PHP Fatal error:  Execution cancelled";
        } else if (e.isGuestException()) {
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("PHP Fatal error:  Uncaught ")) {
                text = msg;
            } else {
                char lf = 10;
                text = "PHP Fatal error:  Uncaught " + msg + " in " + file + ":" + line + lf + phpStackTrace(e)
                        + "  thrown in " + file + " on line " + line;
            }
        } else {
            text = "PHP Fatal error:  Internal error: " + PhpStrings.fromJava(e.getMessage());
        }
        try {
            // Guest messages are PHP bytes; locations and host exceptions are Java text.
            out.write(js ? (text + "\n").getBytes(StandardCharsets.UTF_8) : PhpStrings.bytes(text + "\n"));
        } catch (IOException ignored) {
            // client went away
        }
        request.status = 500;
        return 500;
    }

    /** "#0 file(line): fn()" frames from the guest part of the polyglot stack trace. */
    private static String phpStackTrace(PolyglotException e) {
        StringBuilder sb = new StringBuilder("Stack trace:\n");
        int i = 0;
        String calledFn = null;
        for (PolyglotException.StackFrame f : e.getPolyglotStackTrace()) {
            if (!f.isGuestFrame()) {
                continue;
            }
            String name = f.getRootName();
            if (calledFn != null && f.getSourceLocation() != null) {
                sb.append('#').append(i++).append(' ').append(PhpStrings.fromJava(f.getSourceLocation().getSource().getPath()))
                        .append('(').append(f.getSourceLocation().getStartLine()).append("): ").append(calledFn).append("()\n");
            }
            calledFn = name;
        }
        sb.append('#').append(i).append(" {main}\n");
        return sb.toString();
    }

    private Source source(Path script) throws IOException {
        Path abs = script.toAbsolutePath().normalize();
        long modified = Files.getLastModifiedTime(abs).toMillis();
        CachedSource cached = sources.get(abs);
        if (cached != null && cached.modified() == modified) {
            return cached.source();
        }
        // A cached Source lets the shared engine reuse the parsed call target across contexts.
        String language = abs.toString().endsWith(".js") ? JsLanguage.ID : "php";
        Source src = Source.newBuilder(language, abs.toFile())
                .encoding(language.equals("php") ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8)
                .cached(true).build();
        sources.put(abs, new CachedSource(modified, src));
        return src;
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.close();
        }
        engine.close();
    }

    /** One borrowed polyglot context and the objects that belong to it, not to a request. */
    private static final class PooledContext {
        final Context context;
        final PhpContextHolder holder;
        final RequestStreams streams;
        /** Requests served since this context was built; the safety valve counts them. */
        int uses;

        PooledContext(Context context, PhpContextHolder holder, RequestStreams streams) {
            this.context = context;
            this.holder = holder;
            this.streams = streams;
        }

        void close() {
            try {
                context.close();
            } catch (RuntimeException ignored) {
                // already closed by a timeout, or the engine is going away
            }
        }
    }

    /**
     * Streams fixed in a pooled context at build time. Guest writes go to whichever request
     * currently owns the context; a context is only lent to one request at a time.
     */
    private static final class RequestStreams {
        private volatile OutputStream out = OutputStream.nullOutputStream();
        private volatile OutputStream err = OutputStream.nullOutputStream();
        private final OutputStream outView = new Forwarder(true);
        private final OutputStream errView = new Forwarder(false);

        OutputStream out() {
            return outView;
        }

        OutputStream err() {
            return errView;
        }

        void install(OutputStream requestOut, OutputStream requestErr) {
            out = requestOut;
            err = requestErr;
        }

        void clear() {
            out = OutputStream.nullOutputStream();
            err = OutputStream.nullOutputStream();
        }

        private final class Forwarder extends OutputStream {
            private final boolean stdout;

            Forwarder(boolean stdout) {
                this.stdout = stdout;
            }

            private OutputStream target() {
                return stdout ? out : err;
            }

            @Override
            public void write(int b) throws IOException {
                target().write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                target().write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                target().flush();
            }
        }
    }

    /**
     * Bounded, grow-on-demand pool. Each permit is one context that may exist at the same
     * time; idle contexts stay warm. Requests blocked in I/O keep their context (a database
     * call releases only the scheduler CPU slot), so the limit is above the CPU slots: the
     * default allows cpus * 8 with a floor of 16.
     */
    private final class ContextPool {
        private final Semaphore permits;
        private final Object lock = new Object();
        private final ArrayDeque<PooledContext> idle = new ArrayDeque<>();
        private boolean closed;
        private int created;
        private final ThreadLocal<PooledContext> lastUsed = new ThreadLocal<>();

        ContextPool(int max) {
            this.permits = new Semaphore(max, true);
        }

        PooledContext borrow() throws InterruptedException {
            permits.acquire();
            PooledContext idleContext;
            boolean wasClosed;
            synchronized (lock) {
                // Prefer the context this thread used last: Truffle tracks every thread that has
                // entered a context, and entering one from a new thread walks that list.
                PooledContext mine = lastUsed.get();
                idleContext = mine != null && idle.remove(mine) ? mine : idle.pollFirst();
                wasClosed = closed;
            }
            if (wasClosed) {
                if (idleContext != null) {
                    idleContext.close();
                }
                permits.release();
                throw new IllegalStateException("engine closed");
            }
            if (idleContext != null) {
                return idleContext;
            }
            try {
                PooledContext context = create();
                synchronized (lock) {
                    if (!closed) {
                        created++;
                        return context;
                    }
                }
                context.close();
                throw new IllegalStateException("engine closed");
            } catch (RuntimeException | Error e) {
                permits.release();
                throw e;
            }
        }

        private PooledContext create() {
            RequestStreams streams = new RequestStreams();
            PhpContextHolder holder = PhpContextHolder.prepare();
            Context context = null;
            try {
                context = Context.newBuilder("php").engine(engine).allowIO(IOAccess.ALL)
                        .out(streams.out()).err(streams.err()).build();
                // Force createContext now, on this thread, so the prepared holder is bound.
                context.initialize("php");
                if (!holder.isBound()) {
                    throw new IllegalStateException("PHP language context was not created");
                }
                return new PooledContext(context, holder, streams);
            } catch (RuntimeException | Error e) {
                if (context != null) {
                    try {
                        context.close();
                    } catch (RuntimeException ignored) {
                        // the failure is already being reported
                    }
                }
                throw e;
            } finally {
                PhpContextHolder.finishPrepare();
            }
        }

        void release(PooledContext context, boolean reusable) {
            lastUsed.set(reusable ? context : null);
            boolean kept = false;
            if (reusable && !closed) {
                synchronized (lock) {
                    if (!closed) {
                        idle.addFirst(context);
                        kept = true;
                    }
                }
            }
            if (!kept) {
                context.close();
            }
            permits.release();
        }

        int created() {
            synchronized (lock) {
                return created;
            }
        }

        void close() {
            List<PooledContext> toClose;
            synchronized (lock) {
                closed = true;
                toClose = new ArrayList<>(idle);
                idle.clear();
            }
            for (PooledContext context : toClose) {
                context.close();
            }
        }
    }
}
