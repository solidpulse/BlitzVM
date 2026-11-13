package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;

/**
 * What one local variable has held, shared by every node that writes it and by
 * parameter binding. A variable that only ever holds ints, or only floats, is stored
 * unboxed in a long or double slot. One that has held both (the result of
 * {@code $a * $t / 8} is either) is stored as a boxed object instead: reading a typed
 * slot in code that cannot tell int from float makes a new box on every read, and two
 * different box types meeting in one value cannot be optimized away, while a stored
 * box is shared by all the reads. Both facts are Assumptions, so all compiled code that
 * relied on them is thrown away together when they change.
 */
public final class VarKind {

    private final Assumption noInts = Truffle.getRuntime().createAssumption("variable never held an int");
    private final Assumption noFloats = Truffle.getRuntime().createAssumption("variable never held a float");

    /** True once the variable has held both: its numbers are then stored boxed. */
    public boolean mixed() {
        return !noInts.isValid() && !noFloats.isValid();
    }

    /** Records an int write; true when the int should be stored boxed. */
    public boolean longBoxed() {
        if (noInts.isValid()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            noInts.invalidate();
        }
        return !noFloats.isValid();
    }

    /** Records a float write; true when the float should be stored boxed. */
    public boolean doubleBoxed() {
        if (noFloats.isValid()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            noFloats.invalidate();
        }
        return !noInts.isValid();
    }
}
