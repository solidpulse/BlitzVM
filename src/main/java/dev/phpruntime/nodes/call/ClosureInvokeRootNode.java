package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.PhpRootNode;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpClosure;
import dev.phpruntime.runtime.PhpObject;

/**
 * Built-in {@code Closure::__invoke(...)} root node so {@code $c->__invoke(...)},
 * {@code method_exists($c, '__invoke')}, and {@code [$c, '__invoke']} work identically to {@code $c(...)}.
 */
public final class ClosureInvokeRootNode extends PhpRootNode {

    @Child private IndirectCallNode indirectCall = Truffle.getRuntime().createIndirectCallNode();
    @Child private DispatchCallNode dispatch = new DispatchCallNode();

    public ClosureInvokeRootNode(PhpLanguage language) {
        super(language, new FrameDescriptor(), null, 0);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        PhpObject closureObj = (PhpObject) args[0];
        PhpClosure closure = closureObj.closure();
        Object[] userArgs = new Object[args.length - 2];
        System.arraycopy(args, 2, userArgs, 0, userArgs.length);
        PhpContext ctx = PhpContext.get(this);
        // Closure::__invoke is an internal method: like array_map, it calls the closure weakly.
        dev.phpruntime.runtime.CallMode previous = ctx.pendingCallMode();
        ctx.setPendingCallMode(dev.phpruntime.runtime.CallMode.internal(closure.callTarget() == null ? null
                : dev.phpruntime.nodes.PhpFunctionRootNode.of(closure.callTarget().getRootNode())));
        try {
            Object res = switch (closure.kind()) {
                case CLOSURE -> {
                    com.oracle.truffle.api.RootCallTarget target = closure.callTarget();
                    com.oracle.truffle.api.RootCallTarget cached = dispatch.cachedTarget();
                    if (cached == target) {
                        yield dispatch.call(cached, PhpCallable.buildClosureArgs(cached, closure.boundThis(),
                                closure.calledClass(), closureObj, userArgs, ctx, this));
                    }
                    yield dispatch.call(target, PhpCallable.buildClosureArgs(target, closure.boundThis(),
                            closure.calledClass(), closureObj, userArgs, ctx, this));
                }
                case USER_FUNCTION, METHOD, MAGIC_CALL, BUILTIN ->
                        PhpCallable.invokeClosure(ctx, indirectCall, this, closureObj, closure, userArgs);
            };
            return res;
        } finally {
            ctx.setPendingCallMode(previous);
        }
    }
}
