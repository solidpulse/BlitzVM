package dev.phpruntime.host;

import dev.phpruntime.runtime.GuestScheduler;
import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.sched.Scheduler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs PHP scripts. One polyglot {@link Engine} is shared by all requests so parsed
 * ASTs, their profiling state and any JIT-compiled code are reused; each request gets
 * its own lightweight {@link Context}, so requests share nothing at the PHP level.
 */
public final class PhpEngine implements AutoCloseable {

    /** Result of one execution: the response body plus whatever the script set via header(). */
    public record Result(int status, byte[] body, PhpRequest request, String errors) {
        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final Engine engine;
    private final Scheduler scheduler;
    private final ConcurrentHashMap<Path, CachedSource> sources = new ConcurrentHashMap<>();

    private record CachedSource(long modified, Source source) {
    }

    public PhpEngine(Scheduler scheduler) {
        this.scheduler = scheduler;
        this.engine = Engine.newBuilder("php")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    /** Executes a script and buffers its output, as the HTTP server needs. */
    public Result execute(Path script, PhpRequest request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = run(source(script), request, out, err);
        return new Result(status, out.toByteArray(), request, err.toString(StandardCharsets.UTF_8));
    }

    /** Executes a script streaming output, as the CLI needs. Returns a process exit code. */
    public int executeStreaming(Path script, PhpRequest request, OutputStream out, OutputStream err) throws IOException {
        return run(source(script), request, out, err) >= 500 ? 255 : 0;
    }

    private int run(Source source, PhpRequest request, OutputStream out, OutputStream err) {
        Scheduler.Task task = scheduler == null ? null : scheduler.newTask(request.uri);
        request.scheduler = task == null ? GuestScheduler.NONE : task;
        request.install();
        try (Context ctx = Context.newBuilder("php").engine(engine).out(out).err(err).build()) {
            if (task != null) {
                task.bindContext(ctx);
                task.acquire();
            }
            try {
                ctx.eval(source);
                return request.status;
            } catch (PolyglotException e) {
                return fail(request, out, task, e);
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
            // close() of a cancelled context rethrows the cancellation
            return task != null && task.timedOut() ? 500 : fail(request, out, task, e);
        } finally {
            PhpRequest.uninstall();
        }
    }

    private static int fail(PhpRequest request, OutputStream out, Scheduler.Task task, PolyglotException e) {
        String message;
        if (task != null && task.timedOut()) {
            message = "Maximum execution time exceeded";
        } else if (e.isCancelled()) {
            message = "Execution cancelled";
        } else if (e.isGuestException()) {
            message = e.getMessage();
        } else {
            message = "Internal error: " + e.getMessage();
        }
        String line = e.getSourceLocation() != null ? " on line " + e.getSourceLocation().getStartLine() : "";
        String text = "PHP Fatal error:  " + message + line + "\n";
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // client went away
        }
        request.status = 500;
        return 500;
    }

    private Source source(Path script) throws IOException {
        Path abs = script.toAbsolutePath().normalize();
        long modified = Files.getLastModifiedTime(abs).toMillis();
        CachedSource cached = sources.get(abs);
        if (cached != null && cached.modified() == modified) {
            return cached.source();
        }
        // A cached Source lets the shared engine reuse the parsed call target across contexts.
        Source src = Source.newBuilder("php", abs.toFile()).cached(true).build();
        sources.put(abs, new CachedSource(modified, src));
        return src;
    }

    @Override
    public void close() {
        engine.close();
    }
}
