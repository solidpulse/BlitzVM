package dev.phpruntime.runtime;

import java.lang.ref.WeakReference;

/** WeakReference native state: the referent, held weakly. */
public final class PhpWeakReference implements PhpNativeState {
    private final WeakReference<PhpObject> referent;

    public PhpWeakReference(PhpObject referent) {
        this.referent = new WeakReference<>(referent);
    }

    public PhpObject get() {
        return referent.get();
    }

    @Override
    public PhpNativeState copy() {
        PhpObject target = referent.get();
        return target == null ? this : new PhpWeakReference(target);
    }
}
