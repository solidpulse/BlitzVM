package dev.phpruntime.nodes.var;

import dev.phpruntime.nodes.PhpNode;

/** {@code ++$x}, {@code $x++}, {@code --$x}, {@code $x--}. */
public final class IncDecNode extends PhpNode {

    @Child private LValueNode target;
    private final boolean increment;
    private final boolean prefix;

    public IncDecNode(LValueNode target, boolean increment, boolean prefix) {
        this.target = target;
        this.increment = increment;
        this.prefix = prefix;
    }

    /** Perl-style string increment: "a" becomes "b", "Az" becomes "Ba", "zz" becomes "aaa". */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public static String incrementString(String s) {
        if (s.isEmpty()) {
            return "1";
        }
        char[] c = s.toCharArray();
        int i = c.length - 1;
        while (i >= 0) {
            char ch = c[i];
            if (ch == 'z') {
                c[i] = 'a';
            } else if (ch == 'Z') {
                c[i] = 'A';
            } else if (ch == '9') {
                c[i] = '0';
            } else if (Character.isLetterOrDigit(ch)) {
                c[i]++;
                return new String(c);
            } else {
                return new String(c);
            }
            i--;
        }
        char first = s.charAt(0);
        String prefix = first == 'z' ? "a" : first == 'Z' ? "A" : "1";
        return prefix + new String(c);
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (dev.phpruntime.bytecode.BytecodeEmitter.isPlace(target)) {
            e.place(target, dev.phpruntime.bytecode.PhpBytecodeRootNode.P_INCDEC, (increment ? 1 : 0) | (prefix ? 2 : 0), null);
            return;
        }
        if (!(target instanceof LocalVarNode lv) || "this".equals(lv.varName())) {
            throw e.unsupported("increment of " + target.getClass().getSimpleName());
        }
        e.builder().emitIncDecVar(e.accessor(lv), e.info(lv), increment, prefix);
    }

    @Override
    public void emitStatement(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (dev.phpruntime.bytecode.BytecodeEmitter.isPlace(target)) {
            emit(e);
            return;
        }
        if (!(target instanceof LocalVarNode lv) || "this".equals(lv.varName())) {
            throw e.unsupported("increment of " + target.getClass().getSimpleName());
        }
        e.builder().emitStepVar(e.accessor(lv), e.info(lv), increment);
    }
}
