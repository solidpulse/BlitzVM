package dev.phpruntime.js.parser;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;

/** A JavaScript syntax error, exported as PARSE_ERROR like the PHP one. */
@ExportLibrary(InteropLibrary.class)
public final class JsParseError extends AbstractTruffleException {

    private final int line;
    private Source source;

    public JsParseError(String message, int line) {
        super("SyntaxError: " + message);
        this.line = line;
    }

    JsParseError withSource(Source s) {
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
    SourceSection sourceLocation() throws UnsupportedMessageException {
        if (!hasSourceLocation()) {
            throw UnsupportedMessageException.create();
        }
        return source.createSection(line);
    }
}
