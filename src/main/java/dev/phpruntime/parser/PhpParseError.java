package dev.phpruntime.parser;

public final class PhpParseError extends RuntimeException {
    private final int line;

    public PhpParseError(String message, int line) {
        super("PHP Parse error: " + message + " on line " + line);
        this.line = line;
    }

    public int line() {
        return line;
    }
}
