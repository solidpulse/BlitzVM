package dev.phpruntime.host;

import dev.phpruntime.js.JsLanguage;
import dev.phpruntime.runtime.GuestScheduler;
import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.sched.Scheduler;
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
        this.engine = Engine.newBuilder("php", JsLanguage.ID)
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
        boolean js = JsLanguage.ID.equals(source.getLanguage());
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
            PhpRequest.uninstall();
        }
    }

    /** Prints the failure the way php-cli does and turns it into an HTTP 500. */
    private int fail(PhpRequest request, OutputStream out, Scheduler.Task task, PolyglotException e, boolean js) {
        SourceSection where = e.getSourceLocation();
        String file = where != null && where.getSource().getPath() != null ? where.getSource().getPath() : request.scriptFilename;
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
            text = "PHP Fatal error:  Internal error: " + e.getMessage();
        }
        try {
            out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
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
                sb.append('#').append(i++).append(' ').append(f.getSourceLocation().getSource().getPath())
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
        Source src = Source.newBuilder(language, abs.toFile()).cached(true).build();
        sources.put(abs, new CachedSource(modified, src));
        return src;
    }

    @Override
    public void close() {
        engine.close();
    }
}
