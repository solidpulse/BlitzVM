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

    @Child private PhpNode body;
    private final List<PhpFunction> functions;
    private final List<PhpClassTemplate> topLevelClasses;
    private final int hoistedClassesSlot;

    public PhpMainRootNode(PhpLanguage language, FrameDescriptor fd, Source source, PhpNode body,
                           List<PhpFunction> functions, List<PhpClassTemplate> topLevelClasses, int hoistedClassesSlot) {
        super(language, fd, source, 1);
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
        if (isEntry) {
            start(ctx, request);
        }
        declareFunctions(ctx);
        if (hoistedClassesSlot >= 0) {
            frame.setObject(hoistedClassesSlot, hoistClasses(ctx));
        }
        Object result = 1L;
        try {
            body.executeVoid(frame);
        } catch (ControlFlow.Return r) {
            result = r.value;
        } catch (ControlFlow.Exit e) {
            if (!isEntry) {
                throw e;
            }
        } finally {
            if (isEntry) {
                finish(ctx, request);
            }
        }
        return isEntry ? PhpNull.INSTANCE : result;
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
                && contentType.toLowerCase().startsWith("application/x-www-form-urlencoded")
                ? PhpQuery.parseQuery(request.body) : toArray(request.post);
        ctx.writeGlobal("_GET", get);
        ctx.writeGlobal("_POST", post);
        PhpArray all = get.share().forWrite();
        for (var e : post.entries()) {
            all.put(e.getKey(), e.getValue());
        }
        ctx.writeGlobal("_REQUEST", all);
        ctx.writeGlobal("_COOKIE", PhpQuery.parseCookies(PhpQuery.header(request.headers, "Cookie")));
        ctx.writeGlobal("_SERVER", toArray(request.server));
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
        ctx.flush();
        request.scheduler.detach();
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
