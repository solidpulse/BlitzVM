package dev.phpruntime.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.SplArrayState;

/** Legacy SPL serialize methods reuse the existing scalar/array serializer, not another codec. */
public final class SplNativeSupport {
    private SplNativeSupport() {
    }

    public static PhpArray properties(PhpObject object) {
        PhpArray result = new PhpArray();
        DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
        for (Object key : DynamicObject.GetKeyArrayNode.getUncached().execute(object)) {
            result.put(key, get.execute(object, key, PhpNull.INSTANCE));
        }
        return result;
    }

    @TruffleBoundary
    public static void restoreArray(PhpContext ctx, PhpObject self, SplArrayState state, String data) {
        if (!data.startsWith("x:i:")) {
            throw new PhpError("UnexpectedValueException", "Error at offset 0 of " + data.length() + " bytes", null);
        }
        int flagsEnd = data.indexOf(';', 4);
        int members = data.lastIndexOf(";m:");
        if (flagsEnd < 0 || members < flagsEnd) {
            throw new PhpError("UnexpectedValueException", "Error at offset 0 of " + data.length() + " bytes", null);
        }
        int flags;
        try {
            flags = Integer.parseInt(data.substring(4, flagsEnd));
        } catch (NumberFormatException error) {
            throw new PhpError("UnexpectedValueException", "Error at offset 4 of " + data.length() + " bytes", null);
        }
        Object storage = Builtins.lookup("unserialize").impl().call(ctx, new Object[]{data.substring(flagsEnd + 1, members)});
        Object props = Builtins.lookup("unserialize").impl().call(ctx, new Object[]{data.substring(members + 3)});
        if (!(storage instanceof PhpArray array) || !(props instanceof PhpArray properties)) {
            throw new PhpError("UnexpectedValueException", "Error at offset " + flagsEnd + " of " + data.length() + " bytes", null);
        }
        SplArrayState.Storage replacement = new SplArrayState.Storage(array);
        state.storage.array = replacement.array;
        state.storage.object = null;
        state.storage.size = replacement.size;
        state.storage.version++;
        state.flags = flags;
        state.position = 0;
        DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
        for (var entry : properties.entries()) {
            put.execute(self, PhpOps.toStr(entry.getKey()), PhpOps.copyOnAssign(entry.getValue()));
        }
    }
}
