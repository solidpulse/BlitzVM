package dev.phpruntime.runtime;

/** The entry script reserves compiled variable names before dynamic additions. */
public record PhpGlobalLayout(String[] names) {}
