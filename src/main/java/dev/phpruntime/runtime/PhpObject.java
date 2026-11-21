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
    /** php's property guards: name -> bits of the magic methods running for it (zend_get_property_guard). */
    private java.util.HashMap<String, Integer> magicGuards;

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
        return magicGuards != null && (magicGuards.getOrDefault(name, 0) & kind) != 0;
    }

    public void enterMagic(String name, int kind) {
        if (magicGuards == null) {
            magicGuards = new java.util.HashMap<>();
        }
        magicGuards.merge(name, kind, (a, b) -> a | b);
    }

    public void exitMagic(String name, int kind) {
        Integer bits = magicGuards.get(name);
        if (bits != null) {
            int rest = bits & ~kind;
            if (rest == 0) {
                magicGuards.remove(name);
            } else {
                magicGuards.put(name, rest);
            }
        }
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
