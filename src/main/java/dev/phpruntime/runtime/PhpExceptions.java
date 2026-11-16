package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.object.DynamicObject;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared built-in {@code Throwable} / {@code Exception} / {@code Error} templates and
 * helper operations for initializing, formatting, and chaining PHP throwables.
 */
public final class PhpExceptions {

    public static final String THROWABLE = "Throwable";
    public static final String EXCEPTION = "Exception";
    public static final String ERROR = "Error";

    private static final char NUL = 0;
    private static final char LF = 10;

    public static final String EXC_STRING_KEY = NUL + "Exception" + NUL + "string";
    public static final String EXC_TRACE_KEY = NUL + "Exception" + NUL + "trace";
    public static final String EXC_PREV_KEY = NUL + "Exception" + NUL + "previous";

    public static final String ERR_STRING_KEY = NUL + "Error" + NUL + "string";
    public static final String ERR_TRACE_KEY = NUL + "Error" + NUL + "trace";
    public static final String ERR_PREV_KEY = NUL + "Error" + NUL + "previous";

    private PhpExceptions() {
    }

    /** Registers all built-in Throwable / Exception / Error templates into a language map keyed by lowercase name. */
    public static Map<String, PhpClassTemplate> buildTemplates(PhpLanguage language) {
        Map<String, PhpClassTemplate> out = new LinkedHashMap<>();

        // 1. interface Throwable
        Map<String, PhpClassTemplate.MethodDecl> throwableMethods = new LinkedHashMap<>();
        addAbstractMethod(language, throwableMethods, "getMessage");
        addAbstractMethod(language, throwableMethods, "getCode");
        addAbstractMethod(language, throwableMethods, "getFile");
        addAbstractMethod(language, throwableMethods, "getLine");
        addAbstractMethod(language, throwableMethods, "getTrace");
        addAbstractMethod(language, throwableMethods, "getPrevious");
        addAbstractMethod(language, throwableMethods, "getTraceAsString");
        addAbstractMethod(language, throwableMethods, "__toString");
        register(out, new PhpClassTemplate(
                language.nextTemplateId(), THROWABLE, true, false, false, false,
                null, List.of(), List.of(), List.of(), throwableMethods));

        // 2. class Exception implements Throwable
        register(out, buildBaseThrowableClass(language, EXCEPTION, false));

        // 3. class Error implements Throwable
        register(out, buildBaseThrowableClass(language, ERROR, true));

        // 4. ErrorException extends Exception
        register(out, buildErrorExceptionClass(language));

        // 5. Standard Exception subclasses
        addSubclass(language, out, "LogicException", EXCEPTION);
        addSubclass(language, out, "BadFunctionCallException", "LogicException");
        addSubclass(language, out, "BadMethodCallException", "BadFunctionCallException");
        addSubclass(language, out, "DomainException", "LogicException");
        addSubclass(language, out, "InvalidArgumentException", "LogicException");
        addSubclass(language, out, "LengthException", "LogicException");
        addSubclass(language, out, "OutOfRangeException", "LogicException");

        addSubclass(language, out, "RuntimeException", EXCEPTION);
        addSubclass(language, out, "OutOfBoundsException", "RuntimeException");
        addSubclass(language, out, "OverflowException", "RuntimeException");
        addSubclass(language, out, "RangeException", "RuntimeException");
        addSubclass(language, out, "UnderflowException", "RuntimeException");
        addSubclass(language, out, "UnexpectedValueException", "RuntimeException");
        addSubclass(language, out, "JsonException", EXCEPTION);
        addSubclass(language, out, "SodiumException", EXCEPTION);
        addSubclass(language, out, "mysqli_sql_exception", "RuntimeException");
        addSubclass(language, out, "DateException", EXCEPTION);
        addSubclass(language, out, "DateInvalidTimeZoneException", "DateException");
        addSubclass(language, out, "DateInvalidOperationException", "DateException");
        addSubclass(language, out, "DateMalformedStringException", "DateException");
        addSubclass(language, out, "DateMalformedIntervalStringException", "DateException");
        addSubclass(language, out, "DateMalformedPeriodStringException", "DateException");

        // 6. Standard Error subclasses
        addSubclass(language, out, "TypeError", ERROR);
        addSubclass(language, out, "ArgumentCountError", "TypeError");
        addSubclass(language, out, "ValueError", ERROR);
        addSubclass(language, out, "ArithmeticError", ERROR);
        addSubclass(language, out, "DivisionByZeroError", "ArithmeticError");
        addSubclass(language, out, "AssertionError", ERROR);
        addSubclass(language, out, "CompileError", ERROR);
        addSubclass(language, out, "ParseError", "CompileError");
        addSubclass(language, out, "UnhandledMatchError", ERROR);
        addSubclass(language, out, "DateError", ERROR);
        addSubclass(language, out, "DateObjectError", "DateError");
        addSubclass(language, out, "DateRangeError", "DateError");

        Map<String, PhpClassTemplate.MethodDecl> hashMethods = new LinkedHashMap<>();
        addMethod(language, hashMethods, "__construct", Visibility.PRIVATE, true, (ctx, self, args) -> {
            throw new PhpError("Cannot directly construct HashContext, use hash_init() instead");
        });
        register(out, new PhpClassTemplate(language.nextTemplateId(), "HashContext", false, false, true, false,
                null, List.of(), List.of(), List.of(), hashMethods));

        return Map.copyOf(out);
    }

    private static void register(Map<String, PhpClassTemplate> out, PhpClassTemplate tpl) {
        out.put(tpl.lowerName(), tpl);
    }

    private static void addSubclass(PhpLanguage language, Map<String, PhpClassTemplate> out,
                                    String name, String parent) {
        register(out, new PhpClassTemplate(
                language.nextTemplateId(), name, false, false, false, false,
                parent, List.of(), List.of(), List.of(), Map.of()));
    }

    private static void addAbstractMethod(PhpLanguage language,
                                          Map<String, PhpClassTemplate.MethodDecl> map,
                                          String name) {
        String lower = PhpStrings.lower(name);
        map.put(lower, new PhpClassTemplate.MethodDecl(
                name, lower, language.methodId(name), Visibility.PUBLIC, false, true, false, null));
    }

    private static void addMethod(PhpLanguage language,
                                  Map<String, PhpClassTemplate.MethodDecl> map,
                                  String name, Visibility vis, boolean isFinal, BuiltinMethod impl) {
        String lower = PhpStrings.lower(name);
        RootNode root = new BuiltinMethodRootNode(language, name, impl);
        map.put(lower, new PhpClassTemplate.MethodDecl(
                name, lower, language.methodId(name), vis, false, false, isFinal, root.getCallTarget()));
    }

    private static PhpClassTemplate buildBaseThrowableClass(PhpLanguage language, String className, boolean isError) {
        String traceKey = isError ? ERR_TRACE_KEY : EXC_TRACE_KEY;
        String prevKey = isError ? ERR_PREV_KEY : EXC_PREV_KEY;

        List<PhpClassTemplate.PropertyDecl> props = List.of(
                new PhpClassTemplate.PropertyDecl("message", Visibility.PROTECTED, false, "", null),
                new PhpClassTemplate.PropertyDecl("string", Visibility.PRIVATE, false, "", null),
                new PhpClassTemplate.PropertyDecl("code", Visibility.PROTECTED, false, 0L, null),
                new PhpClassTemplate.PropertyDecl("file", Visibility.PROTECTED, false, "", null),
                new PhpClassTemplate.PropertyDecl("line", Visibility.PROTECTED, false, 0L, null),
                new PhpClassTemplate.PropertyDecl("trace", Visibility.PRIVATE, false, new PhpArray(), null),
                new PhpClassTemplate.PropertyDecl("previous", Visibility.PRIVATE, false, PhpNull.INSTANCE, null));

        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();

        addMethod(language, methods, "__construct", Visibility.PUBLIC, false, (ctx, self, args) -> {
            if (args.length > 3) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        className + "::__construct() expects at most 3 arguments, " + args.length + " given", null);
            }
            String msg = args.length >= 1 ? coerceStringArg(className + "::__construct", 1, "$message", args[0]) : "";
            long code = args.length >= 2 ? coerceIntArg(className + "::__construct", 2, "$code", args[1]) : 0L;
            Object prev = args.length >= 3 ? validatePrevious(ctx, className + "::__construct", 3, args[2]) : PhpNull.INSTANCE;

            DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
            put.execute(self, "message", msg);
            put.execute(self, "code", code);
            put.execute(self, prevKey, prev);
            return PhpNull.INSTANCE;
        });

        addMethod(language, methods, "getMessage", Visibility.PUBLIC, true, (ctx, self, args) -> {
            checkZeroArgs(self, "getMessage", args);
            return PhpOps.toStr(DynamicObject.GetNode.getUncached().execute(self, "message", ""));
        });

        addMethod(language, methods, "getCode", Visibility.PUBLIC, true, (ctx, self, args) -> {
            checkZeroArgs(self, "getCode", args);
            return DynamicObject.GetNode.getUncached().execute(self, "code", 0L);
        });

        addMethod(language, methods, "getFile", Visibility.PUBLIC, true, (ctx, self, args) -> {
            checkZeroArgs(self, "getFile", args);
            return PhpOps.toStr(DynamicObject.GetNode.getUncached().execute(self, "file", ""));
        });

        addMethod(language, methods, "getLine", Visibility.PUBLIC, true, (ctx, self, args) -> {
            checkZeroArgs(self, "getLine", args);
            return PhpOps.toLong(DynamicObject.GetNode.getUncached().execute(self, "line", 0L));
        });

        addMethod(language, methods, "getTrace", Visibility.PUBLIC, true, (ctx, self, args) -> {
            checkZeroArgs(self, "getTrace", args);
            return PhpOps.copyOnAssign(materializeTraceSlot(self, traceKey));
        });

        addMethod(language, methods, "getPrevious", Visibility.PUBLIC, true, (ctx, self, args) -> {
            checkZeroArgs(self, "getPrevious", args);
            return DynamicObject.GetNode.getUncached().execute(self, prevKey, PhpNull.INSTANCE);
        });

        addMethod(language, methods, "getTraceAsString", Visibility.PUBLIC, true, (ctx, self, args) -> {
            checkZeroArgs(self, "getTraceAsString", args);
            Object raw = DynamicObject.GetNode.getUncached().execute(self, traceKey, null);
            return PhpBacktrace.formatTraceValue(raw);
        });

        addMethod(language, methods, "__toString", Visibility.PUBLIC, false, (ctx, self, args) -> {
            checkZeroArgs(self, "__toString", args);
            String formatted = formatChain(self);
            String strKey = isError ? ERR_STRING_KEY : EXC_STRING_KEY;
            DynamicObject.PutNode.getUncached().execute(self, strKey, formatted);
            return formatted;
        });

        addMethod(language, methods, "__clone", Visibility.PRIVATE, true, (ctx, self, args) -> {
            throw new PhpError("Trying to clone an uncloneable object of class " + self.phpClass().name());
        });

        return new PhpClassTemplate(
                language.nextTemplateId(), className, false, false, false, false,
                null, List.of(THROWABLE), props, List.of(), methods);
    }

    private static PhpClassTemplate buildErrorExceptionClass(PhpLanguage language) {
        List<PhpClassTemplate.PropertyDecl> props = List.of(
                new PhpClassTemplate.PropertyDecl("severity", Visibility.PROTECTED, false, 1L, null));

        Map<String, PhpClassTemplate.MethodDecl> methods = new LinkedHashMap<>();

        addMethod(language, methods, "__construct", Visibility.PUBLIC, false, (ctx, self, args) -> {
            if (args.length > 6) {
                throw new PhpError(PhpError.ARGUMENT_COUNT,
                        "ErrorException::__construct() expects at most 6 arguments, " + args.length + " given", null);
            }
            String msg = args.length >= 1 ? coerceStringArg("ErrorException::__construct", 1, "$message", args[0]) : "";
            long code = args.length >= 2 ? coerceIntArg("ErrorException::__construct", 2, "$code", args[1]) : 0L;
            long severity = args.length >= 3 ? coerceIntArg("ErrorException::__construct", 3, "$severity", args[2]) : 1L;
            String file = args.length >= 4 && args[3] != PhpNull.INSTANCE
                    ? coerceStringArg("ErrorException::__construct", 4, "$filename", args[3]) : null;
            Long line = args.length >= 5 && args[4] != PhpNull.INSTANCE
                    ? coerceIntArg("ErrorException::__construct", 5, "$line", args[4]) : null;
            Object prev = args.length >= 6 ? validatePrevious(ctx, "ErrorException::__construct", 6, args[5]) : PhpNull.INSTANCE;

            DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
            put.execute(self, "message", msg);
            put.execute(self, "code", code);
            put.execute(self, "severity", severity);
            if (file != null) {
                put.execute(self, "file", file);
            }
            if (line != null) {
                put.execute(self, "line", line);
            }
            put.execute(self, EXC_PREV_KEY, prev);
            return PhpNull.INSTANCE;
        });

        addMethod(language, methods, "getSeverity", Visibility.PUBLIC, true, (ctx, self, args) -> {
            checkZeroArgs(self, "getSeverity", args);
            return PhpOps.toLong(DynamicObject.GetNode.getUncached().execute(self, "severity", 1L));
        });

        return new PhpClassTemplate(
                language.nextTemplateId(), "ErrorException", false, false, false, false,
                EXCEPTION, List.of(), props, List.of(), methods);
    }

    private static void checkZeroArgs(PhpObject self, String method, Object[] args) {
        if (args.length > 0) {
            String base = isErrorSubclass(self.phpClass()) ? ERROR : EXCEPTION;
            throw new PhpError(PhpError.ARGUMENT_COUNT,
                    base + "::" + method + "() expects exactly 0 arguments, " + args.length + " given", null);
        }
    }

    private static String coerceStringArg(String fn, int idx, String param, Object v) {
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Long || v instanceof Double || v instanceof Boolean) {
            return PhpOps.toStr(v);
        }
        if (v instanceof PhpObject o && o.phpClass().toStringMethod() != null) {
            return PhpOps.toStr(o);
        }
        throw new PhpError(PhpError.TYPE_ERROR,
                fn + "(): Argument #" + idx + " (" + param + ") must be of type string, " + typeDesc(v) + " given",
                null);
    }

    private static long coerceIntArg(String fn, int idx, String param, Object v) {
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof Double d && !Double.isNaN(d) && !Double.isInfinite(d)) {
            return (long) d.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        if (v instanceof String s && PhpOps.isNumericString(s)) {
            return PhpOps.toLong(s);
        }
        throw new PhpError(PhpError.TYPE_ERROR,
                fn + "(): Argument #" + idx + " (" + param + ") must be of type int, " + typeDesc(v) + " given",
                null);
    }

    private static Object validatePrevious(PhpContext ctx, String fn, int idx, Object v) {
        if (v == PhpNull.INSTANCE) {
            return PhpNull.INSTANCE;
        }
        if (v instanceof PhpObject o && isThrowable(ctx, o.phpClass())) {
            return o;
        }
        throw new PhpError(PhpError.TYPE_ERROR,
                fn + "(): Argument #" + idx + " ($previous) must be of type ?Throwable, " + typeDesc(v) + " given",
                null);
    }

    private static String typeDesc(Object v) {
        if (v instanceof PhpObject o) {
            return o.phpClass().name();
        }
        return PhpOps.typeName(v);
    }

    public static boolean isThrowable(PhpContext ctx, PhpClass cls) {
        if (cls == null) {
            return false;
        }
        for (PhpClass c = cls; c != null; c = c.parent()) {
            String n = c.name();
            if (EXCEPTION.equalsIgnoreCase(n) || ERROR.equalsIgnoreCase(n)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isErrorSubclass(PhpClass cls) {
        for (PhpClass c = cls; c != null; c = c.parent()) {
            if (ERROR.equalsIgnoreCase(c.name())) {
                return true;
            }
        }
        return false;
    }

    public static String traceStorageKey(PhpClass cls) {
        return isErrorSubclass(cls) ? ERR_TRACE_KEY : EXC_TRACE_KEY;
    }

    public static String previousStorageKey(PhpClass cls) {
        return isErrorSubclass(cls) ? ERR_PREV_KEY : EXC_PREV_KEY;
    }

    /**
     * Populates {@code $file}, {@code $line} and lazy {@code $trace} on a newly allocated
     * exception/error object BEFORE {@code __construct} runs, matching Zend's object creation
     * handler.
     */
    @TruffleBoundary
    public static void initNewThrowable(PhpObject obj, Node newLocation) {
        String file = PhpBacktrace.sourcePath(newLocation);
        long line = PhpBacktrace.sourceLine(newLocation);
        PhpBacktrace.LazyTrace trace = PhpBacktrace.capture(newLocation, false);
        DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
        put.execute(obj, "file", file);
        put.execute(obj, "line", line);
        put.execute(obj, traceStorageKey(obj.phpClass()), trace);
    }

    /** Instantiates a built-in exception/error from Java (for engine errors or JSON_THROW_ON_ERROR). */
    @TruffleBoundary
    public static PhpObject createBuiltinThrowable(PhpContext ctx, String className, String message, long code,
                                                   String file, int line, PhpBacktrace.LazyTrace trace) {
        PhpClass cls = ctx.lookupClass(className);
        if (cls == null) {
            cls = ctx.lookupClass(ERROR);
        }
        PhpObject obj = new PhpObject(cls, ctx.nextObjectId());
        cls.populateDefaults(obj);
        DynamicObject.PutNode put = DynamicObject.PutNode.getUncached();
        put.execute(obj, "message", message != null ? message : "");
        put.execute(obj, "code", code);
        put.execute(obj, "file", file != null ? file : "");
        put.execute(obj, "line", (long) line);
        put.execute(obj, traceStorageKey(cls), trace != null ? trace : new PhpBacktrace.LazyTrace(new PhpBacktrace.FrameEntry[0]));
        return obj;
    }

    /** Materializes a {@link PhpBacktrace.LazyTrace} in the object's trace slot into a {@link PhpArray}. */
    @TruffleBoundary
    public static PhpArray materializeTraceSlot(PhpObject obj, String traceKey) {
        Object val = DynamicObject.GetNode.getUncached().execute(obj, traceKey, null);
        if (val instanceof PhpBacktrace.LazyTrace lt) {
            PhpArray arr = lt.toPhpArray();
            DynamicObject.PutNode.getUncached().execute(obj, traceKey, arr);
            return arr;
        }
        if (val instanceof PhpArray arr) {
            return arr;
        }
        return new PhpArray();
    }

    /** Links {@code pending} at the tail of {@code replacement}'s {@code previous} chain if not already present. */
    @TruffleBoundary
    public static void linkPrevious(PhpObject replacement, PhpObject pending) {
        if (replacement == pending || pending == null) {
            return;
        }
        DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
        PhpObject cur = replacement;
        int guard = 0;
        while (guard++ < 256) {
            if (cur == pending) {
                return;
            }
            String pk = previousStorageKey(cur.phpClass());
            Object next = get.execute(cur, pk, PhpNull.INSTANCE);
            if (next instanceof PhpObject nextObj) {
                cur = nextObj;
            } else {
                DynamicObject.PutNode.getUncached().execute(cur, pk, pending);
                return;
            }
        }
    }

    /** Formats a single throwable or a chain of {@code previous} throwables in PHP's {@code __toString()} format. */
    @TruffleBoundary
    public static String formatChain(PhpObject top) {
        List<PhpObject> chain = collectChainOldestFirst(top);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) {
                sb.append(LF).append(LF).append("Next ");
            }
            appendSingleThrowable(sb, chain.get(i));
        }
        return sb.toString();
    }

    /** Formats an uncaught throwable for {@code PhpEngine.fail}, matching php-cli output byte-for-byte. */
    @TruffleBoundary
    public static String formatUncaught(PhpObject top) {
        List<PhpObject> chain = collectChainOldestFirst(top);
        StringBuilder sb = new StringBuilder("PHP Fatal error:  Uncaught ");
        for (int i = 0; i < chain.size(); i++) {
            if (i > 0) {
                sb.append(LF).append(LF).append("Next ");
            }
            appendSingleThrowable(sb, chain.get(i));
        }
        DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
        String topFile = PhpOps.toStr(get.execute(top, "file", ""));
        long topLine = PhpOps.toLong(get.execute(top, "line", 0L));
        sb.append(LF).append("  thrown in ").append(topFile).append(" on line ").append(topLine);
        return sb.toString();
    }

    private static List<PhpObject> collectChainOldestFirst(PhpObject top) {
        List<PhpObject> rev = new ArrayList<>(4);
        DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
        PhpObject cur = top;
        int guard = 0;
        while (cur != null && guard++ < 256) {
            rev.add(cur);
            Object prev = get.execute(cur, previousStorageKey(cur.phpClass()), PhpNull.INSTANCE);
            cur = prev instanceof PhpObject po ? po : null;
        }
        List<PhpObject> out = new ArrayList<>(rev.size());
        for (int i = rev.size() - 1; i >= 0; i--) {
            out.add(rev.get(i));
        }
        return out;
    }

    private static void appendSingleThrowable(StringBuilder sb, PhpObject ex) {
        DynamicObject.GetNode get = DynamicObject.GetNode.getUncached();
        String cls = ex.phpClass().name();
        String msg = PhpOps.toStr(get.execute(ex, "message", ""));
        String file = PhpOps.toStr(get.execute(ex, "file", ""));
        long line = PhpOps.toLong(get.execute(ex, "line", 0L));
        Object traceVal = get.execute(ex, traceStorageKey(ex.phpClass()), null);

        sb.append(cls);
        if (!msg.isEmpty()) {
            sb.append(": ").append(msg);
        }
        sb.append(" in ").append(file).append(':').append(line)
                .append(LF).append("Stack trace:").append(LF)
                .append(PhpBacktrace.formatTraceValue(traceVal));
    }

    @FunctionalInterface
    public interface BuiltinMethod {
        Object invoke(PhpContext ctx, PhpObject self, Object[] args);
    }

    public static final class BuiltinMethodRootNode extends RootNode {
        private final String name;
        private final BuiltinMethod impl;

        public BuiltinMethodRootNode(PhpLanguage language, String name, BuiltinMethod impl) {
            super(language);
            this.name = name;
            this.impl = impl;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] raw = frame.getArguments();
            PhpObject self = raw[0] instanceof PhpObject o ? o : null;
            Object[] callArgs = raw.length > 2
                    ? java.util.Arrays.copyOfRange(raw, 2, raw.length)
                    : new Object[0];
            return runBoundary(PhpContext.get(this), self, callArgs);
        }

        @TruffleBoundary
        private Object runBoundary(PhpContext ctx, PhpObject self, Object[] callArgs) {
            PhpStrings.checkArguments(callArgs, name + "()");
            Object value = impl.invoke(ctx, self, callArgs);
            if (PhpStrings.CHECK) {
                PhpStrings.checkValue(value, (self != null ? self.phpClass().name() + "::" : "static ") + name + "()");
            }
            return value;
        }

        @Override
        public String getName() {
            return name;
        }
    }
}
