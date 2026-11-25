package dev.phpruntime.runtime;

import com.oracle.truffle.api.RootCallTarget;

/**
 * A user-defined PHP function: its name and the call target of its root node. Created once
 * per parse and shared by every request that declares it, so the lowercase name, the
 * language-wide function id and the builtin-clash check are computed once here instead of on
 * each request's declaration (WordPress declares about 3,000 functions per request).
 */
public final class PhpFunction {
    private final String name;
    private final RootCallTarget callTarget;
    private final String lowerName;
    /**
     * The guest function's metadata, computed once: a spread or named call needs the parameter
     * names and reference mask, and walking from the call target to its root at the call site
     * would put the root's type checks in every such call.
     */
    private final dev.phpruntime.nodes.PhpFunctionRootNode signature;
    private int id = -1;
    private byte builtinClash = -1;

    public PhpFunction(String name, RootCallTarget callTarget) {
        this.name = name;
        this.callTarget = callTarget;
        this.lowerName = PhpStrings.lower(name);
        this.signature = dev.phpruntime.nodes.PhpFunctionRootNode.of(callTarget.getRootNode());
    }

    public String name() { return name; }

    public RootCallTarget callTarget() { return callTarget; }

    /** The guest function this call target runs, computed when the function was made. */
    public dev.phpruntime.nodes.PhpFunctionRootNode signature() { return signature; }

    public String lowerName() { return lowerName; }

    /** The {@link dev.phpruntime.PhpLanguage#functionId} of this name; computed once. */
    public int id(dev.phpruntime.PhpLanguage language) {
        int i = id;
        if (i < 0) {
            i = language.functionId(lowerName);
            id = i;
        }
        return i;
    }

    /** Whether a builtin has this name (builtins are fixed at startup). */
    public boolean clashesWithBuiltin() {
        byte b = builtinClash;
        if (b < 0) {
            b = (byte) (dev.phpruntime.builtins.Builtins.lookup(name) != null ? 1 : 0);
            builtinClash = b;
        }
        return b == 1;
    }

    @Override
    public String toString() {
        return "PhpFunction[" + name + "]";
    }
}
