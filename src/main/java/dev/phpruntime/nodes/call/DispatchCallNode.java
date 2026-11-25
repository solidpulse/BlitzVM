package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.CallTarget;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.runtime.PhpClass;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpOps;

/**
 * Shared monomorphic-with-indirect-fallback call dispatch helper for method and
 * constructor call sites under ContextPolicy.SHARED.
 */
public final class DispatchCallNode extends Node {

    @Child private DirectCallNode directCall;
    @Child private IndirectCallNode indirectCall;

    /** The last trait call site wrapped for the backtrace, so a call site does not allocate one per call. */
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal private TraitSiteCache traitSiteCache;

    private record TraitSiteCache(dev.phpruntime.runtime.PhpBacktrace.TraceSite traceSite, Object site, PhpClass traitSelf) {
    }

    public RootCallTarget cachedTarget() {
        DirectCallNode direct = directCall;
        return direct != null ? (RootCallTarget) direct.getCallTarget() : null;
    }

    /** True once this site has seen a second target (an indirect call node is installed). */
    public boolean isPolymorphic() {
        return indirectCall != null;
    }

    public static DispatchCallNode create() {
        return new DispatchCallNode();
    }

    public Object call(RootCallTarget target, Object[] args) {
        // Read each child once: another request may be installing it concurrently.
        DirectCallNode direct = directCall;
        if (direct != null && direct.getCallTarget() == target) {
            return direct.call(args);
        }
        IndirectCallNode indirect = indirectCall;
        if (indirect != null) {
            return indirect.call(target, args);
        }
        return callDynamic(target, args);
    }

    /** {@link #call} with the call site pushed for the callee's exception traces. */
    public Object call(RootCallTarget target, Object[] args, Object site) {
        return call(target, args, site, null);
    }

    /** With the class a trait method runs for, which php reports as the frame's class. */
    public Object call(RootCallTarget target, Object[] args, Object site, PhpClass traitSelf) {
        dev.phpruntime.PhpContext ctx = dev.phpruntime.PhpContext.get(this);
        int depth = ctx.pushTraceFrame(traceSite(target != null && isTraitMethod(target), site, traitSelf));
        try {
            return call(target, args);
        } finally {
            ctx.popTraceFrame(depth);
        }
    }

    /**
     * A magic method the runtime calls on its own: php gives its frame the caller's current
     * line, which no call site here knows, so the frame is an unknown site.
     */
    public Object callUnsitedMethod(PhpClass.MethodEntry entry, Object[] args) {
        dev.phpruntime.PhpContext ctx = dev.phpruntime.PhpContext.get(this);
        int depth = ctx.pushTraceFrame(dev.phpruntime.runtime.PhpBacktrace.UNKNOWN_SITE);
        try {
            return callMethod(entry, args);
        } finally {
            ctx.popTraceFrame(depth);
        }
    }

    /** {@link #callMethod} with the call site pushed for the callee's exception traces. */
    public Object callMethod(PhpClass.MethodEntry entry, Object[] args, Node site) {
        return callMethod(entry, args, site, null);
    }

    public Object callMethod(PhpClass.MethodEntry entry, Object[] args, Node site, PhpClass traitSelf) {
        dev.phpruntime.PhpContext ctx = dev.phpruntime.PhpContext.get(this);
        int depth = ctx.pushTraceFrame(traceSite(entry.fromTrait(), site, traitSelf));
        try {
            return callMethod(entry, args);
        } finally {
            ctx.popTraceFrame(depth);
        }
    }

    /**
     * {@code traitMethod} comes from the method entry where there is one: a method call passes
     * its called class as {@code traitSelf}, and walking from a call target that is not a
     * compile-time constant to its PHP function on every call cost each call site about 250
     * IR nodes on /blog.
     */
    private Object traceSite(boolean traitMethod, Object site, PhpClass traitSelf) {
        if (traitSelf == null || !traitMethod || !(site instanceof Node node)) {
            return site;
        }
        TraitSiteCache cached = traitSiteCache;
        if (cached != null && cached.site == site && cached.traitSelf == traitSelf) {
            return cached.traceSite;
        }
        return traceSiteSlow(node, site, traitSelf);
    }

    /** The first call of a site, or a call whose trait class changed (a rebind); entry is rare. */
    @TruffleBoundary
    private Object traceSiteSlow(Node node, Object site, PhpClass traitSelf) {
        java.util.concurrent.locks.Lock lock = getLock();
        lock.lock();
        try {
            TraitSiteCache cached = traitSiteCache;
            if (cached != null && cached.site == site && cached.traitSelf == traitSelf) {
                return cached.traceSite;
            }
            dev.phpruntime.runtime.PhpBacktrace.TraceSite fresh = new dev.phpruntime.runtime.PhpBacktrace.TraceSite(node, traitSelf);
            traitSiteCache = new TraitSiteCache(fresh, site, traitSelf);
            return fresh;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Calls a method given the usual {@code [this, calledClass, args...]} layout. Trait methods
     * also need the using class at index 2; whether the target is one is a compile-time
     * constant on the cached path, so ordinary method calls pay nothing for traits.
     */
    public Object callMethod(PhpClass.MethodEntry entry, Object[] args) {
        RootCallTarget target = entry.callTarget();
        DirectCallNode direct = directCall;
        if (direct != null && direct.getCallTarget() == target) {
            return direct.call(isTraitMethod(direct.getCallTarget()) ? withTraitSelf(args, entry.declaringClass()) : args);
        }
        if (indirectCall == null) {
            // First call or a second target: keep the trait check and argument copy below out
            // of compiled code until the site is polymorphic.
            CompilerDirectives.transferToInterpreterAndInvalidate();
        }
        return call(target, entry.fromTrait() ? withTraitSelf(args, entry.declaringClass()) : args);
    }

    private static boolean isTraitMethod(CallTarget target) {
        return dev.phpruntime.nodes.PhpFunctionRootNode.of(((RootCallTarget) target).getRootNode()) instanceof dev.phpruntime.nodes.PhpFunctionRootNode r
                && !r.isClosure() && r.traitSelfSlot() >= 0;
    }

    static Object[] withTraitSelf(Object[] args, PhpClass usingClass) {
        Object[] out = new Object[args.length + 1];
        out[0] = args[0];
        out[1] = args[1];
        out[2] = usingClass;
        System.arraycopy(args, 2, out, 3, args.length - 2);
        return out;
    }

    private Object callDynamic(RootCallTarget target, Object[] args) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        if (prepareCall(target)) {
            return directCall.call(args);
        }
        return indirectCall.call(target, args);
    }

    @TruffleBoundary
    private boolean prepareCall(RootCallTarget target) {
        java.util.concurrent.locks.Lock lock = getLock();
        lock.lock();
        try {
            if (directCall == null) {
                directCall = insert(DirectCallNode.create(target));
                return true;
            }
            if (directCall.getCallTarget() == target) {
                return true;
            }
            if (indirectCall == null) {
                indirectCall = insert(Truffle.getRuntime().createIndirectCallNode());
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

}
