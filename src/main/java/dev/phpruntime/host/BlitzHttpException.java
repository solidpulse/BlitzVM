package dev.phpruntime.host;

/** A malformed or unsupported HTTP request: the connection answers with this status. */
final class BlitzHttpException extends Exception {

    private static final long serialVersionUID = 1L;

    final int status;

    BlitzHttpException(int status, String message) {
        super(message, null, false, false);
        this.status = status;
    }
}
