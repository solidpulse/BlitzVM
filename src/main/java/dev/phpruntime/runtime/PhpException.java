package dev.phpruntime.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;

/**
 * Truffle guest exception carrying a thrown PHP {@link PhpObject} implementing {@code Throwable}.
 * Suppresses Java stack trace capture (passing stackTraceElementLimit = 0) because the PHP
 * stack trace is already captured on the {@link PhpObject} at construction time ({@code new}),
 * making {@code throw} and {@code catch} cheap and Partial-Evaluation friendly.
 */
public final class PhpException extends AbstractTruffleException {

    private final PhpObject exceptionObject;
    private String cachedFormattedUncaught;

    public PhpException(PhpObject exceptionObject, Node location) {
        super(null, null, 0, location);
        this.exceptionObject = exceptionObject;
    }

    public PhpObject exceptionObject() {
        return exceptionObject;
    }

    /** Caches the pre-formatted uncaught message while the Truffle context is still active. */
    public String formattedUncaught() {
        if (cachedFormattedUncaught == null) {
            cachedFormattedUncaught = PhpExceptions.formatUncaught(exceptionObject);
        }
        return cachedFormattedUncaught;
    }

    @Override
    public String getMessage() {
        return formattedUncaught();
    }
}
