package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.PhpContext;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.runtime.CallMode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpBacktrace;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClosure;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;

import java.util.List;

/**
 * The per-call-site fast path for the array builtins that run a closure over an array
 * ({@code array_map}, {@code array_filter}, {@code array_reduce}).
 *
 * <p>The builtin itself calls a closure through {@code PhpCallable.invokeClosure*}, which goes
 * through an {@code IndirectCallNode} from host code. This node holds a {@link DispatchCallNode}
 * per site instead, so the callback's call target is cached and the JIT can inline the closure
 * body. The walk stays in guest frame wiring: the same closure frame arguments, the same
 * internal trace frame and call mode as {@code PhpCallable.invokeTarget}.
 *
 * <p>The internal frame is pushed once for the whole walk rather than once per element. Only
 * guest code can observe the trace stack or the pending call mode, and no guest code runs
 * between two callback invocations, so the frames during every callback are the same as
 * calling {@code invokeTarget} per element.
 *
 * <p>Every shape this node does not answer exactly (another callback kind, several arrays,
 * a non-array subject, an unusual filter mode, a sort subject that is not a reference to an
 * array) returns {@link #UNHANDLED}; the builtin then runs unchanged and keeps its
 * conversions, diagnostics and error traces.
 */
public final class ArrayCallbackNode extends Node {

    /** Marks a call this fast path does not answer, so the builtin's own implementation runs. */
    public static final Object UNHANDLED = new Object();

    private static final int MAP = 0;
    private static final int FILTER = 1;
    private static final int REDUCE = 2;

    private final int kind;
    private final Node site;
    /** The frame below each closure call, also allocated once per site. */
    private final PhpBacktrace.InternalSite internalSite;

    @Child private DispatchCallNode dispatch = new DispatchCallNode();

    private ArrayCallbackNode(int kind, String name, Node site) {
        this.kind = kind;
        this.site = site;
        this.internalSite = new PhpBacktrace.InternalSite(site, name);
    }

    /** The per-site node for a builtin name, or null when the site calls another builtin. */
    public static ArrayCallbackNode create(String callName, Node site) {
        if (callName == null) {
            return null;
        }
        String lower = dev.phpruntime.runtime.PhpStrings.lower(callName);
        // The callback sorts stay in the builtin: running the sort itself in compiled guest code
        // inlines a whole sort into every usort site for a small gain per comparison.
        return switch (lower) {
            case "array_map" -> new ArrayCallbackNode(MAP, lower, site);
            case "array_filter" -> new ArrayCallbackNode(FILTER, lower, site);
            case "array_reduce" -> new ArrayCallbackNode(REDUCE, lower, site);
            default -> null;
        };
    }

    /** The builtin's answer, or {@link #UNHANDLED} when the builtin must run it. */
    public Object execute(Builtins.Def def, Object[] args) {
        if (dev.phpruntime.runtime.PhpStrings.CHECK) {
            // The byte-string assertions and the builtin's argument checks run in def.call.
            return UNHANDLED;
        }
        return switch (kind) {
            case MAP -> map(args);
            case FILTER -> filter(def, args);
            default -> reduce(def, args);
        };
    }

    private Object map(Object[] args) {
        // Several arrays and a null callback keep the builtin's own row-building path.
        if (args.length != 2 || !(args[1] instanceof PhpArray src)) {
            return UNHANDLED;
        }
        PhpObject closureObj = closureObject(args[0]);
        if (closureObj == null) {
            return UNHANDLED;
        }
        PhpClosure closure = closureObj.closure();
        PhpFunctionRootNode fnRoot = PhpCallable.rootOf(closure.callTarget());
        RootCallTarget target = closure.callTarget();
        Object boundThis = closure.boundThis();
        PhpClass calledClass = closure.calledClass();
        boolean list = src.isList();
        PhpArray out = PhpArray.withCapacity(src.size(), !list);
        PhpContext ctx = PhpContext.get(this);
        Node previousSite = ctx.currentBuiltinCallSite();
        String previousName = ctx.enterBuiltin(site, "array_map");
        CallbackFrame callback = beginCallback(ctx, fnRoot);
        try {
            PhpArray view = src.walkView();
            try {
                for (int pos = view.firstPos(); pos >= 0; pos = view.nextPos(pos)) {
                    Object mapped = call(ctx, target, fnRoot, boundThis, calledClass, closureObj,
                            view.valueAt(pos), null, false, null);
                    if (list) {
                        out.appendReserved(mapped);
                    } else {
                        out.put(view.keyAt(pos), mapped);
                    }
                }
            } finally {
                view.releaseShare();
            }
        } finally {
            endCallback(ctx, callback);
            ctx.exitBuiltin(previousSite, previousName);
        }
        return out;
    }

    private Object filter(Builtins.Def def, Object[] args) {
        if ((args.length != 2 && args.length != 3) || !(args[0] instanceof PhpArray src)) {
            return UNHANDLED;
        }
        PhpObject closureObj = closureObject(args[1]);
        if (closureObj == null) {
            return UNHANDLED;
        }
        long mode;
        if (args.length == 3) {
            if (!(args[2] instanceof Long requested) || requested < 0 || requested > 2) {
                return UNHANDLED;
            }
            mode = requested;
        } else {
            mode = 0;
        }
        PhpClosure closure = closureObj.closure();
        PhpFunctionRootNode fnRoot = PhpCallable.rootOf(closure.callTarget());
        RootCallTarget target = closure.callTarget();
        Object boundThis = closure.boundThis();
        PhpClass calledClass = closure.calledClass();
        boolean list = src.isList();
        PhpArray out = PhpArray.withCapacity(src.size(), !list);
        int kept = 0;
        PhpContext ctx = PhpContext.get(this);
        Node previousSite = ctx.currentBuiltinCallSite();
        String previousName = ctx.enterBuiltin(site, def.name());
        CallbackFrame callback = beginCallback(ctx, fnRoot);
        try {
            PhpArray view = src.walkView();
            try {
                for (int pos = view.firstPos(); pos >= 0; pos = view.nextPos(pos)) {
                    Object key = view.keyAt(pos);
                    Object raw = view.rawValueAt(pos);
                    Object value = raw instanceof PhpReference ref ? ref.getValue() : raw;
                    Object first;
                    Object second = null;
                    boolean twoArgs;
                    if (mode == 2) {
                        first = key;
                        twoArgs = false;
                    } else if (mode == 1) {
                        first = value;
                        second = key;
                        twoArgs = true;
                    } else {
                        first = value;
                        twoArgs = false;
                    }
                    Object answer = call(ctx, target, fnRoot, boundThis, calledClass, closureObj,
                            first, second, twoArgs, null);
                    if (PhpOps.toBool(answer)) {
                        // php copies the zval, so a reference cell stays a reference in the result.
                        Object copy = PhpOps.copyOnAssign(raw);
                        if (list) {
                            if (key instanceof Long index && index == kept) {
                                out.appendReserved(copy);
                            } else {
                                list = false;
                                out.put(key, copy);
                            }
                        } else {
                            out.put(key, copy);
                        }
                        kept++;
                    }
                }
            } finally {
                view.releaseShare();
            }
        } finally {
            endCallback(ctx, callback);
            ctx.exitBuiltin(previousSite, previousName);
        }
        return out;
    }

    private Object reduce(Builtins.Def def, Object[] args) {
        if ((args.length != 2 && args.length != 3) || !(args[0] instanceof PhpArray src)) {
            return UNHANDLED;
        }
        PhpObject closureObj = closureObject(args[1]);
        if (closureObj == null) {
            return UNHANDLED;
        }
        PhpClosure closure = closureObj.closure();
        PhpFunctionRootNode fnRoot = PhpCallable.rootOf(closure.callTarget());
        RootCallTarget target = closure.callTarget();
        Object boundThis = closure.boundThis();
        PhpClass calledClass = closure.calledClass();
        Object acc = args.length > 2 ? PhpOps.copyOnAssign(args[2]) : PhpNull.INSTANCE;
        PhpContext ctx = PhpContext.get(this);
        Node previousSite = ctx.currentBuiltinCallSite();
        String previousName = ctx.enterBuiltin(site, def.name());
        CallbackFrame callback = beginCallback(ctx, fnRoot);
        try {
            PhpArray view = src.walkView();
            try {
                for (int pos = view.firstPos(); pos >= 0; pos = view.nextPos(pos)) {
                    acc = call(ctx, target, fnRoot, boundThis, calledClass, closureObj,
                            acc, view.valueAt(pos), true, null);
                }
            } finally {
                view.releaseShare();
            }
        } finally {
            endCallback(ctx, callback);
            ctx.exitBuiltin(previousSite, previousName);
        }
        return acc;
    }

    /** The closure object a callback value denotes, or null when the builtin must resolve it. */
    private static PhpObject closureObject(Object value) {
        if (value instanceof PhpObject obj && obj.closure() != null
                && obj.closure().kind() == PhpClosure.Kind.CLOSURE) {
            return obj;
        }
        return null;
    }

    /**
     * Installs the trace frame and weak call mode an internal callback runs under, and returns
     * what {@link #endCallback} needs to restore. The frame cache is legitimate here: the site
     * is fixed, and the builtin state entered around this walk makes it the current one.
     */
    private CallbackFrame beginCallback(PhpContext ctx, PhpFunctionRootNode fnRoot) {
        CallMode previous = ctx.pendingCallMode();
        Object traceSite = previous == null ? internalSite : site;
        if (previous == null) {
            ctx.setPendingCallMode(CallMode.internal(fnRoot));
        }
        int depth = ctx.pushTraceFrame(traceSite);
        return new CallbackFrame(previous, depth);
    }

    private void endCallback(PhpContext ctx, CallbackFrame frame) {
        ctx.popTraceFrame(frame.depth());
        ctx.setPendingCallMode(frame.previous());
    }

    private record CallbackFrame(CallMode previous, int depth) {
    }

    /**
     * Calls a closure callback exactly as {@code PhpCallable.invokeClosure1/2} does, but through
     * this site's {@link DispatchCallNode}. Only the frame arguments and the call itself remain
     * per element.
     */
    private Object call(PhpContext ctx, RootCallTarget target, PhpFunctionRootNode fnRoot,
                        Object boundThis, PhpClass calledClass, PhpObject closureObj,
                        Object first, Object second, boolean twoArgs, Node loc) {
        Object[] frameArgs;
        if (twoArgs) {
            frameArgs = new Object[5];
            frameArgs[3] = PhpCallable.adaptUserArg(fnRoot, 0, first, ctx, loc);
            frameArgs[4] = PhpCallable.adaptUserArg(fnRoot, 1, second, ctx, loc);
        } else {
            frameArgs = new Object[4];
            frameArgs[3] = PhpCallable.adaptUserArg(fnRoot, 0, first, ctx, loc);
        }
        frameArgs[0] = boundThis;
        frameArgs[1] = calledClass;
        frameArgs[2] = closureObj;
        return CallArgHelper.unwrapReturn(dispatch.call(target, frameArgs));
    }
}
