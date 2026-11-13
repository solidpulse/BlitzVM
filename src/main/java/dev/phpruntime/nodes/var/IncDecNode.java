package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArith;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

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

    @Override
    public Object execute(VirtualFrame frame) {
        if (target instanceof LocalVarNode local) {
            // Unboxed fast paths for a number held in a variable.
            if (local.holdsLong(frame)) {
                long l = local.getLong(frame);
                if (l != Long.MAX_VALUE && l != Long.MIN_VALUE) {
                    long u = increment ? l + 1 : l - 1;
                    local.writeLong(frame, LValueNode.NO_KEYS, u);
                    return prefix ? u : l;
                }
            } else if (local.holdsDouble(frame)) {
                double d = local.getDouble(frame);
                double u = increment ? d + 1 : d - 1;
                local.writeDouble(frame, LValueNode.NO_KEYS, u);
                return prefix ? u : d;
            }
        }
        Object[] keys = target.evalKeys(frame);
        Object old = target.read(frame, keys);
        Object updated;
        if (old instanceof Long l && l != Long.MAX_VALUE && l != Long.MIN_VALUE) {
            updated = increment ? l + 1 : l - 1;
        } else {
            updated = slow(old);
        }
        target.write(frame, keys, updated);
        return prefix ? updated : (old == null ? PhpNull.INSTANCE : old);
    }

    @TruffleBoundary
    private Object slow(Object old) {
        if (old == null || old == PhpNull.INSTANCE) {
            // PHP: null++ is 1, null-- stays null.
            return increment ? (Object) 1L : PhpNull.INSTANCE;
        }
        if (old instanceof String s && !PhpOps.isNumericString(s)) {
            return increment ? incrementString(s) : s;
        }
        return increment ? PhpArith.add(old, 1L) : PhpArith.sub(old, 1L);
    }

    /** Perl-style string increment: "a" becomes "b", "Az" becomes "Ba", "zz" becomes "aaa". */
    private static String incrementString(String s) {
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
}
