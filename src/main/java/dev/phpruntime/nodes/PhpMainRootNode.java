package dev.phpruntime.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpFunction;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpRequest;

import java.util.List;
import java.util.Map;

/**
 * Root of a script. Sets up superglobals from the request, hoists function
 * declarations, attaches the scheduler and runs top-level code.
 */
public final class PhpMainRootNode extends RootNode {

    @Child private PhpNode body;
    private final List<PhpFunction> functions;

    public PhpMainRootNode(PhpLanguage language, FrameDescriptor fd, PhpNode body, List<PhpFunction> functions) {
        super(language, fd);
        this.body = body;
        this.functions = functions;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpContext ctx = PhpContext.get(this);
        PhpRequest request = ctx.request();
        start(ctx, request);
        try {
            body.executeVoid(frame);
        } catch (ControlFlow.Return | ControlFlow.Exit r) {
            // top-level return and exit() both end the script
        } finally {
            finish(ctx, request);
        }
        return PhpNull.INSTANCE;
    }

    @TruffleBoundary
    private void start(PhpContext ctx, PhpRequest request) {
        request.scheduler.attach(ctx.env(), Thread.currentThread());
        for (PhpFunction fn : functions) {
            ctx.declareFunction(fn);
        }
        ctx.writeGlobal("_GET", toArray(request.get));
        ctx.writeGlobal("_POST", toArray(request.post));
        PhpArray all = toArray(request.get);
        request.post.forEach(all::put);
        ctx.writeGlobal("_REQUEST", all);
        ctx.writeGlobal("_COOKIE", new PhpArray());
        ctx.writeGlobal("_SERVER", toArray(request.server));
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
