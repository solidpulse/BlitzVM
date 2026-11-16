package dev.phpruntime.nodes.stmt;

import com.oracle.truffle.api.frame.VirtualFrame;

/** A statement node that contains labels reachable from an enclosing block via {@code goto}. */
public interface GotoEnterable {

    int[] exportedLabelIds();

    void executeEnter(VirtualFrame frame, int labelId);
}
