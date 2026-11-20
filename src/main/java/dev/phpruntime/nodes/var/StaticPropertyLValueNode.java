package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.expr.ClassRefNode;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.CallMode;
import dev.phpruntime.runtime.Visibility;

import java.util.Arrays;
import java.util.concurrent.locks.Lock;

/** {@code Foo::$prop}, {@code self::$prop}, {@code static::$prop}, {@code parent::$prop}. */
public final class StaticPropertyLValueNode extends LValueNode {

    @Child private ClassRefNode classRef;
    private final String staticName;
    @Child private PhpNode dynamicNameExpr;
    private final String callingClassLower;
    private final int traitSelfSlot;

    /**
     * How (class, name, trait caller) resolves for this site: the declaration and whether this
     * site may access it. The slot itself is per request and never cached here. Published as one
     * immutable object so a request reading it while another installs it never sees half an entry.
     */
    private record Resolution(PhpClass cls, String name, PhpClass traitCaller,
                              PhpClass.StaticDecl decl, boolean accessible) {
    }

    /** Distinct triples one site caches before falling back to the boundary path. */
    private static final int CACHE_LIMIT = 8;
    private static final Resolution[] MEGAMORPHIC = new Resolution[0];

    @CompilationFinal(dimensions = 1) private Resolution[] cache;

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

    /** The rvalue read with the class, name and trait caller already evaluated. */
    public Object executeWithKeys(VirtualFrame frame, Object[] keys) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        PhpClass caller = keys.length > 2 ? (PhpClass) keys[2] : null;
        PhpClass.StaticSlot slot = requireSlot(cls, name, caller);
        Object val = slot.get();
        if (val == PhpClass.UNINITIALIZED) {
            throw uninitialized(cls, name);
        }
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
        Resolution res = resolution(cls, name, caller);
        if (res.decl() == null || !res.accessible()) {
            return null;
        }
        Object val = res.decl().slot(PhpContext.get(this)).get();
        if (val == PhpClass.UNINITIALIZED) {
            throw uninitialized(cls, name);
        }
        return val;
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        PhpClass caller = keys.length > 2 ? (PhpClass) keys[2] : null;
        Resolution res = resolution(cls, name, caller);
        if (res.decl() == null) {
            CompilerDirectives.transferToInterpreter();
            throw undeclared(cls, name);
        }
        if (!res.accessible()) {
            CompilerDirectives.transferToInterpreter();
            throw inaccessible(res.decl(), name);
        }
        if (res.decl().declaredType() != null) {
            value = res.decl().declaredType().checkProperty(value, PhpContext.get(this), this,
                    CallMode.strictAt(this), res.decl().declaringClass(), res.decl().declaringClass().name(), name);
        }
        res.decl().slot(PhpContext.get(this)).set(value);
    }

    @Override
    public PhpReference obtainCell(VirtualFrame frame, Object[] keys) {
        PhpClass caller = keys.length > 2 ? (PhpClass) keys[2] : null;
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        Resolution res = resolution(cls, name, caller);
        if (res.decl() == null) {
            throw undeclared(cls, name);
        }
        if (!res.accessible()) {
            throw inaccessible(res.decl(), name);
        }
        PhpClass.StaticSlot slot = res.decl().slot(PhpContext.get(this));
        if (slot.get() == PhpClass.UNINITIALIZED) {
            if (res.decl().declaredType() != null && !res.decl().declaredType().acceptsNull()) {
                throw new PhpError("Cannot access uninitialized non-nullable property "
                        + res.decl().declaringClass().name() + "::$" + name + " by reference", this);
            }
            slot.set(PhpNull.INSTANCE);
        }
        PhpReference cell = slot.obtainCell();
        if (res.decl().declaredType() != null) {
            cell.setCellCheck(res.decl().declaredType().propertyCellCheck(PhpContext.get(this),
                    CallMode.strictAt(this), res.decl().declaringClass(),
                    res.decl().declaringClass().name(), name));
        }
        return cell;
    }

    @Override
    public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell) {
        PhpClass caller = keys.length > 2 ? (PhpClass) keys[2] : null;
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        Resolution res = resolution(cls, name, caller);
        if (res.decl() == null) {
            throw undeclared(cls, name);
        }
        if (!res.accessible()) {
            throw inaccessible(res.decl(), name);
        }
        if (res.decl().declaredType() != null) {
            Object current = cell.getValue();
            Object checked = res.decl().declaredType().checkProperty(current, PhpContext.get(this), this,
                    CallMode.strictAt(this), res.decl().declaringClass(), res.decl().declaringClass().name(), name);
            if (checked != current) {
                cell.setValue(checked);
            }
            cell.setCellCheck(res.decl().declaredType().propertyCellCheck(PhpContext.get(this),
                    CallMode.strictAt(this), res.decl().declaringClass(),
                    res.decl().declaringClass().name(), name));
        }
        res.decl().slot(PhpContext.get(this)).bindCell(cell);
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        CompilerDirectives.transferToInterpreter();
        throw new PhpError("Attempt to unset static property " + cls.name() + "::$" + name, this);
    }

    private PhpClass.StaticSlot requireSlot(PhpClass cls, String name, PhpClass traitCaller) {
        Resolution res = resolution(cls, name, traitCaller);
        if (res.decl() == null) {
            CompilerDirectives.transferToInterpreter();
            throw undeclared(cls, name);
        }
        if (!res.accessible()) {
            CompilerDirectives.transferToInterpreter();
            throw inaccessible(res.decl(), name);
        }
        return res.decl().slot(PhpContext.get(this));
    }

    @TruffleBoundary
    private PhpError undeclared(PhpClass cls, String name) {
        return new PhpError("Access to undeclared static property " + cls.name() + "::$" + name, this);
    }

    @TruffleBoundary
    private PhpError uninitialized(PhpClass cls, String name) {
        return new PhpError("Typed static property " + cls.name() + "::$" + name
                + " must not be accessed before initialization", this);
    }

    @TruffleBoundary
    private PhpError inaccessible(PhpClass.StaticDecl decl, String name) {
        String level = decl.visibility() == Visibility.PRIVATE ? "private" : "protected";
        return new PhpError("Cannot access " + level + " property " + decl.declaringClass().name() + "::$" + name, this);
    }

    @TruffleBoundary
    private boolean isAccessible(PhpClass targetClass, PhpClass.StaticDecl decl, PhpClass traitCaller) {
        if (decl.visibility() == Visibility.PUBLIC) {
            return true;
        }
        if (traitCaller != null) {
            if (decl.visibility() == Visibility.PRIVATE) {
                return decl.declaringClass() == traitCaller;
            }
            return targetClass.isInstanceOf(traitCaller) || traitCaller.isInstanceOf(decl.declaringClass());
        }
        if (callingClassLower == null) {
            return false;
        }
        if (decl.visibility() == Visibility.PRIVATE) {
            return dev.phpruntime.runtime.PhpStrings.equalsIgnoreCase(decl.declaringClass().name(), callingClassLower);
        }
        PhpClass caller = PhpContext.get(this).lookupClass(callingClassLower);
        return caller != null && (targetClass.isInstanceOf(caller) || caller.isInstanceOf(decl.declaringClass()));
    }

    /** The cached resolution for this site, filling it in on the interpreter path on a miss. */
    private Resolution resolution(PhpClass cls, String name, PhpClass traitCaller) {
        Resolution[] entries = cache;
        if (entries == MEGAMORPHIC) {
            return resolveSlow(cls, name, traitCaller);
        }
        Resolution hit = lookup(entries, cls, name, traitCaller);
        if (hit != null) {
            return hit;
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        return cacheResolution(cls, name, traitCaller);
    }

    @ExplodeLoop
    private Resolution lookup(Resolution[] entries, PhpClass cls, String name, PhpClass traitCaller) {
        if (entries == null) {
            return null;
        }
        for (int i = 0; i < entries.length; i++) {
            Resolution c = entries[i];
            if (c.cls() == cls && c.traitCaller() == traitCaller && name.equals(c.name())) {
                return c;
            }
        }
        return null;
    }

    @TruffleBoundary
    private Resolution resolveSlow(PhpClass cls, String name, PhpClass traitCaller) {
        PhpClass.StaticDecl decl = cls.lookupStaticDecl(name);
        return new Resolution(cls, name, traitCaller, decl, decl != null && isAccessible(cls, decl, traitCaller));
    }

    @TruffleBoundary
    private Resolution cacheResolution(PhpClass cls, String name, PhpClass traitCaller) {
        PhpClass.StaticDecl decl = cls.lookupStaticDecl(name);
        Resolution entry = new Resolution(cls, name, traitCaller, decl,
                decl != null && isAccessible(cls, decl, traitCaller));
        Lock lock = getLock();
        lock.lock();
        try {
            Resolution[] entries = cache;
            if (entries != MEGAMORPHIC) {
                boolean present = false;
                if (entries != null) {
                    for (Resolution c : entries) {
                        present |= c.cls() == cls && c.traitCaller() == traitCaller && name.equals(c.name());
                    }
                }
                if (!present) {
                    Resolution[] next;
                    if (entries == null) {
                        next = new Resolution[]{entry};
                    } else if (entries.length >= CACHE_LIMIT) {
                        next = MEGAMORPHIC;
                    } else {
                        next = Arrays.copyOf(entries, entries.length + 1);
                        next[entries.length] = entry;
                    }
                    // Readers take the array without the lock: its elements must be visible first.
                    java.lang.invoke.VarHandle.storeStoreFence();
                    cache = next;
                }
            }
        } finally {
            lock.unlock();
        }
        return entry;
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
