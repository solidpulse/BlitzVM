package dev.phpruntime.js.nodes;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import dev.phpruntime.js.JsContext;
import dev.phpruntime.js.JsLanguage;
import dev.phpruntime.js.runtime.JsOps;
import dev.phpruntime.js.runtime.JsValues.JsBuiltin;
import dev.phpruntime.js.runtime.JsValues.JsFunction;
import dev.phpruntime.js.runtime.JsValues.JsObject;
import dev.phpruntime.js.runtime.JsValues.JsPromise;
import dev.phpruntime.js.runtime.JsValues.Undefined;
import dev.phpruntime.runtime.PhpRequest;

import java.util.Map;

/**
 * Root of a JavaScript file. Runs the module's top-level code; when serving HTTP and
 * {@code module.exports} is a function, calls it as a Node-style {@code (req, res)}
 * handler and awaits its result.
 */
public final class JsModuleRootNode extends RootNode {

    @Child private JsNode body;
    @Child private IndirectCallNode handlerCall = IndirectCallNode.create();
    @CompilationFinal(dimensions = 1) private final String[] globalNames;
    @CompilationFinal(dimensions = 1) private final int[] globalSlots;
    private final Source source;

    public JsModuleRootNode(JsLanguage language, FrameDescriptor fd, JsNode body, String[] globalNames,
                            int[] globalSlots, Source source) {
        super(language, fd);
        this.body = body;
        this.globalNames = globalNames;
        this.globalSlots = globalSlots;
        this.source = source;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        JsContext ctx = JsContext.get(this);
        PhpRequest request = ctx.request();
        start(ctx, request);
        try {
            for (int i = 0; i < globalNames.length; i++) {
                frame.setObject(globalSlots[i], ctx.global(globalNames[i]));
            }
            try {
                body.executeVoid(frame);
            } catch (JsControl.Return r) {
                // top-level return ends the module
            }
            if (request.http) {
                serve(ctx, request);
            }
        } finally {
            request.scheduler.detach();
        }
        return true; // must be an interop value; the host ignores it
    }

    @TruffleBoundary
    private static void start(JsContext ctx, PhpRequest request) {
        request.scheduler.attach(ctx.env(), Thread.currentThread());
    }

    private void serve(JsContext ctx, PhpRequest request) {
        Object handler = ctx.module().get("exports");
        if (!(handler instanceof JsFunction fn)) {
            return;
        }
        JsObject res = response(ctx, request);
        Object result = handlerCall.call(fn.target, new Object[]{fn.scope, Undefined.INSTANCE, request(request), res});
        if (result instanceof JsPromise p) {
            awaitPromise(p);
        }
        finish(request, res);
    }

    private void awaitPromise(JsPromise p) {
        long wait = p.readyAtNanos - System.nanoTime();
        if (wait > 0) {
            sleep(wait);
        }
    }

    @TruffleBoundary
    private void sleep(long nanos) {
        JsContext.get(this).request().scheduler.blocking(this, () -> Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000)));
    }

    @TruffleBoundary
    private static JsObject request(PhpRequest r) {
        JsObject req = new JsObject();
        req.put("method", r.method);
        req.put("url", r.uri);
        JsObject query = new JsObject();
        for (Map.Entry<String, String> e : r.get.entrySet()) {
            query.put(e.getKey(), e.getValue());
        }
        req.put("query", query);
        JsObject headers = new JsObject();
        for (Map.Entry<String, String> e : r.headers.entrySet()) {
            headers.put(e.getKey().toLowerCase(), e.getValue());
        }
        req.put("headers", headers);
        return req;
    }

    @TruffleBoundary
    private static JsObject response(JsContext ctx, PhpRequest r) {
        JsObject res = new JsObject();
        res.put("statusCode", 200);
        res.put("setHeader", new JsBuiltin("setHeader", (self, a) -> {
            r.setHeader(JsOps.toStr(JsBuiltin.arg(a, 0)), JsOps.toStr(JsBuiltin.arg(a, 1)), true);
            return self;
        }));
        res.put("write", new JsBuiltin("write", (self, a) -> {
            ctx.write(JsOps.toStr(JsBuiltin.arg(a, 0)));
            return true;
        }));
        res.put("end", new JsBuiltin("end", (self, a) -> {
            Object chunk = JsBuiltin.arg(a, 0);
            if (chunk != Undefined.INSTANCE) {
                ctx.write(JsOps.toStr(chunk));
            }
            return self;
        }));
        return res;
    }

    @TruffleBoundary
    private static void finish(PhpRequest r, JsObject res) {
        Object status = res.get("statusCode");
        if (JsOps.isNumber(status)) {
            r.status = (int) JsOps.toDouble(status);
        }
        if (r.responseHeaders.stream().noneMatch(h -> h[0].equalsIgnoreCase("Content-Type"))) {
            r.setHeader("Content-Type", "text/plain; charset=UTF-8", true);
        }
    }

    @Override
    public String getName() {
        return "(module)";
    }

    @Override
    public SourceSection getSourceSection() {
        return source == null ? null : source.createSection(1);
    }
}
