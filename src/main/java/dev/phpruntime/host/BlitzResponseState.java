package dev.phpruntime.host;

import dev.phpruntime.runtime.PhpNativeState;

/** The native state of one {@code Blitz\Http\Response} object. */
public final class BlitzResponseState implements PhpNativeState {

    public final BlitzHttpResponseWriter writer;

    public BlitzResponseState(BlitzHttpResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public PhpNativeState copy() {
        return this;
    }
}
