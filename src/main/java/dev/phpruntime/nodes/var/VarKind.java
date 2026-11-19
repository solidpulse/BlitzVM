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
 * box is shared by all the reads. A variable that has held any non-number ({@code null},
 * {@code false}, a string, an array, an object) also stores its numbers boxed: mixing
 * unboxed and object slots would otherwise change the bytecode local's slot tag back
 * and forth on every call, and each tag change invalidates all compiled code of the
 * function. Both facts are Assumptions, so all compiled code that relied on them is
 * thrown away together when they change.
 */
public final class VarKind {

    private final Assumption noInts = Truffle.getRuntime().createAssumption("variable never held an int");
    private final Assumption noFloats = Truffle.getRuntime().createAssumption("variable never held a float");
    private final Assumption noObjects = Truffle.getRuntime().createAssumption("variable never held a non-number");
    private final Assumption notCell = Truffle.getRuntime().createAssumption("variable is not a reference cell");
    private final Assumption notAppendTarget = Truffle.getRuntime().createAssumption("variable is not an append target");

    public boolean isAppendTarget() { return !notAppendTarget.isValid(); }

    /** Set while parsing; reads of all other variables have no buffer type check. */
    public void markAppendTarget() { notAppendTarget.invalidate(); }

    /** True if this local is stored indirectly through a {@code PhpReference} cell. */
    public boolean isCell() {
        return !notCell.isValid();
    }

    /** Marks this variable as stored through a {@code PhpReference} cell (parse time). */
    public void markCell() {
        if (notCell.isValid()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            notCell.invalidate();
        }
    }

    /** True once the variable has held both: its numbers are then stored boxed. */
    public boolean mixed() {
        return !noInts.isValid() && !noFloats.isValid();
    }

    /** True when the variable has only ever held ints, so it is stored in a long slot. */
    public boolean longSlot() {
        return noObjects.isValid() && noFloats.isValid() && !noInts.isValid();
    }

    /** True when the variable has only ever held floats, so it is stored in a double slot. */
    public boolean doubleSlot() {
        return noObjects.isValid() && noInts.isValid() && !noFloats.isValid();
    }

    /** Records a write of a non-number; the variable's numbers are then stored boxed. */
    public void markObject() {
        if (noObjects.isValid()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            noObjects.invalidate();
        }
    }

    /** Records an int write; true when the int should be stored boxed. */
    public boolean longBoxed() {
        if (noInts.isValid()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            noInts.invalidate();
        }
        return !noFloats.isValid() || !noObjects.isValid();
    }

    /** Records a float write; true when the float should be stored boxed. */
    public boolean doubleBoxed() {
        if (noFloats.isValid()) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            noFloats.invalidate();
        }
        return !noInts.isValid() || !noObjects.isValid();
    }
}
