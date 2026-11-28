package dev.phpruntime.host;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpError;
import org.graalvm.polyglot.Source;

import java.util.ArrayList;
import java.util.List;

/**
 * Host side of Blitz\Http\Server: knows the script and the engine, boots the other workers by
 * evaluating that script again in their own contexts, and holds the shared server core.
 *
 * <p>This is the threaded equivalent of Swoole's fork-after-boot. There is no forked master:
 * the first worker to call {@code start()} binds the socket, spawns the others, and then runs
 * its own selector loop. Each spawned worker evaluates the script from the top, so code before
 * {@code start()} runs once per worker (as in Swoole), but it runs in a fresh context rather
 * than in a copy of the parent's memory.
 */
public final class BlitzServerRuntime {

    private static final long STARTUP_TIMEOUT_MILLIS = 30_000;

    private final PhpEngine engine;
    private final Source source;
    private final String scriptPath;
    private final String documentRoot;
    private final InheritableThreadLocal<BlitzServerCore> inheritedCore = new InheritableThreadLocal<>();
    private final List<Thread> threads = new ArrayList<>();
    private volatile BlitzServerCore currentCore;

    public BlitzServerRuntime(PhpEngine engine, Source source, String scriptPath) {
        this.engine = engine;
        this.source = source;
        this.scriptPath = scriptPath;
        this.documentRoot = parentOf(scriptPath);
    }

    public PhpEngine engine() {
        return engine;
    }

    public Source source() {
        return source;
    }

    public String scriptPath() {
        return scriptPath;
    }

    public String documentRoot() {
        return documentRoot;
    }

    /**
     * Called from {@code $server->start()}. The first caller is the primary: it binds, spawns
     * the rest, and becomes worker 0. Every later caller is a worker that ran the script from
     * the top and joins the same core.
     */
    public void startServer(PhpContext ctx, BlitzServerState state) {
        BlitzServerCore core = inheritedCore.get();
        if (core != null) {
            if (state.workerNum() != core.workerNum()) {
                throw new PhpError("Blitz\\Http\\Server::start(): worker_num must be the same in every worker ("
                        + core.workerNum() + " already started)");
            }
            startSecondary(core, ctx, state);
            return;
        }
        startPrimary(ctx, state);
    }

    private void startPrimary(PhpContext ctx, BlitzServerState state) {
        String root = state.documentRoot() == null || state.documentRoot().isEmpty()
                ? documentRoot : state.documentRoot();
        BlitzServerInfo info = new BlitzServerInfo(state.host(), state.requestedPort(),
                scriptPath, root);
        BlitzServerCore core = new BlitzServerCore(info, state.host(), state.requestedPort(),
                state.workerNum(), state.backlog(), state.maxBodyBytes());
        state.setCore(core);
        currentCore = core;
        inheritedCore.set(core);
        try {
            for (int i = 1; i < state.workerNum(); i++) {
                spawn(i, core);
            }
            boolean allReady;
            try {
                allReady = core.awaitReady(STARTUP_TIMEOUT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                allReady = false;
            }
            if (!allReady) {
                Throwable failure = core.failure();
                core.stop();
                throw new PhpError("Blitz\\Http\\Server::start(): only "
                        + (state.workerNum() - 1 - core.readyRemaining()) + " of "
                        + (state.workerNum() - 1) + " workers started"
                        + (failure == null ? "" : ": " + failure));
            }
            Throwable failure = core.failure();
            if (failure != null) {
                core.stop();
                throw new PhpError("Blitz\\Http\\Server::start(): a worker failed: " + failure);
            }
            BlitzServerWorker primary = new BlitzServerWorker(core, ctx, state,
                    state.requestCallback(), 0);
            state.setWorkerId(0);
            core.registerPrimary(primary);
            core.openGate();
            core.runWorker(primary);
        } finally {
            core.stop();
            core.awaitTermination(5_000);
            joinSpawned();
            currentCore = null;
            inheritedCore.remove();
        }
    }

    private void startSecondary(BlitzServerCore core, PhpContext ctx, BlitzServerState state) {
        state.setCore(core);
        try {
            BlitzServerWorker worker = new BlitzServerWorker(core, ctx, state,
                    state.requestCallback(), 0);
            int index = core.registerSecondary(worker);
            state.setWorkerId(index);
            try {
                core.awaitGate();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PhpError("Blitz\\Http\\Server::start(): interrupted before the workers started");
            }
            if (core.failure() != null) {
                throw new PhpError("Blitz\\Http\\Server::start(): the primary worker failed: "
                        + core.failure());
            }
            core.runWorker(worker);
        } finally {
            core.stop();
        }
    }

    private void spawn(int number, BlitzServerCore core) {
        Thread thread = PhpThreads.newThread(() -> {
            try (PhpEngine.WorkerContext wc = engine.newWorkerContext()) {
                wc.installStreams(System.out, System.err);
                PhpContext php = wc.php();
                php.setServerHost(this);
                inheritedCore.set(core);
                try {
                    wc.context().eval(source);
                } catch (Throwable t) {
                    core.secondaryFailed(t);
                }
            } catch (Throwable t) {
                core.secondaryFailed(t);
            } finally {
                inheritedCore.remove();
            }
        }, "blitz-http-worker-" + number, true);
        synchronized (threads) {
            threads.add(thread);
        }
        thread.start();
    }

    private void joinSpawned() {
        List<Thread> copy;
        synchronized (threads) {
            copy = new ArrayList<>(threads);
            threads.clear();
        }
        for (Thread thread : copy) {
            try {
                thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Test and host hook: stop the current server from outside a request. */
    public void stop() {
        BlitzServerCore core = currentCore;
        if (core != null) {
            core.stop();
        }
    }

    public int port() {
        BlitzServerCore core = currentCore;
        return core == null ? -1 : core.port();
    }

    private static String parentOf(String path) {
        if (path == null || path.isEmpty()) {
            return ".";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash <= 0 ? "." : path.substring(0, slash);
    }
}
