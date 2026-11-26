package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
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

    /** The engine-wide class id of {@link #callingClassLower}; -2 until first needed. */
    @CompilerDirectives.CompilationFinal private int callingClassId = -2;

    /**
     * The calling class in this request: one read of the request's by-id table instead of a
     * lookup by name (a private or protected static property checked it on every write).
     */
    private PhpClass callingClass(PhpContext ctx) {
        int id = callingClassId;
        if (id == -2) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            id = PhpLanguage.get(this).classId(callingClassLower);
            callingClassId = id;
        }
        PhpClass cls = ctx.classById(id);
        return cls != null ? cls : ctx.lookupClass(callingClassLower);
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
        return executeWithKeys(frame, keys, PhpContext.get(this));
    }

    public Object executeWithKeys(VirtualFrame frame, Object[] keys, PhpContext ctx) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        PhpClass caller = keys.length > 2 && keys[2] instanceof PhpClass c ? c : null;
        PhpClass.StaticSlot slot = requireSlot(cls, name, caller, ctx);
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
        return read(frame, keys, PhpContext.get(this));
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys, PhpContext ctx) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        PhpClass caller = keys.length > 2 && keys[2] instanceof PhpClass c ? c : null;
        Resolution res = resolution(cls, name, caller, ctx);
        if (res.decl() == null || !res.accessible()) {
            return null;
        }
        Object val = res.decl().slot(ctx).get();
        if (val == PhpClass.UNINITIALIZED) {
            throw uninitialized(cls, name);
        }
        return val;
    }

    /** isset()/?? treat an uninitialized typed static property as unset instead of throwing. */
    @Override
    public Object readIfSet(VirtualFrame frame, Object[] keys) {
        return readIfSet(frame, keys, PhpContext.get(this));
    }

    @Override
    public Object readIfSet(VirtualFrame frame, Object[] keys, PhpContext ctx) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        PhpClass caller = keys.length > 2 && keys[2] instanceof PhpClass c ? c : null;
        Resolution res = resolution(cls, name, caller, ctx);
        if (res.decl() == null || !res.accessible()) {
            return null;
        }
        Object val = res.decl().slot(ctx).get();
        return val == PhpClass.UNINITIALIZED ? null : val;
    }

    @Override
    public boolean isSet(VirtualFrame frame, Object[] keys) {
        return isSet(frame, keys, PhpContext.get(this));
    }

    @Override
    public boolean isSet(VirtualFrame frame, Object[] keys, PhpContext ctx) {
        Object value = readIfSet(frame, keys, ctx);
        return value != null && value != PhpNull.INSTANCE;
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        write(frame, keys, value, PhpContext.get(this));
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value, PhpContext ctx) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        PhpClass caller = keys.length > 2 && keys[2] instanceof PhpClass c ? c : null;
        Resolution res = resolution(cls, name, caller, ctx);
        if (res.decl() == null) {
            CompilerDirectives.transferToInterpreter();
            throw undeclared(cls, name);
        }
        if (!res.accessible()) {
            CompilerDirectives.transferToInterpreter();
            throw inaccessible(res.decl(), name);
        }
        if (!setAccessible(res.decl(), caller, ctx)) {
            CompilerDirectives.transferToInterpreter();
            throw cannotModifySet(res.decl(), name, caller, "modify");
        }
        if (res.decl().declaredType() != null) {
            value = res.decl().declaredType().checkProperty(value, ctx, this,
                    CallMode.strictAt(this), res.decl().declaringClass(), res.decl().declaringClass().name(), name);
        }
        res.decl().slot(ctx).set(value);
    }

    private boolean setAccessible(PhpClass.StaticDecl decl, PhpClass traitCaller, PhpContext ctx) {
        Visibility set = decl.effectiveSetVisibility();
        if (set == Visibility.PUBLIC) {
            return true;
        }
        PhpClass caller = traitCaller;
        if (caller == null && callingClassLower != null) {
            caller = callingClass(ctx);
        }
        if (set == Visibility.PRIVATE) {
            return caller != null && dev.phpruntime.runtime.PhpStrings.equalsIgnoreCase(caller.name(),
                    decl.declaringClass().name());
        }
        return caller != null && (decl.declaringClass().isInstanceOf(caller)
                || caller.isInstanceOf(decl.declaringClass()));
    }

    @TruffleBoundary
    private PhpError cannotModifySet(PhpClass.StaticDecl decl, String name, PhpClass traitCaller, String action) {
        String level = decl.effectiveSetVisibility() == Visibility.PRIVATE ? "private(set)" : "protected(set)";
        String scope;
        if (traitCaller != null) {
            scope = "scope " + traitCaller.name();
        } else if (callingClassLower == null) {
            scope = "global scope";
        } else {
            PhpClass caller = callingClass(PhpContext.get(this));
            scope = caller == null ? "global scope" : "scope " + caller.name();
        }
        return new PhpError("Cannot " + action + " " + level + " property " + decl.declaringClass().name()
                + "::$" + name + " from " + scope, this);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private PhpError uninitializedByReference(PhpClass declaring, String name) {
        return new PhpError("Cannot access uninitialized non-nullable property "
                + declaring.name() + "::$" + name + " by reference", this);
    }

    @Override
    public PhpReference obtainCell(VirtualFrame frame, Object[] keys) {
        return obtainCell(frame, keys, PhpContext.get(this));
    }

    @Override
    public PhpReference obtainCell(VirtualFrame frame, Object[] keys, PhpContext ctx) {
        PhpClass caller = keys.length > 2 && keys[2] instanceof PhpClass c ? c : null;
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        Resolution res = resolution(cls, name, caller, ctx);
        if (res.decl() == null) {
            throw undeclared(cls, name);
        }
        if (!res.accessible()) {
            throw inaccessible(res.decl(), name);
        }
        if (!setAccessible(res.decl(), caller, ctx)) {
            throw cannotModifySet(res.decl(), name, caller, "indirectly modify");
        }
        PhpClass.StaticSlot slot = res.decl().slot(ctx);
        if (slot.get() == PhpClass.UNINITIALIZED) {
            if (res.decl().declaredType() != null && !res.decl().declaredType().acceptsNull()) {
                throw uninitializedByReference(res.decl().declaringClass(), name);
            }
            slot.set(PhpNull.INSTANCE);
        }
        PhpReference cell = slot.obtainCell();
        if (res.decl().declaredType() != null) {
            cell.setCellCheck(res.decl().declaredType().propertyCellCheck(ctx,
                    CallMode.strictAt(this), res.decl().declaringClass(),
                    res.decl().declaringClass().name(), name));
        }
        return cell;
    }

    @Override
    public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell) {
        bindCell(frame, keys, cell, PhpContext.get(this));
    }

    @Override
    public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell, PhpContext ctx) {
        PhpClass caller = keys.length > 2 && keys[2] instanceof PhpClass c ? c : null;
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        Resolution res = resolution(cls, name, caller, ctx);
        if (res.decl() == null) {
            throw undeclared(cls, name);
        }
        if (!res.accessible()) {
            throw inaccessible(res.decl(), name);
        }
        if (!setAccessible(res.decl(), caller, ctx)) {
            throw cannotModifySet(res.decl(), name, caller, "indirectly modify");
        }
        if (res.decl().declaredType() != null) {
            Object current = cell.getValue();
            Object checked = res.decl().declaredType().checkProperty(current, ctx, this,
                    CallMode.strictAt(this), res.decl().declaringClass(), res.decl().declaringClass().name(), name);
            if (checked != current) {
                cell.setValue(checked);
            }
            cell.setCellCheck(res.decl().declaredType().propertyCellCheck(ctx,
                    CallMode.strictAt(this), res.decl().declaringClass(),
                    res.decl().declaringClass().name(), name));
        }
        res.decl().slot(ctx).bindCell(cell);
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        CompilerDirectives.transferToInterpreter();
        throw new PhpError(msg("Attempt to unset static property ", cls.name(), "::$", name), this);
    }

    /**
     * {@code Cls::$p++} / {@code --}: one resolution and one slot lookup instead of the read
     * and the write resolving the property again.
     */
    public Object increment(VirtualFrame frame, Object[] keys, boolean increment, boolean prefix) {
        return increment(frame, keys, increment, prefix, PhpContext.get(this));
    }

    public Object increment(VirtualFrame frame, Object[] keys, boolean increment, boolean prefix, PhpContext ctx) {
        PhpClass cls = (PhpClass) keys[0];
        String name = (String) keys[1];
        PhpClass caller = keys.length > 2 && keys[2] instanceof PhpClass c ? c : null;
        Resolution res = resolution(cls, name, caller, ctx);
        if (res.decl() == null) {
            CompilerDirectives.transferToInterpreter();
            throw undeclared(cls, name);
        }
        if (!res.accessible()) {
            CompilerDirectives.transferToInterpreter();
            throw inaccessible(res.decl(), name);
        }
        if (!setAccessible(res.decl(), caller, ctx)) {
            CompilerDirectives.transferToInterpreter();
            throw cannotModifySet(res.decl(), name, caller, "modify");
        }
        PhpClass.StaticSlot slot = res.decl().slot(ctx);
        Object old = slot.get();
        if (old == PhpClass.UNINITIALIZED) {
            throw uninitialized(cls, name);
        }
        if (old instanceof Long l
                && res.decl().declaredType() != null
                && isIntType(res.decl().declaredType().type())) {
            // php refuses to turn a typed int past its range into a float.
            if (increment && l == Long.MAX_VALUE) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError(PhpError.TYPE_ERROR, "Cannot increment property "
                        + res.decl().declaringClass().name() + "::$" + name
                        + " of type int past its maximal value", this);
            }
            if (!increment && l == Long.MIN_VALUE) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError(PhpError.TYPE_ERROR, "Cannot decrement property "
                        + res.decl().declaringClass().name() + "::$" + name
                        + " of type int past its minimal value", this);
            }
        }
        Object updated;
        if (old instanceof Long l && l != Long.MAX_VALUE && l != Long.MIN_VALUE) {
            updated = increment ? Long.valueOf(l + 1) : Long.valueOf(l - 1);
        } else if (old == null || old == PhpNull.INSTANCE) {
            updated = increment ? (Object) 1L : PhpNull.INSTANCE;
        } else if (old instanceof String str && !PhpOps.isNumericString(str)) {
            updated = increment ? dev.phpruntime.nodes.var.IncDecNode.incrementString(str) : str;
        } else {
            updated = increment ? dev.phpruntime.runtime.PhpArith.add(old, 1L)
                    : dev.phpruntime.runtime.PhpArith.sub(old, 1L);
        }
        if (res.decl().declaredType() != null) {
            updated = res.decl().declaredType().checkProperty(updated, ctx, this, CallMode.strictAt(this),
                    res.decl().declaringClass(), res.decl().declaringClass().name(), name);
        }
        slot.set(updated);
        return prefix ? updated : (old == null ? PhpNull.INSTANCE : old);
    }

    private static boolean isIntType(dev.phpruntime.runtime.PhpType type) {
        if (type instanceof dev.phpruntime.runtime.PhpType.Named named) {
            return "int".equalsIgnoreCase(named.name());
        }
        return type instanceof dev.phpruntime.runtime.PhpType.Nullable nullable && isIntType(nullable.inner());
    }

    private PhpClass.StaticSlot requireSlot(PhpClass cls, String name, PhpClass traitCaller, PhpContext ctx) {
        Resolution res = resolution(cls, name, traitCaller, ctx);
        if (res.decl() == null) {
            CompilerDirectives.transferToInterpreter();
            throw undeclared(cls, name);
        }
        if (!res.accessible()) {
            CompilerDirectives.transferToInterpreter();
            throw inaccessible(res.decl(), name);
        }
        return res.decl().slot(ctx);
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
    private boolean isAccessible(PhpClass targetClass, PhpClass.StaticDecl decl, PhpClass traitCaller, PhpContext ctx) {
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
        PhpClass caller = callingClass(ctx);
        return caller != null && (targetClass.isInstanceOf(caller) || caller.isInstanceOf(decl.declaringClass()));
    }

    /** The cached resolution for this site, filling it in on the interpreter path on a miss. */
    private Resolution resolution(PhpClass cls, String name, PhpClass traitCaller, PhpContext ctx) {
        Resolution[] entries = cache;
        if (entries == MEGAMORPHIC) {
            return resolveSlow(cls, name, traitCaller, ctx);
        }
        Resolution hit = lookup(entries, cls, name, traitCaller);
        if (hit != null) {
            return hit;
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        return cacheResolution(cls, name, traitCaller, ctx);
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
    private Resolution resolveSlow(PhpClass cls, String name, PhpClass traitCaller, PhpContext ctx) {
        PhpClass.StaticDecl decl = cls.lookupStaticDecl(name);
        return new Resolution(cls, name, traitCaller, decl, decl != null && isAccessible(cls, decl, traitCaller, ctx));
    }

    @TruffleBoundary
    private Resolution cacheResolution(PhpClass cls, String name, PhpClass traitCaller, PhpContext ctx) {
        PhpClass.StaticDecl decl = cls.lookupStaticDecl(name);
        Resolution entry = new Resolution(cls, name, traitCaller, decl,
                decl != null && isAccessible(cls, decl, traitCaller, ctx));
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

    @Override
    public Object readValue(VirtualFrame frame, Object[] keys, PhpContext ctx) {
        Object v = read(frame, keys, ctx);
        return v == null ? undefined(ctx) : v;
    }

}
