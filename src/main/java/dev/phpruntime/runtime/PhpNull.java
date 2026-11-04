package dev.phpruntime.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/** The PHP {@code null} singleton. */
@ExportLibrary(InteropLibrary.class)
public final class PhpNull implements TruffleObject {
    public static final PhpNull INSTANCE = new PhpNull();

    private PhpNull() {
    }

    @ExportMessage
    boolean isNull() {
        return true;
    }

    @Override
    public String toString() {
        return "NULL";
    }
}
