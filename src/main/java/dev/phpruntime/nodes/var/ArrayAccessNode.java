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

    public ArrayAccessNode(PhpLanguage language) {
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
        return get.call(obj, key);
    }

    public Object get(PhpObject obj, Object key) {
        return CallArgHelper.unwrapReturn(getRaw(obj, key));
    }

    public boolean exists(PhpObject obj, Object key) {
        check(obj);
        return PhpOps.toBool(CallArgHelper.unwrapReturn(exists.call(obj, key)));
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
