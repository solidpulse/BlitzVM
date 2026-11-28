package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.call.CallArgHelper;
import dev.phpruntime.nodes.call.ObjectMethodNode;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.SplInterfaces;
import dev.phpruntime.nodes.expr.CondNode;

/** Iterator protocol. Node fields contain only method ids and child call caches, never iterators. */
public final class IteratorNode extends Node {
    @Child private ObjectMethodNode getIterator;
    @Child private ObjectMethodNode rewind;
    @Child private ObjectMethodNode valid;
    @Child private ObjectMethodNode current;
    @Child private ObjectMethodNode key;
    @Child private ObjectMethodNode next;

    /** For bytecode operations, which cache this node per instruction. */
    public static IteratorNode create(com.oracle.truffle.api.nodes.RootNode root) {
        return new IteratorNode(root.getLanguage(PhpLanguage.class));
    }

    public IteratorNode(PhpLanguage language) {
        getIterator = new ObjectMethodNode(language, "getIterator");
        rewind = new ObjectMethodNode(language, "rewind");
        valid = new ObjectMethodNode(language, "valid");
        current = new ObjectMethodNode(language, "current");
        key = new ObjectMethodNode(language, "key");
        next = new ObjectMethodNode(language, "next");
    }

    public PhpObject resolve(PhpObject object) {
        PhpObject iterator = object;
        int depth = 0;
        while (!iterator.phpClass().hasSplInterface(SplInterfaces.ITERATOR)) {
            if (!iterator.phpClass().hasSplInterface(SplInterfaces.AGGREGATE) || depth++ > 256) {
                throw invalidAggregate(object);
            }
            Object result = CallArgHelper.unwrapReturn(getIterator.call(iterator));
            if (!(result instanceof PhpObject returned) || !SplInterfaces.has(returned, SplInterfaces.TRAVERSABLE)) {
                throw invalidAggregate(iterator);
            }
            iterator = returned;
        }
        return iterator;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private PhpError invalidAggregate(PhpObject object) {
        return new PhpError("Exception", "Objects returned by " + object.phpClass().name()
                + "::getIterator() must be traversable or implement interface Iterator", this);
    }

    public void rewind(PhpObject iterator) {
        rewind(iterator, null);
    }

    /** The protocol call attributed to {@code site}, the foreach or yield-from construct. */
    public void rewind(PhpObject iterator, Node site) {
        if (iterator.nativeState() instanceof dev.phpruntime.runtime.PhpGenerator generator && generator.isClosed()) {
            throw closedGenerator();
        }
        rewind.callAt(iterator, site);
    }

    public boolean valid(PhpObject iterator) {
        return valid(iterator, null);
    }

    public boolean valid(PhpObject iterator, Node site) {
        return CondNode.toBool(CallArgHelper.unwrapReturn(valid.callAt(iterator, site)));
    }

    public Object current(PhpObject iterator) {
        return current(iterator, null);
    }

    public Object current(PhpObject iterator, Node site) {
        return CallArgHelper.unwrapReturn(current.callAt(iterator, site));
    }

    public Object key(PhpObject iterator) {
        return key(iterator, null);
    }

    public Object key(PhpObject iterator, Node site) {
        return CallArgHelper.unwrapReturn(key.callAt(iterator, site));
    }

    public void next(PhpObject iterator) {
        next(iterator, null);
    }

    public void next(PhpObject iterator, Node site) {
        next.callAt(iterator, site);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private dev.phpruntime.runtime.PhpException closedGenerator() {
        dev.phpruntime.PhpContext ctx = dev.phpruntime.PhpContext.get(this);
        return new dev.phpruntime.runtime.PhpException(dev.phpruntime.runtime.PhpExceptions.createBuiltinThrowable(
                ctx, "Exception", "Cannot traverse an already closed generator", 0L,
                dev.phpruntime.runtime.PhpBacktrace.sourcePath(this), dev.phpruntime.runtime.PhpBacktrace.sourceLine(this), null), this);
    }
}
