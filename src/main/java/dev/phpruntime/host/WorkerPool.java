package dev.phpruntime.host;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.GuestScheduler;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpException;
import dev.phpruntime.runtime.PhpExceptions;
import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.runtime.WorkerHandler;
import dev.phpruntime.sched.Scheduler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * FrankenPHP-style worker mode: a fixed set of long-lived guest threads, each with its own
 * polyglot context, running one worker script. The script boots the application once and then
 * calls {@code frankenphp_handle_request()} in a loop; requests are handed to whichever
 * worker is idle through a shared queue.
 *
 * <p>A worker holds a scheduler slot only while it runs guest code. While it waits inside
 * {@code frankenphp_handle_request()} it holds none, and every handled request gets a fresh
 * scheduler task, so the quantum, preemption and {@code max_execution_time} budget apply per
 * request rather than to the worker's lifetime.
 */
public final class WorkerPool implements AutoCloseable {

    /** How long the server waits for every worker to reach its first handle_request call. */
    public static final long STARTUP_TIMEOUT_MILLIS = 30_000;

    private static final long MAX_BACKOFF_MILLIS = 2_000;

    private record Job(PhpRequest request, CompletableFuture<PhpEngine.Result> response) {
    }

    /** Marks a worker whose script must be thrown away and booted again. */
    private static final class RestartWorker extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RestartWorker(String reason) {
            super(reason, null, false, false);
        }
    }

    private final PhpEngine engine;
    private final Scheduler scheduler;
    private final Path workerScript;
    private final Path docRoot;
    private final int workerCount;
    private final int port;
    private final Map<String, String> environment;
    /** The environment as PHP strings, in environment order; converted once for the pool. */
    private final List<String[]> environmentEntries;

    private final BlockingQueue<Job> queue = new LinkedBlockingQueue<>();
    private final CountDownLatch ready;
    private final ReentrantLock submitLock = new ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<Thread> threads = new ArrayList<>();
    private final AtomicLong bootAttempts = new AtomicLong();

    public WorkerPool(PhpEngine engine, Scheduler scheduler, Path workerScript, Path docRoot,
                      int workerCount, int port) {
        this(engine, scheduler, workerScript, docRoot, workerCount, port, System.getenv());
    }

    /** Test and embedder hook: the environment the worker script sees as {@code $_SERVER}. */
    public WorkerPool(PhpEngine engine, Scheduler scheduler, Path workerScript, Path docRoot,
                      int workerCount, int port, Map<String, String> environment) {
        this.engine = engine;
        this.scheduler = scheduler;
        this.workerScript = workerScript.toAbsolutePath().normalize();
        this.docRoot = docRoot.toAbsolutePath().normalize();
        this.workerCount = Math.max(1, workerCount);
        this.port = port;
        this.environment = Map.copyOf(environment);
        List<String[]> entries = new ArrayList<>(this.environment.size());
        for (Map.Entry<String, String> e : this.environment.entrySet()) {
            entries.add(new String[]{PhpStrings.fromJava(e.getKey()), PhpStrings.fromJava(e.getValue())});
        }
        this.environmentEntries = List.copyOf(entries);
        this.ready = new CountDownLatch(this.workerCount);
    }

    public int workerCount() {
        return workerCount;
    }

    public Map<String, String> environment() {
        return environment;
    }

    public Path workerScript() {
        return workerScript;
    }

    /** Starts the worker threads; the port must not open until {@link #awaitReady()} says so. */
    public void start() {
        for (int i = 0; i < workerCount; i++) {
            int index = i;
            Thread t = PhpThreads.newThread(() -> runWorker(index), "blitz-worker-" + i, true);
            threads.add(t);
            t.start();
        }
    }

    /** Waits for every worker to reach its first handle_request call. False on timeout. */
    public boolean awaitReady(long timeoutMillis) throws InterruptedException {
        return ready.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public long readyCount() {
        return workerCount - ready.getCount();
    }

    /** Test hook: how many times a worker context was started, successful or not. */
    long bootAttempts() {
        return bootAttempts.get();
    }

    /**
     * Fills in the CGI keys FrankenPHP leaves as empty strings. The process environment no
     * longer travels through this request: the worker's prebuilt environment is prepended to
     * {@code $_SERVER} when the request first reads it.
     */
    public void prepare(PhpRequest request) {
        if (request.serverFiller != null) {
            // A worker request builds its CGI values and defaults on first $_SERVER access.
            return;
        }
        request.server.putIfAbsent("PATH_INFO", "");
        request.server.putIfAbsent("DOCUMENT_URI", "");
        request.server.putIfAbsent("HTTPS", "");
        request.server.putIfAbsent("AUTH_TYPE", "");
        request.server.putIfAbsent("REMOTE_HOST", request.remoteAddr);
        request.server.putIfAbsent("REMOTE_IDENT", "");
        request.server.putIfAbsent("CONTENT_TYPE", "");
        request.server.putIfAbsent("CONTENT_LENGTH", "");
    }

    /** Queues a request for an idle worker and blocks until that worker has answered it. */
    public PhpEngine.Result execute(PhpRequest request) {
        CompletableFuture<PhpEngine.Result> response = new CompletableFuture<>();
        submitLock.lock();
        try {
            if (closed.get()) {
                return unavailable(request);
            }
            queue.add(new Job(request, response));
        } finally {
            submitLock.unlock();
        }
        try {
            return response.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable(request);
        } catch (ExecutionException e) {
            return failure(request, e.getCause());
        }
    }

    private static PhpEngine.Result unavailable(PhpRequest request) {
        request.status = 503;
        return new PhpEngine.Result(503, PhpStrings.bytes("Service Unavailable\n"), request, "");
    }

    private static PhpEngine.Result failure(PhpRequest request, Throwable cause) {
        request.status = 500;
        return new PhpEngine.Result(500, PhpStrings.bytes("Internal Server Error: " + cause + "\n"), request, "");
    }

    @Override
    public void close() {
        submitLock.lock();
        try {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            Job job;
            while ((job = queue.poll()) != null) {
                job.response().complete(unavailable(job.request()));
            }
            // One sentinel per worker wakes a thread parked in handle_request(); a thread that
            // is between requests sees closed and exits before taking one.
            for (int i = 0; i < workerCount; i++) {
                queue.add(new Job(null, null));
            }
        } finally {
            submitLock.unlock();
        }
        for (Thread t : threads) {
            try {
                t.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---------------------------------------------------------------- one worker thread

    private void runWorker(int index) {
        int consecutiveBootFailures = 0;
        boolean firstContext = true;
        while (!closed.get()) {
            bootAttempts.incrementAndGet();
            WorkerInstance instance = null;
            Throwable failure = null;
            ByteArrayOutputStream bootErrors = new ByteArrayOutputStream();
            try (PhpEngine.WorkerContext wc = engine.newWorkerContext()) {
                instance = new WorkerInstance(index, wc);
                PhpContext php = wc.php();
                php.setWorkerHandler(instance);
                instance.bootRequest = bootRequest();
                php.bindRequest(instance.bootRequest);
                instance.bootRequest.install();
                wc.installStreams(OutputStream.nullOutputStream(), bootErrors);
                instance.acquireBootSlot();
                try {
                    wc.context().eval(engine.source(workerScript));
                } catch (Throwable t) {
                    failure = t;
                }
            } catch (Throwable t) {
                failure = t;
            } finally {
                if (instance != null) {
                    instance.releaseBootSlot();
                }
                PhpRequest.uninstall();
            }
            String bootErrorText = PhpStrings.toJava(PhpStrings.of(bootErrors.toByteArray()));
            if (!bootErrorText.isEmpty()) {
                System.err.print(bootErrorText);
            }

            boolean reached = instance != null && instance.reached;
            if (failure == null) {
                if (!reached) {
                    consecutiveBootFailures++;
                    logBackoff(index, consecutiveBootFailures,
                            "worker script returned without calling frankenphp_handle_request()");
                } else {
                    // MAX_REQUESTS (or the script's own condition) ended the script normally.
                    String ending = instance.restartReason != null ? instance.restartReason
                            : (instance.exited ? "worker script exited" : "worker script returned");
                    System.err.println("php-truffle worker " + index + ": " + ending + "; restarting");
                    consecutiveBootFailures = 0;
                }
            } else {
                String detail = failure.getMessage() != null ? failure.getMessage() : failure.toString();
                if (reached) {
                    System.err.println("php-truffle worker " + index + ": "
                            + (instance != null && instance.timedOut ? "request timed out" : "fatal error")
                            + "; restarting: " + detail);
                    consecutiveBootFailures = 0;
                } else {
                    consecutiveBootFailures++;
                    logBackoff(index, consecutiveBootFailures,
                            "worker script failed before frankenphp_handle_request(): " + detail);
                }
            }
            firstContext = false;
            if (!reached && !closed.get()) {
                sleepBackoff(consecutiveBootFailures);
            }
        }
        if (!firstContext) {
            System.err.println("php-truffle worker " + index + ": stopped");
        }
    }

    private static void logBackoff(int index, int failures, String reason) {
        System.err.println("php-truffle worker " + index + ": " + reason);
    }

    private static void sleepBackoff(int failures) {
        long delay = Math.min(MAX_BACKOFF_MILLIS, 100L << Math.min(5, Math.max(0, failures - 1)));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            // Nothing interrupts a worker to stop it (close() uses sentinels), so a stray
            // interrupt is dropped; keeping it would turn every later backoff into a spin.
        }
    }

    /** The request the worker script itself runs as, before the first handled request. */
    private PhpRequest bootRequest() {
        PhpRequest r = new PhpRequest();
        r.http = true;
        r.method = "GET";
        r.uri = "/";
        r.queryString = "";
        r.scriptFilename = workerScript.toString();
        r.remoteAddr = "127.0.0.1";
        String name = "/" + workerScript.getFileName();
        long nowMillis = System.currentTimeMillis();
        Map<String, String> s = r.server;
        for (String[] entry : environmentEntries) {
            s.put(entry[0], entry[1]);
        }
        s.put("FRANKENPHP_WORKER", "1");
        s.put("GATEWAY_INTERFACE", "CGI/1.1");
        s.put("REQUEST_METHOD", "GET");
        s.put("REQUEST_URI", workerScript.getFileName().toString());
        s.put("QUERY_STRING", "");
        s.put("SCRIPT_NAME", name);
        s.put("PHP_SELF", name);
        s.put("SCRIPT_FILENAME", PhpStrings.fromJava(workerScript.toString()));
        s.put("DOCUMENT_ROOT", PhpStrings.fromJava(docRoot.toString()));
        s.put("PATH_INFO", "");
        s.put("DOCUMENT_URI", "");
        s.put("CONTENT_TYPE", "");
        s.put("CONTENT_LENGTH", "");
        s.put("SERVER_PROTOCOL", "HTTP/1.1");
        s.put("SERVER_SOFTWARE", "php-truffle");
        s.put("SERVER_NAME", "127.0.0.1");
        s.put("SERVER_PORT", Integer.toString(port));
        s.put("REMOTE_ADDR", "127.0.0.1");
        s.put("REMOTE_HOST", "127.0.0.1");
        s.put("REMOTE_IDENT", "");
        s.put("REMOTE_PORT", "0");
        s.put("REQUEST_SCHEME", "http");
        s.put("HTTPS", "");
        s.put("AUTH_TYPE", "");
        s.put("REQUEST_TIME", Long.toString(nowMillis / 1000));
        s.put("REQUEST_TIME_FLOAT", Double.toString(nowMillis / 1000.0));
        s.put("HTTP_HOST", "localhost:" + port);
        r.environment.putAll(environment);
        return r;
    }

    /** One polyglot context plus the request currently assigned to it. */
    private final class WorkerInstance implements WorkerHandler {

        private final int index;
        private final PhpEngine.WorkerContext wc;
        /** The worker's {@code $_ENV}, built once and shared (frozen) by every request. */
        private final PhpArray environmentArray;
        /** "worker-N", built once; the scheduler task name is the same for every request. */
        private final String taskName;
        private PhpRequest bootRequest;
        private PhpContext.Superglobals bootSuperglobals;
        private Scheduler.Task heldBootTask;
        private boolean bootSlotHeld;
        private boolean reached;
        private boolean exited;
        private boolean timedOut;
        private boolean responded;
        private Job currentJob;
        private ByteArrayOutputStream currentOut;
        private ByteArrayOutputStream currentErr;
        /**
         * The response streams of this worker, reused by every request it handles. A fresh pair
         * per request allocated the stream, its first buffer and every growth step; a worker
         * runs one request at a time and the response body is copied out in {@link #finish}.
         * A stream that grew past {@link #RESPONSE_RETAIN} is replaced, so one large response
         * does not pin its buffer for the life of the worker.
         */
        private ByteArrayOutputStream responseOut = new ByteArrayOutputStream();
        private ByteArrayOutputStream responseErr = new ByteArrayOutputStream();
        private static final int RESPONSE_RETAIN = 1 << 22;
        /** Why the worker script is ending, when it is not a plain MAX_REQUESTS return. */
        private String restartReason;

        WorkerInstance(int index, PhpEngine.WorkerContext wc) {
            this.index = index;
            this.wc = wc;
            this.taskName = "worker-" + index;
            PhpArray environment = PhpArray.withCapacity(environmentEntries.size(), true);
            for (String[] entry : environmentEntries) {
                environment.put(entry[0], entry[1]);
            }
            this.environmentArray = environment.freeze();
        }

        @Override
        public boolean handleRequest(PhpContext ctx, PhpCallable callback) {
            if (currentJob != null) {
                throw new PhpError("frankenphp_handle_request() cannot be called from a request callback");
            }
            if (!reached) {
                reached = true;
                ready.countDown();
            }
            if (bootSuperglobals == null) {
                bootSuperglobals = ctx.captureSuperglobals();
            }
            releaseBootSlot();
            Job job;
            try {
                job = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                job = null;
            }
            if (job == null || job.request() == null || closed.get()) {
                acquireBootSlot();
                return false;
            }
            currentJob = job;
            runOne(job, ctx, callback);
            currentJob = null;
            if (exited) {
                // Let the script unwind exactly as exit() does; runEntry reports it as ending.
                throw new dev.phpruntime.nodes.ControlFlow.Exit();
            }
            if (timedOut) {
                throw new RestartWorker("request timed out");
            }
            acquireBootSlot();
            return true;
        }

        @Override
        public boolean finishRequest(PhpContext ctx) {
            Job job = currentJob;
            if (job == null || responded) {
                return false;
            }
            try {
                ctx.flush();
            } catch (Throwable ignored) {
                // The response is whatever reached the buffer.
            }
            finish(job, ctx);
            return true;
        }

        private void runOne(Job job, PhpContext ctx, PhpCallable callback) {
            PhpRequest request = job.request();
            if (responseOut.size() > RESPONSE_RETAIN) {
                responseOut = new ByteArrayOutputStream();
            }
            if (responseErr.size() > RESPONSE_RETAIN) {
                responseErr = new ByteArrayOutputStream();
            }
            ByteArrayOutputStream out = responseOut;
            ByteArrayOutputStream err = responseErr;
            out.reset();
            err.reset();
            currentOut = out;
            currentErr = err;
            wc.installStreams(out, err);
            Scheduler.Task task = scheduler == null ? null : scheduler.newTask(taskName);
            request.scheduler = task == null ? GuestScheduler.NONE : task;
            boolean slotHeld = false;
            int shutdownBase = ctx.shutdownFunctionCount();
            try {
                request.install();
                if (task != null) {
                    task.attach(ctx.env(), Thread.currentThread());
                    task.bindContext(wc.context());
                    try {
                        task.acquire();
                        slotHeld = true;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        request.status = 503;
                        return;
                    }
                }
                ctx.beginWorkerRequest(request, environmentArray, environmentEntries);
                callback.call(ctx, null, null);
            } catch (dev.phpruntime.nodes.ControlFlow.Exit e) {
                exited = true;
                restartReason = "worker script exited";
            } catch (PhpException e) {
                flushQuietly(ctx, out);
                append(out, e.formattedUncaught() + "\n");
            } catch (PhpError e) {
                flushQuietly(ctx, out);
                append(out, errorText(ctx, e));
                if (!e.isCatchable()) {
                    exited = true; // a fatal error ends the worker script
                    restartReason = "fatal error: " + e.getMessage();
                }
            } catch (Throwable t) {
                String pending = takePendingOutput(ctx);
                append(out, pending);
                if (task != null && task.timedOut()) {
                    timedOut = true;
                    request.status = 500;
                    append(out, timeoutText(request) + "\n");
                } else {
                    request.status = 500;
                    append(out, "PHP Fatal error:  Internal error: " + t + "\n");
                    exited = true;
                    restartReason = "internal error: " + t;
                }
            } finally {
                try {
                    // A timed-out request's context is cancelled: no more guest code runs on it,
                    // and the worker restarts for the timeout, not for a failed shutdown function.
                    if (!timedOut) {
                        ctx.runShutdownFunctionsFrom(shutdownBase);
                    }
                } catch (dev.phpruntime.nodes.ControlFlow.Exit e) {
                    exited = true;
                    restartReason = "exit in a shutdown function";
                } catch (PhpException e) {
                    append(out, e.formattedUncaught() + "\n");
                } catch (PhpError e) {
                    append(out, errorText(ctx, e));
                    if (!e.isCatchable()) {
                        exited = true;
                        restartReason = "fatal error in a shutdown function: " + e.getMessage();
                    }
                } catch (Throwable t) {
                    append(out, "PHP Fatal error:  " + t + "\n");
                    exited = true;
                    restartReason = "internal error in a shutdown function: " + t;
                }
                ctx.removeShutdownFunctionsFrom(shutdownBase);
                if (!responded) {
                    flushQuietly(ctx, out);
                    finish(job, ctx);
                }
                try {
                    ctx.endWorkerRequest(bootSuperglobals, bootRequest);
                } catch (Throwable ignored) {
                    // A cancelled context cannot be cleaned; it is discarded anyway.
                }
                request.closeResources();
                PhpRequest.uninstall();
                bootRequest.install();
                wc.clearStreams();
                if (slotHeld) {
                    task.release();
                }
                responded = false;
            }
        }

        private void finish(Job job, PhpContext ctx) {
            responded = true;
            PhpRequest request = job.request();
            job.response().complete(new PhpEngine.Result(request.status, currentOut.toByteArray(), request,
                    PhpStrings.toJava(PhpStrings.of(currentErr.toByteArray()))));
        }

        private void flushQuietly(PhpContext ctx, ByteArrayOutputStream out) {
            try {
                ctx.flush();
            } catch (Throwable ignored) {
                append(out, takePendingOutput(ctx));
            }
        }

        private void acquireBootSlot() {
            if (bootSlotHeld || closed.get()) {
                return;
            }
            if (scheduler != null) {
                Scheduler.Task task = scheduler.newTask("worker-" + index + ":boot");
                task.attach(wc.php().env(), Thread.currentThread());
                bootRequest.scheduler = task;
                heldBootTask = task;
                try {
                    task.acquire();
                    bootSlotHeld = true;
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    bootRequest.scheduler = GuestScheduler.NONE;
                    heldBootTask = null;
                    return;
                }
            }
            bootRequest.scheduler = GuestScheduler.NONE;
        }

        private void releaseBootSlot() {
            if (!bootSlotHeld || heldBootTask == null) {
                return;
            }
            heldBootTask.release();
            heldBootTask = null;
            bootSlotHeld = false;
        }

        private String timeoutText(PhpRequest request) {
            long millis = scheduler == null ? 0 : scheduler.maxExecutionMillis();
            if (millis <= 0) {
                return "PHP Fatal error:  Maximum execution time exceeded";
            }
            double secs = millis / 1000.0;
            String shown = secs == Math.rint(secs) ? Long.toString((long) secs) : Double.toString(secs);
            return "PHP Fatal error:  Maximum execution time of " + shown + " second"
                    + (secs == 1 ? "" : "s") + " exceeded"
                    + (request.scriptFilename.isEmpty() ? "" : " in " + request.scriptFilename);
        }
    }

    private static void append(ByteArrayOutputStream out, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        try {
            out.write(PhpStrings.bytes(text));
        } catch (IOException ignored) {
            // ByteArrayOutputStream never throws
        }
    }

    private static String takePendingOutput(PhpContext ctx) {
        try {
            return ctx.takePendingOutput();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** The text php prints for one uncaught engine error. */
    private static String errorText(PhpContext ctx, PhpError e) {
        if (e.isCatchable()) {
            try {
                return PhpExceptions.formatUncaught(e.toPhpObject(ctx, null)) + "\n";
            } catch (Throwable ignored) {
                // fall through to the plain message
            }
        }
        return "PHP Fatal error:  " + e.getMessage() + "\n";
    }
}
