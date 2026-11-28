package dev.phpruntime.host;

import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpNativeState;

/** The native state of one {@code Blitz\Http\Request} object. */
public final class BlitzRequestState implements PhpNativeState {

    public final BlitzHttpRequest request;
    public final long connectionId;
    public PhpArray get;
    public PhpArray post;
    public PhpArray cookie;
    public PhpArray files;
    public PhpArray server;
    public PhpArray header;
    public String rawContent;

    public BlitzRequestState(BlitzHttpRequest request, long connectionId) {
        this.request = request;
        this.connectionId = connectionId;
    }

    public void release() {
        get = null;
        post = null;
        cookie = null;
        files = null;
        server = null;
        header = null;
        rawContent = null;
        request.release();
    }

    @Override
    public PhpNativeState copy() {
        return this;
    }
}
