package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.call.CallArgHelper;
import dev.phpruntime.nodes.call.ObjectMethodNode;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.SplInterfaces;

/** Cached ArrayAccess dispatch shared by reads, writes, existence tests and destructuring. */
public final class ArrayAccessNode extends Node {
    @Child private ObjectMethodNode get;
    @Child private ObjectMethodNode set;
    @Child private ObjectMethodNode exists;
    @Child private ObjectMethodNode unset;
    private final int getId;

    public ArrayAccessNode(PhpLanguage language) {
        getId = language.methodId("offsetGet");
        get = new ObjectMethodNode(language, "offsetGet");
        set = new ObjectMethodNode(language, "offsetSet");
        exists = new ObjectMethodNode(language, "offsetExists");
        unset = new ObjectMethodNode(language, "offsetUnset");
    }

    private void check(PhpObject obj) {
        if (!obj.phpClass().hasSplInterface(SplInterfaces.ARRAY_ACCESS)) {
            throw new PhpError("Cannot use object of type " + obj.phpClass().name() + " as array", this);
        }
    }

    public Object getRaw(PhpObject obj, Object key) {
        check(obj);
        if (obj.nativeState() instanceof dev.phpruntime.runtime.SplArrayState state && isNativeGet(obj)) {
            if (key == dev.phpruntime.runtime.PhpNull.INSTANCE) { return state.cell(key); }
            Object value = state.get(key);
            if (value instanceof dev.phpruntime.runtime.PhpObject) { return value; }
            return state.cell(key);
        }
        return get.call(obj, key);
    }

    public void bindCell(PhpObject obj, Object key, PhpReference cell) {
        check(obj);
        if (obj.nativeState() instanceof dev.phpruntime.runtime.SplArrayState state && isNativeGet(obj)) {
            if (state.storage.array != null) {
                state.storage.array = state.storage.array.forWrite();
                if (key == dev.phpruntime.runtime.PhpNull.INSTANCE) { state.storage.array.appendRef(cell); }
                else { state.storage.array.putRef(key, cell); }
                state.storage.size = state.storage.array.size();
                state.storage.version++;
                return;
            }
        }
        throw new PhpError("Cannot assign by reference to an array dimension of an object", this);
    }

    public Object get(PhpObject obj, Object key) {
        check(obj);
        return CallArgHelper.unwrapReturn(get.call(obj, key));
    }

    public boolean exists(PhpObject obj, Object key) {
        check(obj);
        return dev.phpruntime.nodes.expr.CondNode.toBool(CallArgHelper.unwrapReturn(exists.call(obj, key)));
    }

    public Object readIfSet(PhpObject obj, Object key) {
        if (!exists(obj, key)) { return null; }
        return get(obj, key);
    }

    public boolean isSet(PhpObject obj, Object key) {
        if (!exists(obj, key)) { return false; }
        if (obj.nativeState() instanceof dev.phpruntime.runtime.SplArrayState state && isNativeGet(obj)) {
            Object value = state.get(key);
            return value != null && value != dev.phpruntime.runtime.PhpNull.INSTANCE;
        }
        return true;
    }

    private boolean isNativeGet(PhpObject obj) {
        return obj.phpClass().methodById(getId).callTarget().getRootNode() instanceof dev.phpruntime.nodes.call.SplMethodRootNode;
    }

    public void set(PhpObject obj, Object key, Object value) {
        check(obj);
        set.call(obj, key, value);
    }

    public void unset(PhpObject obj, Object key) {
        check(obj);
        unset.call(obj, key);
    }

    public PhpReference cell(PhpObject obj, Object key) {
        Object raw = getRaw(obj, key);
        if (raw instanceof PhpReference ref) {
            return ref;
        }
        indirectNotice(obj);
        return new PhpReference(PhpOps.copyOnAssign(raw));
    }

    public void indirectNotice(PhpObject obj) {
        Node parent = getParent();
        PhpContext.get(this).notice("Indirect modification of overloaded element of "
                + obj.phpClass().name() + " has no effect", parent instanceof PhpNode php ? php.line() : 0);
    }
}
