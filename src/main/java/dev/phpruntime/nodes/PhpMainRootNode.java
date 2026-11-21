package dev.phpruntime.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.source.Source;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpFunction;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpQuery;
import dev.phpruntime.runtime.PhpRequest;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Root of a script. Sets up superglobals from the request, hoists function
 * declarations, attaches the scheduler and runs top-level code.
 */
public final class PhpMainRootNode extends PhpRootNode {

    private static final String[] INIT_GLOBALS = {"_GET", "_POST", "_COOKIE", "_FILES", "_ENV", "_REQUEST", "_SERVER"};

    @Child private PhpNode body;
    private final List<PhpFunction> functions;
    private final List<PhpClassTemplate> topLevelClasses;
    private final int hoistedClassesSlot;
    private final dev.phpruntime.runtime.PhpScopeLayout scopeLayout;
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1) private final String[] globalNames;

    public PhpMainRootNode(PhpLanguage language, FrameDescriptor fd, Source source, PhpNode body,
                           List<PhpFunction> functions, List<PhpClassTemplate> topLevelClasses, int hoistedClassesSlot) {
        super(language, fd, source, 1);
        scopeLayout = fd.getInfo() instanceof dev.phpruntime.runtime.PhpScopeLayout layout ? layout : null;
        globalNames = fd.getInfo() instanceof dev.phpruntime.runtime.PhpGlobalLayout layout ? layout.names() : new String[0];
        for (String sg : INIT_GLOBALS) {
            language.globalId(sg);
        }
        this.body = body;
        this.functions = functions;
        this.topLevelClasses = topLevelClasses;
        this.hoistedClassesSlot = hoistedClassesSlot;
    }

    /** The arguments the entry script's compiled body runs with (see EntryRootNode). */
    public static final Object ENTRY_BODY = new Object();
    public static final Object[] ENTRY_BODY_ARGS = {ENTRY_BODY};

    /** Whether a file root's arguments are the request's entry script rather than an include. */
    public static boolean isEntryArgs(Object[] args) {
        return args.length == 0 || args[0] == ENTRY_BODY;
    }

    /**
     * Runs the request's entry script around {@code body}: request start and globals, then
     * {@code exit} and uncaught exceptions as php reports them, then request finish.
     */
    public Object runEntry(PhpContext ctx, java.util.function.Supplier<Object> body) {
        PhpRequest request = ctx.request();
        try {
            start(ctx, request);
            ctx.reserveGlobals(globalNames);
            body.get();
        } catch (ControlFlow.Exit e) {
            // exit() ends the entry script normally, but a pooled context is replaced after
            // an exit so the host never has to reason about the state it left behind.
            ctx.markEndedAbnormally();
        } catch (dev.phpruntime.snapshot.SnapshotAbort e) {
            // The template run ends at the boot-include boundary; skip shutdown functions and
            // output, which belong to the request the snapshot will serve.
            ctx.markSnapshotAborted();
            ctx.markEndedAbnormally();
        } catch (dev.phpruntime.runtime.PhpException ex) {
            if (ctx.handleUncaughtException(ex.exceptionObject())) {
                ctx.markEndedAbnormally();
                return PhpNull.INSTANCE;
            }
            ex.formattedUncaught();
            throw ex;
        } catch (dev.phpruntime.runtime.PhpError err) {
            if (err.isCatchable()) {
                dev.phpruntime.runtime.PhpObject errObj = err.toPhpObject(ctx, this);
                if (ctx.handleUncaughtException(errObj)) {
                    ctx.markEndedAbnormally();
                    return PhpNull.INSTANCE;
                }
                dev.phpruntime.runtime.PhpException wrapped =
                        new dev.phpruntime.runtime.PhpException(errObj, err.getLocation());
                wrapped.formattedUncaught();
                throw wrapped;
            }
            throw err;
        } finally {
            finish(ctx, request);
        }
        return PhpNull.INSTANCE;
    }

    /** A root that runs a file's top-level code: this AST root or the bytecode compiled from it. */
    public static boolean isMain(com.oracle.truffle.api.nodes.RootNode root) {
        return root instanceof PhpMainRootNode
                || root instanceof dev.phpruntime.bytecode.PhpBytecodeRootNode b && b.mainRoot() != null;
    }

    public PhpNode body() {
        return body;
    }

    /** The functions this script declares, for reflection over a parsed unit. */
    public List<PhpFunction> functions() {
        return functions;
    }

    /** The classes, interfaces, traits and enums this script declares at top level. */
    public List<PhpClassTemplate> topLevelClasses() {
        return topLevelClasses;
    }

    public dev.phpruntime.runtime.PhpScopeLayout scopeLayout() {
        return scopeLayout;
    }

    /** Whether this file's top-level code can run without a symbol table (not a local include). */
    public boolean usesSymbolTable() {
        return scopeLayout != null;
    }

    public boolean hoists() {
        return hoistedClassesSlot >= 0;
    }

    /** What execute does before the body of an included file: declarations; the hoisted flags or null. */
    @TruffleBoundary
    public boolean[] prologue(PhpContext ctx) {
        declareFunctions(ctx);
        return hoistedClassesSlot >= 0 ? hoistClasses(ctx) : null;
    }

    @TruffleBoundary
    private void start(PhpContext ctx, PhpRequest request) {
        request.scheduler.attach(ctx.env(), Thread.currentThread());
        String entryPath = source().getPath();
        if (entryPath != null && !entryPath.isEmpty()) {
            TruffleFile f = ctx.env().getPublicTruffleFile(entryPath);
            try {
                ctx.markIncluded(f.getCanonicalFile().getPath());
            } catch (IOException e) {
                ctx.markIncluded(f.getAbsoluteFile().normalize().getPath());
            }
        }
        // Parse the raw query string and urlencoded body PHP's way (so "a[]=1" builds arrays)
        // when the host provides them; otherwise fall back to the flat maps.
        PhpArray get = request.queryString.isEmpty() ? toArray(request.get) : PhpQuery.parseQuery(request.queryString);
        String contentType = PhpQuery.header(request.headers, "Content-Type");
        PhpArray post = !request.body.isEmpty() && contentType != null
                && dev.phpruntime.runtime.PhpStrings.lower(contentType).startsWith("application/x-www-form-urlencoded")
                ? PhpQuery.parseQuery(request.body) : toArray(request.post);
        ctx.writeGlobal("_GET", get);
        ctx.writeGlobal("_POST", post);
        PhpArray all = get.share().forWrite();
        for (var e : post.entries()) {
            all.put(e.getKey(), e.getValue());
        }
        ctx.writeGlobal("_REQUEST", all);
        ctx.writeGlobal("_COOKIE", PhpQuery.parseCookies(PhpQuery.header(request.headers, "Cookie")));
        ctx.writeGlobal("_FILES", new PhpArray());
        // PHP_SAPI: php-fpm's name for HTTP requests (code checks 'cli', 'cgi-fcgi', 'fpm-fcgi').
        ctx.defineConstant("PHP_SAPI", request.http ? "fpm-fcgi" : "cli", 0);
        PhpArray environment = new PhpArray();
        request.environment.forEach((key, value) -> environment.put(dev.phpruntime.runtime.PhpStrings.fromJava(key),
                dev.phpruntime.runtime.PhpStrings.fromJava(value)));
        ctx.writeGlobal("_ENV", environment);
        ctx.writeGlobal("_SERVER", toArray(request.server));
        if (!request.scriptFilename.isEmpty() && !request.server.containsKey("SCRIPT_FILENAME")) {
            PhpArray server = toArray(request.server);
            server.put("SCRIPT_FILENAME", dev.phpruntime.runtime.PhpStrings.fromJava(request.scriptFilename));
            ctx.writeGlobal("_SERVER", server);
        }
        if (request.argv.length > 0) {
            PhpArray argv = PhpArray.ofList(java.util.Arrays.asList(request.argv));
            ctx.writeGlobal("argv", argv);
            ctx.writeGlobal("argc", (long) request.argv.length);
            PhpArray server = toArray(request.server);
            server.put("argv", argv.share());
            server.put("argc", (long) request.argv.length);
            ctx.writeGlobal("_SERVER", server);
        }
        ctx.initializeGlobalOrder();
        dev.phpruntime.snapshot.SnapshotSupport.entryStarted(ctx);
    }

    @TruffleBoundary
    private void declareFunctions(PhpContext ctx) {
        for (PhpFunction fn : functions) {
            ctx.declareFunction(fn);
        }
    }

    @TruffleBoundary
    private boolean[] hoistClasses(PhpContext ctx) {
        boolean[] hoisted = new boolean[topLevelClasses.size()];
        for (int i = 0; i < topLevelClasses.size(); i++) {
            PhpClassTemplate t = topLevelClasses.get(i);
            if (ctx.canHoistClass(t)) {
                ctx.declareClass(t);
                hoisted[i] = true;
            }
        }
        return hoisted;
    }

    @TruffleBoundary
    private static void finish(PhpContext ctx, PhpRequest request) {
        if (ctx.snapshotAborted()) {
            // The snapshot has been taken (or refused); nothing from the template request may run.
            request.scheduler.detach();
            return;
        }
        try {
            ctx.runShutdownFunctions();
        } finally {
            ctx.flush();
            request.scheduler.detach();
        }
    }

    private static PhpArray toArray(Map<String, String> m) {
        PhpArray a = new PhpArray();
        m.forEach(a::put);
        return a;
    }

    @Override
    public String getName() {
        return "{main}";
    }
}
