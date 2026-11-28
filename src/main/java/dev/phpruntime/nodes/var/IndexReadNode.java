package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpObject;

/** Read-only {@code expr[key]} on an arbitrary expression, e.g. {@code f()[0]}. */
public final class IndexReadNode extends PhpNode {

    /**
     * One-character strings for the first 256 code points, so {@code $s[$i]} does not allocate
     * the result. Strings are immutable, so sharing one instance is invisible to the guest.
     */
    private static final String[] SINGLE_CHARS = singleChars();

    private static String[] singleChars() {
        String[] table = new String[256];
        for (int i = 0; i < table.length; i++) {
            table[i] = String.valueOf((char) i);
        }
        return table;
    }

    @Child private PhpNode target;
    @Child private PhpNode key;

    public IndexReadNode(PhpNode target, PhpNode key) {
        this.target = target;
        this.key = key;
    }

    public PhpNode target() {
        return target;
    }

    public PhpNode key() {
        return key;
    }

    /** Element of an array or character of a string; null when absent. */
    @TruffleBoundary
    public static Object index(Object container, Object k) {
        if (container instanceof PhpArray a) {
            return a.get(k);
        }
        if (container instanceof String s) {
            long i;
            if (k instanceof Long l) {
                i = l;
            } else if (k instanceof String ks) {
                Object norm = PhpArray.normalizeKey(ks);
                if (!(norm instanceof Long l)) {
                    return null;
                }
                i = l;
            } else if (k instanceof Boolean b) {
                i = b ? 1L : 0L;
            } else if (k instanceof Double d) {
                i = (long) d.doubleValue();
            } else {
                return null;
            }
            if (i < 0) {
                i += s.length();
            }
            if (i < 0 || i >= s.length()) {
                return null;
            }
            char c = s.charAt((int) i);
            return c < 256 ? SINGLE_CHARS[c] : String.valueOf(c);
        }
        if (container instanceof PhpObject obj) {
            throw new dev.phpruntime.runtime.PhpError("Cannot use object of type " + obj.phpClass().name() + " as array");
        }
        return null;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (key == null) {
            throw e.unsupported("read of []");
        }
        e.builder().beginIndex();
        e.expr(target);
        e.expr(key);
        e.builder().endIndex();
    }

    /** The silent read used by isset, ?? and nested index reads under them. */
    public void emitIfSet(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (key == null) {
            throw e.unsupported("read of []");
        }
        e.builder().beginIndexIfSet();
        e.ifSet(target);
        e.expr(key);
        e.builder().endIndexIfSet();
    }

    public void emitIsset(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (key == null) {
            throw e.unsupported("isset of []");
        }
        e.builder().beginIssetIndex();
        e.ifSet(target);
        e.expr(key);
        e.builder().endIssetIndex();
    }
}
