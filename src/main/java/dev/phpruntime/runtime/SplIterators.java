package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.nodes.call.CallArgHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Slow runtime adapter for callback builtins. AST loops use the cached IteratorNode instead. */
public final class SplIterators {
    private SplIterators() {
    }

    public static Object call(PhpObject receiver, String name, Object... args) {
        if (name.equals("rewind") && receiver.nativeState() instanceof PhpGenerator generator && generator.isClosed()) {
            dev.phpruntime.PhpContext ctx = dev.phpruntime.PhpContext.get(null);
            throw new PhpException(PhpExceptions.createBuiltinThrowable(ctx, "Exception",
                    "Cannot traverse an already closed generator", 0L, "", 0, null), null);
        }
        PhpClass.MethodEntry method = receiver.phpClass().lookupMethod(name);
        if (method == null || method.callTarget() == null) {
            throw new PhpError("Call to undefined method " + receiver.phpClass().name() + "::" + name + "()");
        }
        Object[] raw = new Object[2 + args.length + (method.fromTrait() ? 1 : 0)];
        raw[0] = receiver;
        raw[1] = receiver.phpClass();
        int offset = 2;
        if (method.fromTrait()) {
            raw[offset++] = method.declaringClass();
        }
        System.arraycopy(args, 0, raw, offset, args.length);
        return CallArgHelper.unwrapReturn(method.callTarget().call(raw));
    }

    public static PhpObject resolve(PhpObject object) {
        PhpObject it = object;
        int depth = 0;
        while (!it.phpClass().hasSplInterface(SplInterfaces.ITERATOR)) {
            if (!it.phpClass().hasSplInterface(SplInterfaces.AGGREGATE) || depth++ > 256) {
                throw new PhpError("Objects returned by " + object.phpClass().name()
                        + "::getIterator() must be traversable or implement interface Iterator");
            }
            Object result = call(it, "getIterator");
            if (!(result instanceof PhpObject returned) || !SplInterfaces.has(returned, SplInterfaces.TRAVERSABLE)) {
                throw new PhpError("Objects returned by " + it.phpClass().name()
                        + "::getIterator() must be traversable or implement interface Iterator");
            }
            it = returned;
        }
        return it;
    }

    @TruffleBoundary
    public static List<Map.Entry<Object, Object>> entries(PhpObject object, Node site) {
        PhpObject it = resolve(object);
        call(it, "rewind");
        List<Map.Entry<Object, Object>> result = new ArrayList<>();
        while (PhpOps.toBool(call(it, "valid"))) {
            if (site != null) {
                TruffleSafepoint.poll(site);
            }
            Object value = call(it, "current");
            Object key = call(it, "key");
            result.add(Map.entry(key, value));
            call(it, "next");
        }
        return result;
    }
}
