package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.PhpContext;

/**
 * A PHP engine error or fatal error. Catchable errors carry a PHP Error subclass name
 * ({@code Error}, {@code TypeError}, {@code ArgumentCountError}, {@code ValueError},
 * {@code ArithmeticError}, {@code DivisionByZeroError}, {@code ParseError}) and lazily
 * materialize a {@link PhpObject} only if caught or inspected. Non-catchable fatals
 * (such as class/function redeclaration) pass straight through {@code catch} blocks.
 */
public final class PhpError extends AbstractTruffleException {

    public static final String ERROR = "Error";
    public static final String ARGUMENT_COUNT = "ArgumentCountError";
    public static final String DIVISION_BY_ZERO = "DivisionByZeroError";
    public static final String ARITHMETIC_ERROR = "ArithmeticError";
    public static final String TYPE_ERROR = "TypeError";
    public static final String VALUE_ERROR = "ValueError";
    public static final String PARSE_ERROR = "ParseError";

    private final String kind;
    private final String detail;
    private final boolean catchable;
    private final String customFile;
    private final int customLine;
    private PhpBacktrace.LazyTrace capturedTrace;
    private PhpObject materialized;

    public PhpError(String message) {
        this(ERROR, message, true, null, -1, null, null);
    }

    public PhpError(String message, Node location) {
        this(ERROR, message, true, null, -1, location, null);
    }

    public PhpError(String kind, String message, Node location) {
        this(kind, message, true, null, -1, location, null);
    }

    public PhpError(String kind, String message, String customFile, int customLine, Node location) {
        this(kind, message, true, customFile, customLine, location, null);
    }

    private PhpError(String kind, String message, boolean catchable, String customFile, int customLine,
                     Node location, PhpBacktrace.LazyTrace trace) {
        super(catchable ? (kind + ": " + message) : message, location);
        this.kind = kind;
        this.detail = message;
        this.catchable = catchable;
        this.customFile = customFile;
        this.customLine = customLine;
        this.capturedTrace = trace;
    }

    /** Creates an uncatchable compile/declaration fatal error. */
    public static PhpError fatal(String message) {
        return new PhpError(ERROR, message, false, null, -1, null, null);
    }

    public boolean isCatchable() {
        return catchable;
    }

    public String kind() {
        return kind;
    }

    public String detail() {
        return detail;
    }

    /** The same error, attributed to {@code location} if it has no location yet. */
    public PhpError at(Node location) {
        if (getLocation() != null || location == null) {
            return this;
        }
        return new PhpError(kind, detail, catchable, customFile, customLine, location, capturedTrace);
    }

    /** Attaches a pre-captured guest call stack if none is attached yet. */
    public PhpError withTrace(PhpBacktrace.LazyTrace trace) {
        if (this.capturedTrace == null) {
            this.capturedTrace = trace;
        }
        return this;
    }

    @TruffleBoundary
    public PhpClass resolveErrorClass(PhpContext ctx) {
        PhpClass cls = ctx.lookupClass(kind);
        return cls != null ? cls : ctx.lookupClass(ERROR);
    }

    /** Lazily materializes the backing PHP {@code Error} object when caught or formatted. */
    @TruffleBoundary
    public PhpObject toPhpObject(PhpContext ctx, Node fallbackLocation) {
        if (materialized != null) {
            return materialized;
        }
        Node loc = getLocation() != null ? getLocation() : fallbackLocation;
        String file = customFile != null ? customFile : PhpBacktrace.sourcePath(loc);
        int line = customLine > 0 ? customLine : PhpBacktrace.sourceLine(loc);
        PhpBacktrace.LazyTrace trace = capturedTrace != null
                ? capturedTrace
                : traceFromTruffleStack(loc);
        materialized = PhpExceptions.createBuiltinThrowable(ctx, kind, detail, 0L, file, line, trace);
        return materialized;
    }

    @TruffleBoundary
    private PhpBacktrace.LazyTrace traceFromTruffleStack(Node loc) {
        java.util.List<com.oracle.truffle.api.TruffleStackTraceElement> elements =
                com.oracle.truffle.api.TruffleStackTrace.getStackTrace(this);
        if (elements == null || elements.isEmpty()) {
            return PhpBacktrace.capture(loc, false);
        }
        java.util.List<PhpBacktrace.FrameEntry> out = new java.util.ArrayList<>(elements.size());
        String pendingFn = null;
        String pendingCls = null;
        String pendingType = null;
        Object[] pendingArgs = null;
        for (com.oracle.truffle.api.TruffleStackTraceElement el : elements) {
            com.oracle.truffle.api.nodes.RootNode root = el.getTarget().getRootNode();
            if (!(root instanceof dev.phpruntime.nodes.PhpRootNode)) {
                continue;
            }
            if (pendingFn != null || pendingArgs != null) {
                Node callSite = el.getLocation();
                String f = PhpBacktrace.sourcePath(callSite);
                int l = PhpBacktrace.sourceLine(callSite);
                String fn = pendingFn;
                if (fn == null) {
                    fn = includeNameAt(callSite);
                }
                out.add(new PhpBacktrace.FrameEntry(f, l, fn, pendingCls, pendingType, null, pendingArgs));
                pendingFn = null;
                pendingCls = null;
                pendingType = null;
                pendingArgs = null;
            }
            if (root instanceof dev.phpruntime.nodes.PhpFunctionRootNode fnRoot) {
                com.oracle.truffle.api.frame.Frame frame = el.getFrame();
                pendingFn = fnRoot.rawFunctionName();
                if (fnRoot.isClosure()) {
                    pendingCls = fnRoot.declaringClass();
                    if (pendingCls != null) {
                        pendingType = fnRoot.isStaticMethod() ? "::" : "->";
                    }
                } else if (fnRoot.isMethod()) {
                    pendingCls = fnRoot.declaringClass();
                    if (frame != null) {
                        boolean hasThis = frame.getArguments().length > 0 && frame.getArguments()[0] instanceof PhpObject;
                        pendingType = hasThis ? "->" : "::";
                    } else {
                        pendingType = fnRoot.isStaticMethod() ? "::" : "->";
                    }
                }
            } else if (root instanceof dev.phpruntime.nodes.PhpMainRootNode) {
                com.oracle.truffle.api.frame.Frame frame = el.getFrame();
                if (frame != null && frame.getArguments().length > 0) {
                    pendingFn = null;
                    pendingArgs = new Object[0];
                } else {
                    break;
                }
            }
        }
        return new PhpBacktrace.LazyTrace(out.toArray(new PhpBacktrace.FrameEntry[0]));
    }

    private static String includeNameAt(Node n) {
        for (Node cur = n; cur != null; cur = cur.getParent()) {
            if (cur instanceof dev.phpruntime.nodes.expr.IncludeNode inc) {
                return switch (inc.kind()) {
                    case INCLUDE -> "include";
                    case INCLUDE_ONCE -> "include_once";
                    case REQUIRE -> "require";
                    case REQUIRE_ONCE -> "require_once";
                };
            }
            if (cur instanceof com.oracle.truffle.api.nodes.RootNode) {
                break;
            }
        }
        return "include";
    }
}
