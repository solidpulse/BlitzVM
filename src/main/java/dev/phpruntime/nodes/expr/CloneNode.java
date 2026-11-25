package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.call.DispatchCallNode;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.Visibility;

/** {@code clone $obj} and PHP 8.5's {@code clone($obj, ['prop' => $value])}. */
public final class CloneNode extends PhpNode {

    @Child private PhpNode targetExpr;
    @Child private PhpNode withProperties;
    private final String callingClass;
    private final int traitSelfSlot;

    public CloneNode(PhpNode targetExpr, PhpNode withProperties, String callingClass, int traitSelfSlot) {
        this.targetExpr = targetExpr;
        this.withProperties = withProperties;
        this.callingClass = callingClass;
        this.traitSelfSlot = traitSelfSlot;
    }

    /** The semantics of {@code clone $value}; the bytecode operation passes this instruction's site. */
    public static Object cloneValue(Object val, Node site, DispatchCallNode dispatch, CopyNode copier) {
        if (!(val instanceof PhpObject obj)) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.TYPE_ERROR,
                    "clone(): Argument #1 ($object) must be of type object, "
                            + PhpOps.typeName(val) + " given", site);
        }
        if (obj.nativeState() instanceof dev.phpruntime.runtime.EnumCaseState) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("Trying to clone an uncloneable object of class " + obj.phpClass().name(), site);
        }
        PhpObject copy = shallowCopy(PhpContext.get(site), obj, copier);
        runCloneMethod(obj, copy, site, dispatch);
        return copy;
    }

    /** {@code clone($obj, $with)}: clone, let {@code __clone} run, then assign the new values. */
    public static Object cloneValueWith(Object val, Object with, Node site, DispatchCallNode dispatch,
                                        String callingClass, Object traitSelf, CopyNode copier) {
        if (!(val instanceof PhpObject obj)) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.TYPE_ERROR,
                    "clone(): Argument #1 ($object) must be of type object, " + PhpOps.typeName(val) + " given", site);
        }
        if (!(with instanceof PhpArray properties)) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError(PhpError.TYPE_ERROR,
                    "clone(): Argument #2 ($withProperties) must be of type array, "
                            + PhpOps.typeName(with) + " given", site);
        }
        if (obj.nativeState() instanceof dev.phpruntime.runtime.EnumCaseState) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
            throw new PhpError("Trying to clone an uncloneable object of class " + obj.phpClass().name(), site);
        }
        PhpObject copy = shallowCopy(PhpContext.get(site), obj, copier);
        runCloneMethod(obj, copy, site, dispatch);
        applyProperties(copy, properties, callingClass,
                traitSelf instanceof PhpClass c ? c.template().lowerName() : null, site);
        return copy;
    }

    private static void runCloneMethod(PhpObject obj, PhpObject copy, Node site, DispatchCallNode dispatch) {
        PhpClass.MethodEntry cloneMethod = obj.phpClass().cloneMethod();
        if (cloneMethod != null && cloneMethod.callTarget() != null) {
            copy.setCloning(true);
            try {
                Object[] callArgs = cloneMethod.fromTrait()
                        ? new Object[]{copy, obj.phpClass(), cloneMethod.declaringClass()}
                        : new Object[]{copy, obj.phpClass()};
                dispatch.call(cloneMethod.callTarget(), callArgs);
            } catch (PhpError e) {
                throw e.at(site);
            } finally {
                copy.setCloning(false);
            }
        }
    }

    @TruffleBoundary
    private static void applyProperties(PhpObject obj, PhpArray properties, String callingClass,
                                        String traitCaller, Node site) {
        PhpContext ctx = PhpContext.get(site);
        String callerLower = traitCaller != null ? traitCaller
                : (callingClass == null ? null : dev.phpruntime.runtime.PhpStrings.lower(callingClass));
        DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
        DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
        for (int pos = properties.firstPos(); pos >= 0; pos = properties.nextPos(pos)) {
            Object key = properties.keyAt(pos);
            Object raw = properties.rawValueAt(pos);
            String name = PhpOps.toStr(key);
            if (raw instanceof PhpReference) {
                throw new PhpError("Cannot assign by reference when cloning with updated properties", site);
            }
            Object value = raw == null ? PhpNull.INSTANCE : raw;
            PhpClass.PropertyResolution res = obj.phpClass().resolveProperty(ctx, name, callerLower);
            if (!res.accessible()) {
                String level = res.visibility() == Visibility.PRIVATE ? "private" : "protected";
                String owner = res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name();
                throw new PhpError("Cannot access " + level + " property " + owner + "::$" + name, site);
            }
            if (res.declaringClass() == null
                    && (obj.phpClass().template().isEnum() || obj.phpClass().isReadonlyClass())) {
                throw new PhpError("Cannot create dynamic property " + obj.phpClass().name() + "::$" + name, site);
            }
            String storageKey = res.storageKey();
            if (res.isReadonly()) {
                if (!res.canInitReadonly()) {
                    String owner = res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name();
                    throw new PhpError("Cannot modify protected(set) readonly property " + owner + "::$"
                            + name + " from " + scopeName(ctx, callerLower), site);
                }
                put.execute(obj, storageKey, typed(res, value, obj, name, ctx, site));
                continue;
            }
            Object existing = get.execute(obj, storageKey, null);
            Object checked = typed(res, value, obj, name, ctx, site);
            if (existing instanceof PhpReference ref) {
                ref.setValue(checked);
            } else if (existing == null && res.declaringClass() == null
                    && obj.phpClass().setMethod() != null && obj.phpClass().setMethod().callTarget() != null) {
                PhpClass.MethodEntry set = obj.phpClass().setMethod();
                if (set.fromTrait()) {
                    dev.phpruntime.runtime.PhpBacktrace.callUnsited(set.callTarget(), obj, obj.phpClass(), set.declaringClass(), name, checked);
                } else {
                    dev.phpruntime.runtime.PhpBacktrace.callUnsited(set.callTarget(), obj, obj.phpClass(), name, checked);
                }
            } else {
                put.execute(obj, storageKey, checked);
            }
        }
    }

    private static Object typed(PhpClass.PropertyResolution res, Object value, PhpObject obj, String name,
                                PhpContext ctx, Node site) {
        if (res.type() == null) {
            return value;
        }
        PhpClass owner = res.declaringClass() == null ? null : ctx.lookupClass(res.declaringClass());
        return res.type().checkProperty(value, ctx, site, false, owner,
                res.declaringClass() != null ? res.declaringClass() : obj.phpClass().name(), name);
    }

    private static String scopeName(PhpContext ctx, String callerLower) {
        if (callerLower == null) {
            return "global scope";
        }
        PhpClass caller = ctx.lookupClass(callerLower);
        return "scope " + (caller != null ? caller.name() : callerLower);
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (withProperties == null) {
            e.builder().beginClone();
            e.expr(targetExpr);
            e.builder().endClone();
            return;
        }
        e.builder().beginCloneWith(callingClass == null ? "" : callingClass, traitSelfSlot);
        e.expr(targetExpr);
        e.expr(withProperties);
        e.builder().endCloneWith();
    }

    /**
     * The Java-side state of a builtin class instance (SPL file, collection, random engine):
     * its copy uses JDK collections and strings, so it stays out of compiled guest code (native
     * image refuses those methods in runtime compilation, and HotSpot gains nothing from them).
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static dev.phpruntime.runtime.PhpNativeState copyNativeState(dev.phpruntime.runtime.PhpNativeState state) {
        return state.copy();
    }

    private static PhpObject shallowCopy(PhpContext ctx, PhpObject src, CopyNode copier) {
        PhpObject copy = new PhpObject(
                src.phpClass(),
                ctx.nextObjectId(),
                src.closure() != null ? src.closure().copy() : null);
        if (src.nativeState() != null) {
            copy.setNativeState(copyNativeState(src.nativeState()));
        }
        copier.execute(src, copy);
        return copy;
    }

    /**
     * Copies every property of the source into the new object as php's clone does: values as
     * they are (uninitialized markers and reference cells included, so explicit aliases stay
     * aliases), with each array value shared copy-on-write. A site that sees a few source shapes
     * copies the storage with {@link DynamicObject.CopyPropertiesNode} and shares the arrays
     * through getters made once per shape; property-by-property gets and puts through one node
     * went megamorphic (about 2% of /blog cycles).
     */
    @com.oracle.truffle.api.dsl.GenerateInline(false)
    public abstract static class CopyNode extends Node {
        public abstract void execute(PhpObject src, PhpObject copy);

        @com.oracle.truffle.api.dsl.Specialization(guards = "src.getShape() == cachedShape", limit = "3")
        @com.oracle.truffle.api.nodes.ExplodeLoop
        static void cached(PhpObject src, PhpObject copy,
                           @com.oracle.truffle.api.dsl.Cached("src.getShape()") com.oracle.truffle.api.object.Shape cachedShape,
                           @com.oracle.truffle.api.dsl.Cached(value = "getters(cachedShape)", dimensions = 1)
                           com.oracle.truffle.api.object.PropertyGetter[] getters,
                           @com.oracle.truffle.api.dsl.Cached DynamicObject.CopyPropertiesNode copyProperties) {
            copyProperties.execute(src, copy);
            for (int i = 0; i < getters.length; i++) {
                if (getters[i].get(src) instanceof dev.phpruntime.runtime.PhpArray array) {
                    array.share();
                }
            }
        }

        static com.oracle.truffle.api.object.PropertyGetter[] getters(com.oracle.truffle.api.object.Shape shape) {
            java.util.List<Object> keys = shape.getKeyList();
            com.oracle.truffle.api.object.PropertyGetter[] getters = new com.oracle.truffle.api.object.PropertyGetter[keys.size()];
            for (int i = 0; i < getters.length; i++) {
                getters[i] = shape.makePropertyGetter(keys.get(i));
            }
            return getters;
        }

        @com.oracle.truffle.api.dsl.Specialization(replaces = "cached")
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        static void generic(PhpObject src, PhpObject copy) {
            DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
            DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
            Object[] keys = DynamicObject.GetKeyArrayNode.getUncached().execute(src);
            for (Object k : keys) {
                Object raw = get.execute(src, k, PhpNull.INSTANCE);
                put.execute(copy, k, raw == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(raw));
            }
        }
    }
}
