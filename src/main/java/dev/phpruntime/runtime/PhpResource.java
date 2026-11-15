package dev.phpruntime.runtime;

/**
 * A PHP resource: a numbered handle to host state such as an open file or a stream context.
 * Ids are per request (see the stream builtins). A closed resource keeps its id but reports
 * type "Unknown", as PHP does.
 */
public final class PhpResource {

    private final long id;
    private final String type;
    private Object handle;
    private boolean closed;

    public PhpResource(long id, String type, Object handle) {
        this.id = id;
        this.type = type;
        this.handle = handle;
    }

    public long id() {
        return id;
    }

    /** get_resource_type(): the type name, or "Unknown" once closed. */
    public String type() {
        return closed ? "Unknown" : type;
    }

    public boolean isClosed() {
        return closed;
    }

    public Object handle() {
        return handle;
    }

    public void close() {
        closed = true;
        handle = null;
    }

    @Override
    public String toString() {
        return "Resource id #" + id;
    }
}
