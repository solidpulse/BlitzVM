package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpObject;

/** Read-only {@code expr[key]} on an arbitrary expression, e.g. {@code f()[0]}. */
public final class IndexReadNode extends PhpNode {

    @Child private PhpNode target;
    @Child private PhpNode key;
    @Child private ArrayAccessNode access;

    public IndexReadNode(PhpNode target, PhpNode key) {
        this.target = target;
        this.key = key;
    }

    public IndexReadNode(PhpLanguage language, PhpNode target, PhpNode key) {
        this(target, key);
        this.access = new ArrayAccessNode(language);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object t = target.execute(frame);
        Object k = key.execute(frame);
        Object v;
        try {
            v = t instanceof PhpObject obj ? access.get(obj, k) : index(t, k);
        } catch (dev.phpruntime.runtime.PhpError e) {
            throw e.at(this);
        }
        if (v == null) {
            warn(k);
            return PhpNull.INSTANCE;
        }
        return v;
    }

    /** Silent lookup for {@code isset()} / {@code empty()} / {@code ??}; returns null if absent. */
    public Object readIfSet(VirtualFrame frame) {
        Object t = target instanceof IndexReadNode ir ? ir.readIfSet(frame) : target.execute(frame);
        Object k = key.execute(frame);
        if (t == null || t == PhpNull.INSTANCE) {
            return null;
        }
        try {
            if (t instanceof PhpObject obj) {
                return access.exists(obj, k) ? access.get(obj, k) : null;
            }
            return index(t, k);
        } catch (dev.phpruntime.runtime.PhpError e) {
            throw e.at(this);
        }
    }

    public boolean isSet(VirtualFrame frame) {
        Object t = target instanceof IndexReadNode ir ? ir.readIfSet(frame) : target.execute(frame);
        Object k = key.execute(frame);
        if (t instanceof PhpObject obj) {
            return access.isSet(obj, k);
        }
        Object value = index(t, k);
        return value != null && value != PhpNull.INSTANCE;
    }

    @TruffleBoundary
    private void warn(Object k) {
        PhpContext.get(this).warning("Undefined array key " + PhpOps.toStr(k), line());
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
            return String.valueOf(s.charAt((int) i));
        }
        if (container instanceof PhpObject obj) {
            throw new dev.phpruntime.runtime.PhpError("Cannot use object of type " + obj.phpClass().name() + " as array");
        }
        return null;
    }
}
