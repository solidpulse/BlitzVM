package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.call.CallArgHelper;
import dev.phpruntime.nodes.call.ObjectMethodNode;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.SplInterfaces;

/** Iterator protocol. Node fields contain only method ids and child call caches, never iterators. */
public final class IteratorNode extends Node {
    @Child private ObjectMethodNode getIterator;
    @Child private ObjectMethodNode rewind;
    @Child private ObjectMethodNode valid;
    @Child private ObjectMethodNode current;
    @Child private ObjectMethodNode key;
    @Child private ObjectMethodNode next;

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

    private PhpError invalidAggregate(PhpObject object) {
        return new PhpError("Objects returned by " + object.phpClass().name()
                + "::getIterator() must be traversable or implement interface Iterator", this);
    }

    public void rewind(PhpObject iterator) {
        rewind.call(iterator);
    }

    public boolean valid(PhpObject iterator) {
        return PhpOps.toBool(CallArgHelper.unwrapReturn(valid.call(iterator)));
    }

    public Object current(PhpObject iterator) {
        return CallArgHelper.unwrapReturn(current.call(iterator));
    }

    public Object key(PhpObject iterator) {
        return CallArgHelper.unwrapReturn(key.call(iterator));
    }

    public void next(PhpObject iterator) {
        next.call(iterator);
    }
}
