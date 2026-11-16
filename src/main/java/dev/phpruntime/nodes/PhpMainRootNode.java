package dev.phpruntime.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
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
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1) private final String[] includedNames;
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1) private final int[] includedSlots;

    public PhpMainRootNode(PhpLanguage language, FrameDescriptor fd, Source source, PhpNode body,
                           List<PhpFunction> functions, List<PhpClassTemplate> topLevelClasses, int hoistedClassesSlot) {
        super(language, fd, source, 1);
        scopeLayout = fd.getInfo() instanceof dev.phpruntime.runtime.PhpScopeLayout layout ? layout : null;
        globalNames = fd.getInfo() instanceof dev.phpruntime.runtime.PhpGlobalLayout layout ? layout.names() : new String[0];
        includedNames = scopeLayout == null ? new String[0] : scopeLayout.locals().keySet().toArray(new String[0]);
        includedSlots = scopeLayout == null ? new int[0] : scopeLayout.locals().values().stream().mapToInt(Integer::intValue).toArray();
        for (String sg : INIT_GLOBALS) {
            language.globalId(sg);
        }
        this.body = body;
        this.functions = functions;
        this.topLevelClasses = topLevelClasses;
        this.hoistedClassesSlot = hoistedClassesSlot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        boolean isEntry = frame.getArguments().length == 0;
        PhpContext ctx = PhpContext.get(this);
        PhpRequest request = ctx.request();
        if (scopeLayout != null) {
            dev.phpruntime.runtime.PhpSymbolTable table = (dev.phpruntime.runtime.PhpSymbolTable) frame.getArguments()[1];
            frame.setObject(scopeLayout.tableSlot(), table);
            frame.setObject(scopeLayout.thisSlot(), table.thisValue);
            frame.setObject(scopeLayout.calledClassSlot(), table.calledClass);
            frame.setObject(scopeLayout.lexicalClassSlot(), table.lexicalClass);
            initializeIncludedLocals(frame, table);
        }
        Object result = !isEntry && frame.getArguments()[0] == dev.phpruntime.nodes.expr.EvalNode.EVAL_MODE
                ? PhpNull.INSTANCE : 1L;
        try {
            if (isEntry) {
                start(ctx, request);
                ctx.reserveGlobals(globalNames);
            }
            declareFunctions(ctx);
            if (hoistedClassesSlot >= 0) {
                frame.setObject(hoistedClassesSlot, hoistClasses(ctx));
            }
            body.executeVoid(frame);
        } catch (ControlFlow.Return r) {
            result = r.value;
        } catch (ControlFlow.Exit e) {
            if (!isEntry) {
                throw e;
            }
        } catch (dev.phpruntime.runtime.PhpException ex) {
            if (isEntry) {
                if (ctx.handleUncaughtException(ex.exceptionObject())) {
                    return PhpNull.INSTANCE;
                }
                ex.formattedUncaught();
            }
            throw ex;
        } catch (dev.phpruntime.runtime.PhpError err) {
            if (isEntry && err.isCatchable()) {
                dev.phpruntime.runtime.PhpObject errObj = err.toPhpObject(ctx, this);
                if (ctx.handleUncaughtException(errObj)) {
                    return PhpNull.INSTANCE;
                }
                dev.phpruntime.runtime.PhpException wrapped =
                        new dev.phpruntime.runtime.PhpException(errObj, err.getLocation());
                wrapped.formattedUncaught();
                throw wrapped;
            }
            throw err;
        } finally {
            if (isEntry) {
                finish(ctx, request);
            }
        }
        return isEntry ? PhpNull.INSTANCE : result;
    }

    @com.oracle.truffle.api.nodes.ExplodeLoop
    private void initializeIncludedLocals(VirtualFrame frame, dev.phpruntime.runtime.PhpSymbolTable table) {
        for (int i = 0; i < includedNames.length; i++) {
            frame.setObject(includedSlots[i], table.access(includedNames[i]));
        }
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
