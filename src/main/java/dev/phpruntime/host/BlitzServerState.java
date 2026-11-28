package dev.phpruntime.host;

import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNativeState;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;

/**
 * The native state of one PHP {@code Blitz\Http\Server} object. Every worker runs the script,
 * so every worker owns one of these objects with its own callbacks; only the listening socket
 * and the worker set are shared through {@link BlitzServerCore}.
 */
public final class BlitzServerState implements PhpNativeState {

    private final BlitzServerRuntime runtime;
    private final String host;
    private final int requestedPort;
    private int workerNum = 1;
    private int backlog = 511;
    private int maxBodyBytes = 8 * 1024 * 1024;
    private String documentRoot;
    private PhpCallable requestCallback;
    private PhpCallable workerStartCallback;
    private PhpCallable workerStopCallback;
    private BlitzServerCore core;
    private PhpObject phpObject;
    private int workerId = -1;
    private int boundPort;
    private boolean started;

    public BlitzServerState(BlitzServerRuntime runtime, String host, int port) {
        this.runtime = runtime;
        this.host = host;
        this.requestedPort = port;
        this.boundPort = port;
    }

    public BlitzServerRuntime runtime() {
        return runtime;
    }

    public String host() {
        return host;
    }

    public int requestedPort() {
        return requestedPort;
    }

    public int workerNum() {
        return workerNum;
    }

    public int backlog() {
        return backlog;
    }

    public int maxBodyBytes() {
        return maxBodyBytes;
    }

    public String documentRoot() {
        return documentRoot;
    }

    public PhpCallable requestCallback() {
        return requestCallback;
    }

    public PhpCallable workerStartCallback() {
        return workerStartCallback;
    }

    public PhpCallable workerStopCallback() {
        return workerStopCallback;
    }

    public PhpObject phpObject() {
        return phpObject;
    }

    public void setPhpObject(PhpObject phpObject) {
        this.phpObject = phpObject;
    }

    public int workerId() {
        return workerId;
    }

    public int boundPort() {
        return boundPort;
    }

    public void setWorkerId(int workerId) {
        this.workerId = workerId;
    }

    public void setCore(BlitzServerCore core) {
        this.core = core;
        this.boundPort = core.port();
    }

    /** Applies one {@code set()} entry. Unknown options are kept out of the server path. */
    public void setOption(PhpContext ctx, String name, Object value) {
        switch (dev.phpruntime.runtime.PhpStrings.lower(name)) {
            case "worker_num" -> {
                long n = PhpOps.toLong(value);
                if (n < 1 || n > 1024) {
                    throw new PhpError(PhpError.VALUE_ERROR,
                            "Blitz\\Http\\Server::set(): worker_num must be between 1 and 1024", null);
                }
                workerNum = (int) n;
            }
            case "backlog" -> {
                long n = PhpOps.toLong(value);
                if (n < 1 || n > 65535) {
                    throw new PhpError(PhpError.VALUE_ERROR,
                            "Blitz\\Http\\Server::set(): backlog must be between 1 and 65535", null);
                }
                backlog = (int) n;
            }
            case "max_body_size" -> {
                long n = PhpOps.toLong(value);
                if (n < 0 || n > 1L << 30) {
                    throw new PhpError(PhpError.VALUE_ERROR,
                            "Blitz\\Http\\Server::set(): max_body_size must be between 0 and 1 GiB", null);
                }
                maxBodyBytes = (int) n;
            }
            case "document_root" -> documentRoot = PhpOps.toStr(value);
            case "max_request", "max_conn", "log_level", "enable_coroutine",
                 "hook_flags", "enable_preemptive_scheduler", "enable_static_handler" ->
                    // Accepted for parity with common Swoole configurations; this server has
                    // no coroutines, no request cap and no static handler.
                    {
                    }
            default -> {
                // Unknown settings are ignored rather than fatal, so an existing script's
                // configuration can be reused while aliases are added.
            }
        }
    }

    public void on(PhpContext ctx, String event, PhpCallable callback) {
        switch (dev.phpruntime.runtime.PhpStrings.lower(event)) {
            case "request" -> requestCallback = callback;
            case "workerstart" -> workerStartCallback = callback;
            case "workerstop" -> workerStopCallback = callback;
            default -> throw new PhpError("Blitz\\Http\\Server::on(): unknown event \"" + event + "\"");
        }
    }

    public void start(PhpContext ctx) {
        if (started) {
            throw new PhpError("Blitz\\Http\\Server::start(): the server has already been started");
        }
        if (requestCallback == null) {
            throw new PhpError("Blitz\\Http\\Server::start(): no request callback is registered");
        }
        if (runtime == null) {
            if (workerNum != 1) {
                throw new PhpError("Blitz\\Http\\Server::start(): worker_num > 1 needs the php-truffle CLI");
            }
            String script = ctx.request().scriptFilename;
            BlitzServerInfo info = new BlitzServerInfo(host, requestedPort, script, parentOf(script));
            core = new BlitzServerCore(info, host, requestedPort, 1, backlog, maxBodyBytes);
            workerId = 0;
            boundPort = core.port();
            started = true;
            try {
                BlitzServerWorker worker = new BlitzServerWorker(core, ctx, this, requestCallback, 0);
                core.runWorker(worker);
            } finally {
                core.stop();
                core.awaitTermination(5_000);
                core = null;
                started = false;
            }
            return;
        }
        started = true;
        try {
            runtime.startServer(ctx, this);
        } finally {
            core = null;
            started = false;
        }
    }

    public void stop() {
        BlitzServerCore current = core;
        if (current != null) {
            current.stop();
        }
    }

    public int port() {
        return core != null ? core.port() : boundPort;
    }

    @Override
    public PhpNativeState copy() {
        // A cloned server object is not a useful thing; keep the same configuration and let
        // clone share the (unstarted or started) server identity.
        return this;
    }

    @Override
    public java.util.Map<String, Object> debugProperties(PhpObject self) {
        return null;
    }

    private static String parentOf(String script) {
        if (script == null || script.isEmpty()) {
            return ".";
        }
        int slash = Math.max(script.lastIndexOf('/'), script.lastIndexOf('\\'));
        return slash <= 0 ? "." : script.substring(0, slash);
    }
}
