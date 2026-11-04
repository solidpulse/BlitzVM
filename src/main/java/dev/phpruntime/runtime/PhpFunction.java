package dev.phpruntime.runtime;

import com.oracle.truffle.api.RootCallTarget;

/** A user-defined PHP function: its name and the call target of its root node. */
public record PhpFunction(String name, RootCallTarget callTarget) {
}
