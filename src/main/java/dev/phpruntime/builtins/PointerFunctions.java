package dev.phpruntime.builtins;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpOps;

/** reset, end, current, key, next, prev: the array's internal pointer. */
final class PointerFunctions {

    private static final int REF_FIRST = 1;

    private PointerFunctions() {
    }

    static void register() {
        Builtins.defRef("reset", 1, 1, REF_FIRST, (ctx, a) -> {
            PhpArray arr = moving(a, "reset");
            arr.pointerReset();
            return valueOrFalse(arr);
        });
        Builtins.defRef("end", 1, 1, REF_FIRST, (ctx, a) -> {
            PhpArray arr = moving(a, "end");
            arr.pointerEnd();
            return valueOrFalse(arr);
        });
        Builtins.defRef("next", 1, 1, REF_FIRST, (ctx, a) -> {
            PhpArray arr = moving(a, "next");
            arr.pointerNext();
            return valueOrFalse(arr);
        });
        Builtins.defRef("prev", 1, 1, REF_FIRST, (ctx, a) -> {
            PhpArray arr = moving(a, "prev");
            arr.pointerPrev();
            return valueOrFalse(arr);
        });
        Builtins.def("current", 1, 1, (ctx, a) -> valueOrFalse(array(a[0], "current")));
        Builtins.def("pos", 1, 1, (ctx, a) -> valueOrFalse(array(a[0], "pos")));
        Builtins.def("key", 1, 1, (ctx, a) -> {
            Object k = array(a[0], "key").pointerKey();
            return k == null ? PhpNull.INSTANCE : k;
        });
    }

    /**
     * The by-reference array, separated from other owners first (as PHP does), so moving the
     * pointer never affects a copy held elsewhere. An unshared array is moved in place, which
     * keeps a next() loop linear.
     */
    private static PhpArray moving(Object[] a, String fn) {
        PhpArray arr = array(a[0], fn).forWrite();
        a[0] = arr;
        return arr;
    }

    private static PhpArray array(Object v, String fn) {
        if (v instanceof PhpArray arr) {
            return arr;
        }
        throw new PhpError(PhpError.TYPE_ERROR, fn + "(): Argument #1 ($array) must be of type array, "
                + PhpOps.typeName(v) + " given", null);
    }

    private static Object valueOrFalse(PhpArray arr) {
        Object v = arr.pointerValue();
        return v == null ? false : v;
    }
}
