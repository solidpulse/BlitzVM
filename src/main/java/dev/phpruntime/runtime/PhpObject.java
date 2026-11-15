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
    private final PhpClosure closure;

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

    @Override
    public String toString() {
        return phpClass.name() + "#" + id;
    }
}
