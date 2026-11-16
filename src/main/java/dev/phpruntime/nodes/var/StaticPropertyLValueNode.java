package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.expr.ClassRefNode;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.Visibility;

/** {@code Foo::$prop}, {@code self::$prop}, {@code static::$prop}, {@code parent::$prop}. */
public final class StaticPropertyLValueNode extends LValueNode {

    @Child private ClassRefNode classRef;
    private final String staticName;
    @Child private PhpNode dynamicNameExpr;
    private final String callingClassLower;

    public StaticPropertyLValueNode(ClassRefNode classRef, String staticName, PhpNode dynamicNameExpr,
                                    String callingClassLower) {
        this.classRef = classRef;
        this.staticName = staticName;
        this.dynamicNameExpr = dynamicNameExpr;
        this.callingClassLower = callingClassLower;
    }

    @Override
    public Object[] evalKeys(VirtualFrame frame) {
        PhpClass cls = classRef.resolve(frame);
        String name = staticName != null ? staticName : PhpOps.toStr(dynamicNameExpr.execute(frame));
        return new Object[]{cls, name};
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] keys = evalKeys(frame);
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        PhpClass.StaticSlot slot = requireSlot(cls, name);
        Object v = slot.value instanceof PhpReference ref ? ref.value : slot.value;
        return v == null ? PhpNull.INSTANCE : v;
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        PhpClass.StaticSlot slot = cls.lookupStaticProperty(name);
        if (slot == null || !isAccessible(cls, slot)) {
            return null;
        }
        return slot.value instanceof PhpReference ref ? ref.value : slot.value;
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        PhpClass.StaticSlot slot = requireSlot(cls, name);
        if (slot.value instanceof PhpReference ref) {
            ref.value = value;
        } else {
            slot.value = value;
        }
    }

    @Override
    public PhpReference obtainCell(VirtualFrame frame, Object[] keys) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        PhpClass.StaticSlot slot = requireSlot(cls, name);
        if (slot.value instanceof PhpReference ref) {
            if (ref.value == null) {
                ref.value = PhpNull.INSTANCE;
            }
            return ref;
        }
        PhpReference ref = new PhpReference(slot.value == null ? PhpNull.INSTANCE : slot.value);
        slot.value = ref;
        return ref;
    }

    @Override
    public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        PhpClass.StaticSlot slot = requireSlot(cls, name);
        slot.value = cell;
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        throw new PhpError("Attempt to unset static property " + cls.name() + "::$" + name, this);
    }

    @TruffleBoundary
    private PhpClass.StaticSlot requireSlot(PhpClass cls, String name) {
        PhpClass.StaticSlot slot = cls.lookupStaticProperty(name);
        if (slot == null) {
            throw new PhpError("Access to undeclared static property " + cls.name() + "::$" + name, this);
        }
        if (!isAccessible(cls, slot)) {
            String level = slot.visibility == Visibility.PRIVATE ? "private" : "protected";
            throw new PhpError(
                    "Cannot access " + level + " property " + slot.declaringClass.name() + "::$" + name,
                    this);
        }
        return slot;
    }

    @TruffleBoundary
    private boolean isAccessible(PhpClass targetClass, PhpClass.StaticSlot slot) {
        if (slot.visibility == Visibility.PUBLIC) {
            return true;
        }
        if (callingClassLower == null) {
            return false;
        }
        if (slot.visibility == Visibility.PRIVATE) {
            return slot.declaringClass.name().equalsIgnoreCase(callingClassLower);
        }
        PhpClass caller = PhpContext.get(this).lookupClass(callingClassLower);
        return caller != null && (targetClass.isInstanceOf(caller) || caller.isInstanceOf(slot.declaringClass));
    }

    @Override
    public String describe() {
        return staticName != null ? "Class::$" + staticName : "Class::$prop";
    }
}
