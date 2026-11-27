package dev.phpruntime.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.runtime.PhpWeakReference;

/** WeakReference: create() makes the holder, get() reads the referent if it is still alive. */
public final class SplWeakReferenceMethodRootNode extends RootNode {
    private final String name;

    public SplWeakReferenceMethodRootNode(PhpLanguage language, String name) {
        super(language);
        this.name = name;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (PhpStrings.CHECK) {
            PhpStrings.checkArguments(frame.getArguments(), "WeakReference::" + name);
        }
        Object result = invoke(frame.getArguments());
        return PhpStrings.CHECK ? PhpStrings.checkReturn(result, "WeakReference::" + name) : result;
    }

    @TruffleBoundary
    private Object invoke(Object[] raw) {
        int count = raw.length - 2;
        switch (name) {
            case "__construct" -> throw new PhpError("Direct instantiation of WeakReference is not allowed, "
                    + "use WeakReference::create instead", this);
            case "create" -> {
                if (count != 1) {
                    throw new PhpError(PhpError.ARGUMENT_COUNT, "WeakReference::create() expects exactly 1 argument, "
                            + count + " given", this);
                }
                if (!(raw[2] instanceof PhpObject referent)) {
                    throw new PhpError(PhpError.TYPE_ERROR, "WeakReference::create(): Argument #1 ($object) must be of "
                            + "type object, " + PhpOps.typeDescription(raw[2]) + " given", this);
                }
                PhpContext ctx = PhpContext.get(this);
                PhpClass cls = ctx.lookupClass("WeakReference");
                PhpObject reference = new PhpObject(cls, ctx.nextObjectId());
                cls.populateDefaults(reference);
                reference.setNativeState(new PhpWeakReference(referent));
                return reference;
            }
            case "get" -> {
                if (count != 0) {
                    throw new PhpError(PhpError.ARGUMENT_COUNT, "WeakReference::get() expects exactly 0 arguments, "
                            + count + " given", this);
                }
                PhpObject self = (PhpObject) raw[0];
                if (!(self.nativeState() instanceof PhpWeakReference reference)) {
                    throw new PhpError("The WeakReference is not initialized", this);
                }
                PhpObject referent = reference.get();
                return referent == null ? PhpNull.INSTANCE : referent;
            }
            default -> throw new IllegalStateException("WeakReference::" + name);
        }
    }

    @Override
    public String getName() {
        return "WeakReference::" + name;
    }

    /** A builtin method is not guest code: tools treat it as internal. */
    @Override
    public boolean isInternal() {
        return true;
    }
}
