package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpObject;

/**
 * The method entry a class's method id names, kept for the classes one call site has seen.
 * Classes cannot change once declared and are shared by every request, so a site that sees two
 * implementations of an interface keeps each entry as a constant; the fourth class, and a name
 * no class declares, take the lookup.
 */
public abstract class MethodEntryNode extends Node {
    private final int methodId;

    protected MethodEntryNode(int methodId) {
        this.methodId = methodId;
    }

    public static MethodEntryNode create(int methodId) {
        return MethodEntryNodeGen.create(methodId);
    }

    public abstract PhpClass.MethodEntry execute(PhpObject receiver);

    @Specialization(guards = {"receiver.phpClass() == cachedClass", "cachedEntry != null"}, limit = "3")
    protected PhpClass.MethodEntry cached(PhpObject receiver,
                                          @Cached("receiver.phpClass()") PhpClass cachedClass,
                                          @Cached("entryOf(cachedClass)") PhpClass.MethodEntry cachedEntry) {
        return cachedEntry;
    }

    @Specialization(replaces = "cached")
    protected PhpClass.MethodEntry other(PhpObject receiver) {
        return receiver.phpClass().methodById(methodId);
    }

    protected PhpClass.MethodEntry entryOf(PhpClass cls) {
        return cls.methodById(methodId);
    }
}
