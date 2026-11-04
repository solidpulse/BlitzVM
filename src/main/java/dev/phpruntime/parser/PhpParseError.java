package dev.phpruntime.parser;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

/** A syntax error. Exported as PARSE_ERROR so embedders see PolyglotException.isSyntaxError(). */
@ExportLibrary(InteropLibrary.class)
public final class PhpParseError extends AbstractTruffleException {

    private final int line;
    private Source source;

    public PhpParseError(String message, int line) {
        super(message);
        this.line = line;
    }

    public int line() {
        return line;
    }

    PhpParseError withSource(Source s) {
        this.source = s;
        return this;
    }

    @ExportMessage
    ExceptionType getExceptionType() {
        return ExceptionType.PARSE_ERROR;
    }

    @ExportMessage
    boolean hasSourceLocation() {
        return source != null && line > 0 && line <= source.getLineCount();
    }

    @ExportMessage(name = "getSourceLocation")
    SourceSection sourceLocation() throws com.oracle.truffle.api.interop.UnsupportedMessageException {
        if (!hasSourceLocation()) {
            throw com.oracle.truffle.api.interop.UnsupportedMessageException.create();
        }
        return source.createSection(line);
    }
}
