package dev.phpruntime.nodes.stmt;

/** A statement node that contains labels reachable from an enclosing block via {@code goto}. */
public interface GotoEnterable {

    /** The labels declared inside this construct, in source order. */
    int[] exportedLabelIds();
}
