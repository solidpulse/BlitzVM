package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.var.LValueNode;
import dev.phpruntime.runtime.PhpError;

/** {@code name(args...)}. */
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

    @Child private SplBuiltinNode spl;

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

    public CallNode withSpl(dev.phpruntime.PhpLanguage language) {
        if (SplBuiltinNode.supports(name.toLowerCase())) {
            spl = new SplBuiltinNode(language, name.toLowerCase());
        }
        return this;
    }

    /** The AST is shared by concurrent requests, so node specialization takes the AST lock. */
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

    /** Exploded so each argument and ref-target node is a PE constant: no virtual calls, no frame escape. */
    public String functionName() {
        return name;
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

    /**
     * A namespaced {@code assert(...)}: a function declared in that namespace wins, so resolve
     * first; when it is the builtin, return true without evaluating the arguments.
     */
    private void emitNamespacedAssert(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        boolean keepRef = false;
        dev.phpruntime.bytecode.PhpBytecodeRootNode.CallSite site =
                new dev.phpruntime.bytecode.PhpBytecodeRootNode.CallSite(name, functionId, nsName, nsFunctionId, null);
        com.oracle.truffle.api.bytecode.BytecodeLocal callee = e.enterCall();
        try {
            b.beginBlock();
            b.emitResolveFunction(site, callee);
            com.oracle.truffle.api.bytecode.BytecodeLocal result = e.temp();
            b.beginStoreLocal(result);
            b.emitLoadConstant(Boolean.TRUE);
            b.endStoreLocal();
            b.beginIfThen();
            b.beginNot();
            b.beginIsBuiltinNamed("assert");
            b.emitLoadLocal(callee);
            b.endIsBuiltinNamed();
            b.endNot();
            b.beginBlock();
            b.beginStoreLocal(result);
            if (hasSpreadOrNamed) {
                com.oracle.truffle.api.bytecode.BytecodeLocal raw = e.temp();
                e.beginRawArgs(raw);
                e.complexArgs(args, refTargets, argNames, spreads, callee, raw);
                b.beginDoComplexFunction(keepRef);
                b.emitLoadLocal(callee);
                b.emitLoadLocal(raw);
                b.endDoComplexFunction();
            } else {
                b.beginDoCall(site.withKeepRef(keepRef), callee);
                for (int i = 0; i < args.length; i++) {
                    e.sendArgument(args[i], refTargets != null ? refTargets[i] : null, i, callee);
                }
                b.endDoCall();
            }
            b.endStoreLocal();
            b.endBlock();
            b.endIfThen();
            b.emitLoadLocal(result);
            b.endBlock();
        } finally {
            e.exitCall();
        }
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        var b = e.builder();
        boolean keepRef = e.takeRef(this);
        Builtins.Def def = Builtins.lookup(name);
        if (def != null && def.name().equals("assert")) {
            // zend.assertions is -1: assert() compiles to true and evaluates nothing.
            if (nsName == null) {
                b.emitLoadConstant(Boolean.TRUE);
                return;
            }
            emitNamespacedAssert(e);
            return;
        }
        if (def != null) {
            if (nsName == null && !hasSpreadOrNamed) {
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
        }
        dev.phpruntime.bytecode.PhpBytecodeRootNode.CallSite site =
                new dev.phpruntime.bytecode.PhpBytecodeRootNode.CallSite(name, functionId, nsName, nsFunctionId,
                        spl != null ? name.toLowerCase() : null);
        com.oracle.truffle.api.bytecode.BytecodeLocal callee = e.enterCall();
        try {
            b.beginBlock();
            b.emitResolveFunction(site, callee);
            if (hasSpreadOrNamed) {
                com.oracle.truffle.api.bytecode.BytecodeLocal raw = e.temp();
                e.beginRawArgs(raw);
                e.complexArgs(args, refTargets, argNames, spreads, callee, raw);
                b.beginDoComplexFunction(keepRef);
                b.emitLoadLocal(callee);
                b.emitLoadLocal(raw);
                b.endDoComplexFunction();
            } else {
                b.beginDoCall(site.withKeepRef(keepRef), callee);
                for (int i = 0; i < args.length; i++) {
                    e.sendArgument(args[i], refTargets != null ? refTargets[i] : null, i, callee);
                }
                b.endDoCall();
            }
            b.endBlock();
        } finally {
            e.exitCall();
        }
    }
}
