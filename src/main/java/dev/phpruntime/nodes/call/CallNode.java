package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import dev.phpruntime.PhpContext;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpFunction;
import dev.phpruntime.runtime.PhpOps;

/**
 * {@code name(args...)}. Resolved on first execution: a user function becomes a
 * DirectCallNode (which Graal can inline), a builtin is called directly. The resolved
 * target is cached in the node, like a monomorphic inline cache.
 */
public final class CallNode extends PhpNode {

    private final String nsName;
    private final int nsFunctionId;
    private final String name;
    /** {@link dev.phpruntime.PhpLanguage#functionId} of {@link #name}. */
    private final int functionId;
    @Children private final PhpNode[] args;
    /** For each argument, the lvalue it came from (for by-reference params), else null. */
    @Children private final LValueNode[] refTargets;
    @CompilationFinal(dimensions = 1) private final String[] argNames;
    @CompilationFinal(dimensions = 1) private final boolean[] spreads;
    private final boolean hasSpreadOrNamed;

    @Child private DirectCallNode callNode;
    @Child private IndirectCallNode indirectCall;
    @CompilationFinal private Builtins.Def builtin;
    @Child private SplBuiltinNode spl;
    /** A builtin compiled inline, like php's zend_try_compile_special_func; see {@link #intrinsicFor}. */
    @CompilationFinal private int intrinsic = NO_INTRINSIC;

    private static final int NO_INTRINSIC = 0;
    private static final int IS_ARRAY = 1;
    private static final int IS_STRING = 2;
    private static final int IS_INT = 3;
    private static final int IS_FLOAT = 4;
    private static final int IS_BOOL = 5;
    private static final int IS_NULL = 6;
    private static final int IS_OBJECT = 7;
    private static final int STRLEN = 8;
    private static final int ARRAY_KEY_EXISTS = 9;

    public CallNode(String name, int functionId, PhpNode[] args, LValueNode[] refTargets) {
        this(null, -1, name, functionId, args, refTargets, null, null);
    }

    public CallNode(String nsName, int nsFunctionId, String name, int functionId,
                    PhpNode[] args, LValueNode[] refTargets) {
        this(nsName, nsFunctionId, name, functionId, args, refTargets, null, null);
    }

    public CallNode(String name, int functionId, PhpNode[] args, LValueNode[] refTargets,
                    String[] argNames, boolean[] spreads) {
        this(null, -1, name, functionId, args, refTargets, argNames, spreads);
    }

    /** {@code nsName}/{@code nsFunctionId}: the namespaced name tried first (php's fallback), or null/-1. */
    public CallNode(String nsName, int nsFunctionId, String name, int functionId, PhpNode[] args,
                    LValueNode[] refTargets, String[] argNames, boolean[] spreads) {
        this.nsName = nsName;
        this.nsFunctionId = nsFunctionId;
        this.name = name;
        this.functionId = functionId;
        this.args = args;
        this.refTargets = refTargets;
        this.argNames = argNames;
        this.spreads = spreads;
        boolean complex = false;
        if (argNames != null) {
            for (String s : argNames) {
                if (s != null) {
                    complex = true;
                    break;
                }
            }
        }
        if (!complex && spreads != null) {
            for (boolean b : spreads) {
                if (b) {
                    complex = true;
                    break;
                }
            }
        }
        this.hasSpreadOrNamed = complex;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return CallArgHelper.unwrapReturn(executeRaw(frame));
    }

    public CallNode withSpl(dev.phpruntime.PhpLanguage language) {
        if (SplBuiltinNode.supports(name.toLowerCase())) {
            spl = new SplBuiltinNode(language, name.toLowerCase());
        }
        return this;
    }

    @Override
    public Object executeForRef(VirtualFrame frame) {
        return executeRaw(frame);
    }

    private Object executeRaw(VirtualFrame frame) {
        if (nsFunctionId >= 0) {
            PhpFunction nsFn = PhpContext.get(this).functionById(nsFunctionId);
            if (nsFn != null) {
                return callUserFunction(frame, nsFn);
            }
        }
        if (builtin != null) {
            return callBuiltin(frame);
        }
        PhpFunction fn = PhpContext.get(this).functionById(functionId);
        if (fn == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            Builtins.Def def = resolveBuiltin();
            if (def != null) {
                return callBuiltin(frame);
            }
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Call to undefined function " + (nsName != null ? nsName : name) + "()", this);
        }
        return callUserFunction(frame, fn);
    }

    private Object callUserFunction(VirtualFrame frame, PhpFunction fn) {
        RootCallTarget target = fn.callTarget();
        DirectCallNode direct = callNode;
        if (direct != null && direct.getCallTarget() == target) {
            // Pass the cached target so partial evaluation sees fnRoot and hasAnyRefParam() as constants.
            return direct.call(evalUserArgs(frame, (RootCallTarget) direct.getCallTarget()));
        }
        IndirectCallNode indirect = indirectCall;
        if (indirect != null) {
            return indirect.call(target, evalUserArgs(frame, target));
        }
        return callDynamic(frame, fn);
    }

    /** The AST is shared by concurrent requests, so node specialization takes the AST lock. */
    @TruffleBoundary
    private Builtins.Def resolveBuiltin() {
        java.util.concurrent.locks.Lock lock = getLock();
        lock.lock();
        try {
            if (builtin == null) {
                Builtins.Def def = Builtins.lookup(name);
                if (def != null && !hasSpreadOrNamed && spl == null) {
                    intrinsic = intrinsicFor(def.name(), args.length);
                }
                builtin = def;
            }
            return builtin;
        } finally {
            lock.unlock();
        }
    }

    private Object callDynamic(VirtualFrame frame, PhpFunction fn) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        RootCallTarget target = fn.callTarget();
        if (prepareCall(target)) {
            return callNode.call(evalUserArgs(frame, target));
        }
        return indirectCall.call(target, evalUserArgs(frame, target));
    }

    @TruffleBoundary
    private boolean prepareCall(RootCallTarget target) {
        java.util.concurrent.locks.Lock lock = getLock();
        lock.lock();
        try {
            if (callNode == null) {
                callNode = insert(DirectCallNode.create(target));
                return true;
            }
            if (callNode.getCallTarget() == target) {
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

    private Object[] evalUserArgs(VirtualFrame frame, RootCallTarget target) {
        if (hasSpreadOrNamed) {
            return CallArgHelper.evalComplexUserArgs(frame, target, new Object[0], args, refTargets, argNames, spreads, this);
        }
        dev.phpruntime.nodes.PhpFunctionRootNode fnRoot = dev.phpruntime.nodes.PhpFunctionRootNode.of(target.getRootNode()) instanceof dev.phpruntime.nodes.PhpFunctionRootNode r ? r : null;
        if (fnRoot != null && fnRoot.hasAnyRefParam()) {
            return evalArgsWithRefs(frame, fnRoot);
        }
        return evalArgs(frame);
    }

    @ExplodeLoop
    private Object[] evalArgsWithRefs(VirtualFrame frame, dev.phpruntime.nodes.PhpFunctionRootNode fnRoot) {
        Object[] values = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            values[i] = CallArgHelper.evalArgForParam(frame, args[i], refTargets[i], fnRoot, i, this);
        }
        return values;
    }

    @ExplodeLoop
    private Object[] evalArgs(VirtualFrame frame) {
        Object[] values = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            values[i] = PhpOps.copyOnAssign(args[i].execute(frame));
        }
        return values;
    }

    /**
     * Type checks, strlen and array_key_exists with the exact argument count run inline: a
     * generic builtin call evaluates into an argument array and crosses a boundary, about 1 KB
     * of machine code each, and these are among the most called functions in WordPress. Any
     * other argument type takes the generic path, which owns coercion and errors.
     */
    private static int intrinsicFor(String name, int argc) {
        if (argc == 1) {
            return switch (name) {
                case "is_array" -> IS_ARRAY;
                case "is_string" -> IS_STRING;
                case "is_int", "is_integer", "is_long" -> IS_INT;
                case "is_float", "is_double" -> IS_FLOAT;
                case "is_bool" -> IS_BOOL;
                case "is_null" -> IS_NULL;
                case "is_object" -> IS_OBJECT;
                case "strlen" -> STRLEN;
                default -> NO_INTRINSIC;
            };
        }
        return argc == 2 && name.equals("array_key_exists") ? ARRAY_KEY_EXISTS : NO_INTRINSIC;
    }

    private Object callIntrinsic(VirtualFrame frame, int kind) {
        Object a = args[0].execute(frame);
        switch (kind) {
            case IS_ARRAY: return a instanceof dev.phpruntime.runtime.PhpArray;
            case IS_STRING: return a instanceof String;
            case IS_INT: return a instanceof Long;
            case IS_FLOAT: return a instanceof Double;
            case IS_BOOL: return a instanceof Boolean;
            case IS_NULL: return a == dev.phpruntime.runtime.PhpNull.INSTANCE;
            case IS_OBJECT: return a instanceof dev.phpruntime.runtime.PhpObject;
            case STRLEN:
                if (a instanceof String str) {
                    return (long) str.length();
                }
                return invoke(builtin, new Object[]{a});
            case ARRAY_KEY_EXISTS: {
                Object b = args[1].execute(frame);
                if (b instanceof dev.phpruntime.runtime.PhpArray arr) {
                    return arr.containsKey(a);
                }
                return invoke(builtin, new Object[]{a, b});
            }
            default:
                throw CompilerDirectives.shouldNotReachHere();
        }
    }

    /** Exploded so each argument and ref-target node is a PE constant: no virtual calls, no frame escape. */
    @ExplodeLoop
    private Object callBuiltin(VirtualFrame frame) {
        int kind = intrinsic;
        if (kind != NO_INTRINSIC) {
            return callIntrinsic(frame, kind);
        }
        Builtins.Def def = builtin;
        // zend.assertions is -1 in this build: assert() compiles to nothing, not even
        // evaluating its arguments. The parser is shared with other work, so skip here.
        if (def.name().equals("assert")) {
            return true;
        }
        if (hasSpreadOrNamed) {
            return CallArgHelper.callComplexBuiltin(frame, def, args, refTargets, argNames, spreads, this);
        }
        Object[] values = new Object[args.length];
        Object[][] refKeys = new Object[args.length][];
        for (int i = 0; i < args.length; i++) {
            if (def.isRef(i)) {
                if (refTargets[i] != null) {
                    if (refTargets[i] instanceof dev.phpruntime.nodes.var.GlobalVarNode gv && gv.isGlobalsSuperglobal()) {
                        CompilerDirectives.transferToInterpreter();
                        throw new PhpError(def.name() + "(): Argument #" + (i + 1) + " could not be passed by reference", this);
                    }
                    refKeys[i] = refTargets[i].evalKeys(frame);
                    Object v = refTargets[i].read(frame, refKeys[i]);
                    values[i] = v == null ? dev.phpruntime.runtime.PhpNull.INSTANCE : v;
                } else {
                    Object res = args[i].executeForRef(frame);
                    if (res instanceof dev.phpruntime.runtime.PhpReference ref) {
                        values[i] = ref.getValue() == null ? dev.phpruntime.runtime.PhpNull.INSTANCE : ref.getValue();
                    } else if (args[i] instanceof CallNode || args[i] instanceof MethodCallNode
                            || args[i] instanceof StaticMethodCallNode || args[i] instanceof CallValueNode) {
                        PhpContext.get(this).notice("Only variables should be passed by reference", line());
                        values[i] = res;
                    } else {
                        CompilerDirectives.transferToInterpreter();
                        throw new PhpError(def.name() + "(): Argument #" + (i + 1) + " could not be passed by reference", this);
                    }
                }
            } else {
                values[i] = args[i].execute(frame);
            }
        }
        checkArity(def, values.length);
        Object result = spl != null ? spl.execute(values) : invoke(def, values);
        for (int i = 0; i < args.length; i++) {
            if (refKeys[i] != null) {
                refTargets[i].write(frame, refKeys[i], values[i]);
            }
        }
        return result;
    }

    public String functionName() {
        return name;
    }

    @TruffleBoundary
    private Object invoke(Builtins.Def def, Object[] values) {
        PhpContext ctx = PhpContext.get(this);
        com.oracle.truffle.api.nodes.Node prev = ctx.currentBuiltinCallSite();
        ctx.setCurrentBuiltinCallSite(this);
        try {
            return def.call(ctx, values);
        } catch (PhpError e) {
            throw e.withTrace(dev.phpruntime.runtime.PhpBacktrace.captureFromBuiltin(this, def.name())).at(this);
        } finally {
            ctx.setCurrentBuiltinCallSite(prev);
        }
    }

    private void checkArity(Builtins.Def def, int n) {
        checkArity(def, n, this);
    }

    public static void checkArity(Builtins.Def def, int n, com.oracle.truffle.api.nodes.Node location) {
        if (n < def.minArgs() || (def.maxArgs() >= 0 && n > def.maxArgs())) {
            CompilerDirectives.transferToInterpreter();
            PhpError err = new PhpError(PhpError.ARGUMENT_COUNT, def.name() + "() expects " + arity(def, n) + ", " + n + " given", location);
            if (location != null) {
                err.withTrace(dev.phpruntime.runtime.PhpBacktrace.captureFromBuiltin(location, def.name()));
            }
            throw err;
        }
    }

    private static String arity(Builtins.Def def, int n) {
        String quantifier = def.minArgs() == def.maxArgs() ? "exactly" : (n < def.minArgs() ? "at least" : "at most");
        int count = n < def.minArgs() ? def.minArgs() : def.maxArgs();
        return quantifier + " " + count + " argument" + (count == 1 ? "" : "s");
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (hasSpreadOrNamed) {
            throw e.unsupported("call with spread or named arguments");
        }
        var b = e.builder();
        Builtins.Def def = Builtins.lookup(name);
        if (def != null) {
            if (nsName == null) {
                int kind = intrinsicFor(def.name(), args.length);
                if (kind == STRLEN) {
                    b.beginStrlen(def);
                    e.expr(args[0]);
                    b.endStrlen();
                    return;
                }
                if (kind == ARRAY_KEY_EXISTS) {
                    b.beginKeyExists(def);
                    e.expr(args[0]);
                    e.expr(args[1]);
                    b.endKeyExists();
                    return;
                }
                if (kind != NO_INTRINSIC) {
                    b.beginTypeTest(kind);
                    e.expr(args[0]);
                    b.endTypeTest();
                    return;
                }
            }
            if (def.name().equals("assert")) {
                throw e.unsupported("assert");
            }
        }
        dev.phpruntime.bytecode.PhpBytecodeRootNode.CallSite site =
                new dev.phpruntime.bytecode.PhpBytecodeRootNode.CallSite(name, functionId, nsName, nsFunctionId,
                        spl != null ? name.toLowerCase() : null);
        com.oracle.truffle.api.bytecode.BytecodeLocal callee = e.enterCall();
        try {
            b.beginBlock();
            b.emitResolveFunction(site, callee);
            b.beginDoCall(site, callee);
            for (int i = 0; i < args.length; i++) {
                e.sendArgument(args[i], refTargets != null ? refTargets[i] : null, i, callee);
            }
            b.endDoCall();
            b.endBlock();
        } finally {
            e.exitCall();
        }
    }
}
