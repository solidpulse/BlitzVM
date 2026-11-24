package dev.phpruntime.runtime;

import com.oracle.truffle.api.object.DynamicObject;

import java.lang.invoke.MethodHandles;

/** A PHP object instance backed by Truffle's DynamicObject for fast inline-cached property access. */
public final class PhpObject extends DynamicObject {

    public static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    @DynamicField private long l0;
    @DynamicField private long l1;
    @DynamicField private long l2;
    @DynamicField private long l3;
    @DynamicField private Object o0;
    @DynamicField private Object o1;
    @DynamicField private Object o2;
    @DynamicField private Object o3;

    private final PhpClass phpClass;
    private final long id;
    /** Set once at creation; the snapshot copier fills it in after the object exists (cycles). */
    private PhpClosure closure;
    private boolean cloning;
    /**
     * php's property guards: the magic methods currently running for a property
     * (zend_get_property_guard). A stack of the active entries, newest last; it is almost
     * always empty, so a read is a null check and an enter is two array stores.
     */
    private MagicGuardStack magicGuards;

    private static final class MagicGuardStack {
        String[] names = new String[4];
        int[] kinds = new int[4];
        int depth;
    }

    public static final int GUARD_GET = 1;
    public static final int GUARD_SET = 2;
    public static final int GUARD_UNSET = 4;
    public static final int GUARD_ISSET = 8;
    public static final int GUARD_HOOK_GET = 16;
    public static final int GUARD_HOOK_SET = 32;
    /** Either hook running for a property: its {@code $this->prop} accesses the backing store. */
    public static final int GUARD_HOOK_ANY = GUARD_HOOK_GET | GUARD_HOOK_SET;

    /** True while the magic method {@code kind} runs for property {@code name} on this object. */
    public boolean inMagic(String name, int kind) {
        MagicGuardStack g = magicGuards;
        if (g == null) {
            return false;
        }
        for (int i = g.depth - 1; i >= 0; i--) {
            if ((g.kinds[i] & kind) != 0 && g.names[i].equals(name)) {
                return true;
            }
        }
        return false;
    }

    public void enterMagic(String name, int kind) {
        MagicGuardStack g = magicGuards;
        if (g == null) {
            g = magicGuards = new MagicGuardStack();
        }
        if (g.depth == g.names.length) {
            g.names = java.util.Arrays.copyOf(g.names, g.depth * 2);
            g.kinds = java.util.Arrays.copyOf(g.kinds, g.depth * 2);
        }
        g.names[g.depth] = name;
        g.kinds[g.depth] = kind;
        g.depth++;
    }

    public void exitMagic(String name, int kind) {
        MagicGuardStack g = magicGuards;
        if (g == null) {
            return;
        }
        int last = g.depth - 1;
        if (last < 0) {
            return;
        }
        int at = last;
        if (g.kinds[at] != kind || !g.names[at].equals(name)) {
            // A mismatched exit (nested calls with different properties): drop the newest match.
            at = -1;
            for (int i = last; i >= 0; i--) {
                if (g.kinds[i] == kind && g.names[i].equals(name)) {
                    at = i;
                    break;
                }
            }
            if (at < 0) {
                return;
            }
        }
        if (at != last) {
            System.arraycopy(g.names, at + 1, g.names, at, last - at);
            System.arraycopy(g.kinds, at + 1, g.kinds, at, last - at);
        }
        g.names[last] = null;
        g.depth = last;
    }
    private PhpNativeState nativeState;

    public PhpObject(PhpClass phpClass, long id) {
        this(phpClass, id, null);
    }

    public PhpObject(PhpClass phpClass, long id, PhpClosure closure) {
        super(phpClass.template().rootShape());
        this.phpClass = phpClass;
        this.id = id;
        this.closure = closure;
    }

    public PhpClass phpClass() {
        return phpClass;
    }

    public long id() {
        return id;
    }

    public PhpClosure closure() {
        return closure;
    }

    /** Used by the snapshot copier, which must register the object before copying its closure. */
    public void setClosure(PhpClosure closure) {
        this.closure = closure;
    }

    public boolean isCloning() {
        return cloning;
    }

    public void setCloning(boolean cloning) {
        this.cloning = cloning;
    }

    public PhpNativeState nativeState() {
        return nativeState;
    }

    public void setNativeState(PhpNativeState nativeState) {
        this.nativeState = nativeState;
    }

    @Override
    public String toString() {
        return phpClass.name() + "#" + id;
    }
}
