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
        return PhpCallable.invokeClosure(PhpContext.get(this), indirectCall, this, closureObj, closure, userArgs);
    }
}
