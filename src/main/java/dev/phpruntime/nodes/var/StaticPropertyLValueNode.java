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
    private final int traitSelfSlot;

    public StaticPropertyLValueNode(ClassRefNode classRef, String staticName, PhpNode dynamicNameExpr,
                                    String callingClassLower) {
        this(classRef, staticName, dynamicNameExpr, callingClassLower, -1);
    }

    public StaticPropertyLValueNode(ClassRefNode classRef, String staticName, PhpNode dynamicNameExpr,
                                    String callingClassLower, int traitSelfSlot) {
        this.classRef = classRef;
        this.staticName = staticName;
        this.dynamicNameExpr = dynamicNameExpr;
        this.callingClassLower = callingClassLower;
        this.traitSelfSlot = traitSelfSlot;
    }

    @Override
    public Object[] evalKeys(VirtualFrame frame) {
        PhpClass cls = classRef.resolve(frame);
        String name = staticName != null ? staticName : PhpOps.toStr(dynamicNameExpr.execute(frame));
        if (traitSelfSlot >= 0) {
            return new Object[]{cls, name, frame.getObject(traitSelfSlot)};
        }
        return new Object[]{cls, name};
    }

    /** The rvalue read with the class, name and trait caller already evaluated. */
    public Object executeWithKeys(VirtualFrame frame, Object[] keys) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        PhpClass caller = keys.length > 2 ? (PhpClass) keys[2] : null;
        PhpClass.StaticSlot slot = requireSlot(cls, name, caller);
        Object val = slot.get();
        return val == null ? PhpNull.INSTANCE : val;
    }

    /** A copy without its expressions, for bytecode, which evaluates the class and the name. */
    public StaticPropertyLValueNode headless() {
        return new StaticPropertyLValueNode(null, staticName, null, callingClassLower).at(line());
    }

    public ClassRefNode classRef() {
        return classRef;
    }

    public String staticName() {
        return staticName;
    }

    public PhpNode dynamicNameExpr() {
        return dynamicNameExpr;
    }

    public int traitSelfSlot() {
        return traitSelfSlot;
    }

    public boolean usesTraitSelf() {
        return traitSelfSlot >= 0;
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        PhpClass caller = keys.length > 2 ? (PhpClass) keys[2] : null;
        PhpClass.StaticSlot slot = cls.lookupStaticProperty(PhpContext.get(this), name);
        if (slot == null || !isAccessible(cls, slot, caller)) {
            return null;
        }
        return slot.get();
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        PhpClass caller = keys.length > 2 ? (PhpClass) keys[2] : null;
        PhpClass.StaticSlot slot = requireSlot(cls, name, caller);
        slot.set(value);
    }

    @Override
    public PhpReference obtainCell(VirtualFrame frame, Object[] keys) {
        PhpClass caller = keys.length > 2 ? (PhpClass) keys[2] : null;
        return requireSlot((PhpClass) keys[0], (String) keys[1], caller).obtainCell();
    }

    @Override
    public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell) {
        PhpClass caller = keys.length > 2 ? (PhpClass) keys[2] : null;
        requireSlot((PhpClass) keys[0], (String) keys[1], caller).bindCell(cell);
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
        throw new PhpError("Attempt to unset static property " + cls.name() + "::$" + name, this);
    }

    @TruffleBoundary
    private PhpClass.StaticSlot requireSlot(PhpClass cls, String name, PhpClass traitCaller) {
        PhpClass.StaticSlot slot = cls.lookupStaticProperty(PhpContext.get(this), name);
        if (slot == null) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("Access to undeclared static property " + cls.name() + "::$" + name, this);
        }
        if (!isAccessible(cls, slot, traitCaller)) {
            String level = slot.visibility == Visibility.PRIVATE ? "private" : "protected";
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(
                    "Cannot access " + level + " property " + slot.declaringClass.name() + "::$" + name,
                    this);
        }
        return slot;
    }

    @TruffleBoundary
    private boolean isAccessible(PhpClass targetClass, PhpClass.StaticSlot slot, PhpClass traitCaller) {
        if (slot.visibility == Visibility.PUBLIC) {
            return true;
        }
        if (traitCaller != null) {
            if (slot.visibility == Visibility.PRIVATE) {
                return slot.declaringClass == traitCaller;
            }
            return targetClass.isInstanceOf(traitCaller) || traitCaller.isInstanceOf(slot.declaringClass);
        }
        if (callingClassLower == null) {
            return false;
        }
        if (slot.visibility == Visibility.PRIVATE) {
            return dev.phpruntime.runtime.PhpStrings.equalsIgnoreCase(slot.declaringClass.name(), callingClassLower);
        }
        PhpClass caller = PhpContext.get(this).lookupClass(callingClassLower);
        return caller != null && (targetClass.isInstanceOf(caller) || caller.isInstanceOf(slot.declaringClass));
    }

    @Override
    public String describe() {
        return staticName != null ? "Class::$" + staticName : "Class::$prop";
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        e.place(this, dev.phpruntime.bytecode.PhpBytecodeRootNode.P_READ, 0, null);
    }
}
