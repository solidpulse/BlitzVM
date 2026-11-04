package dev.phpruntime.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;

/**
 * A PHP fatal error. The message carries the PHP error class, e.g.
 * "DivisionByZeroError: Division by zero", so the host can print it like php-cli.
 * The node location gives Truffle a source section, hence file and line.
 */
public final class PhpError extends AbstractTruffleException {

    public static final String ERROR = "Error";
    public static final String ARGUMENT_COUNT = "ArgumentCountError";
    public static final String DIVISION_BY_ZERO = "DivisionByZeroError";
    public static final String TYPE_ERROR = "TypeError";

    private final String kind;
    private final String detail;

    public PhpError(String message) {
        this(ERROR, message, null);
    }

    public PhpError(String message, Node location) {
        this(ERROR, message, location);
    }

    public PhpError(String kind, String message, Node location) {
        super(kind + ": " + message, location);
        this.kind = kind;
        this.detail = message;
    }

    /** The same error, attributed to {@code location} if it has no location yet. */
    public PhpError at(Node location) {
        return getLocation() != null ? this : new PhpError(kind, detail, location);
    }
}
