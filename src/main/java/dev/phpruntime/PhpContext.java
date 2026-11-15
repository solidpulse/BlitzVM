package dev.phpruntime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.runtime.PhpConstants;
import dev.phpruntime.runtime.PhpFunction;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-execution state: the global symbol table, the function table and the output
 * buffer. One polyglot Context (and therefore one PhpContext) is created per HTTP
 * request, which gives PHP's shared-nothing request model for free.
 */
public final class PhpContext {

    private static final ContextReference<PhpContext> REFERENCE = ContextReference.create(PhpLanguage.class);
    private static final int FLUSH_THRESHOLD = 8192;

    private final PhpLanguage language;
    private final TruffleLanguage.Env env;
    private final Map<String, Object> globals = new HashMap<>();
    private final Map<String, PhpFunction> functions = new HashMap<>();
    private PhpFunction[] functionsById = new PhpFunction[256];
    private final Map<String, Object> constants = new HashMap<>();
    private final Set<String> includedFiles = new HashSet<>();
    private final StringBuilder output = new StringBuilder();
    private final PrintStream err;
    private PhpRequest request;

    PhpContext(PhpLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
        this.err = new PrintStream(env.err(), true, StandardCharsets.UTF_8);
    }

    public static PhpContext get(Node node) {
        return REFERENCE.get(node);
    }

    public TruffleLanguage.Env env() {
        return env;
    }

    public PhpLanguage language() {
        return language;
    }

    /** The request this execution serves; a CLI run gets an empty request. */
    public PhpRequest request() {
        if (request == null) {
            request = PhpRequest.current();
        }
        return request;
    }

    // ------------------------------------------------------------ globals

    @TruffleBoundary
    public Object readGlobal(String name) {
        return globals.get(name);
    }

    @TruffleBoundary
    public void writeGlobal(String name, Object value) {
        globals.put(name, value);
    }

    @TruffleBoundary
    public boolean hasGlobal(String name) {
        Object v = globals.get(name);
        return v != null && v != PhpNull.INSTANCE;
    }

    @TruffleBoundary
    public void unsetGlobal(String name) {
        globals.remove(name);
    }

    // ------------------------------------------------------------ functions

    @TruffleBoundary
    public PhpFunction lookupFunction(String name) {
        return functions.get(name.toLowerCase());
    }

    @TruffleBoundary
    public void declareFunction(PhpFunction fn) {
        if (Builtins.lookup(fn.name()) != null || functions.putIfAbsent(fn.name().toLowerCase(), fn) != null) {
            throw new dev.phpruntime.runtime.PhpError("Cannot redeclare function " + fn.name() + "()");
        }
        int id = language.functionId(fn.name());
        if (id >= functionsById.length) {
            functionsById = java.util.Arrays.copyOf(functionsById, Math.max(id + 1, functionsById.length * 2));
        }
        functionsById[id] = fn;
    }

    /** The function with this {@link PhpLanguage#functionId id} in this context, or null. Cheap enough for every call. */
    public PhpFunction functionById(int id) {
        PhpFunction[] table = functionsById;
        return id < table.length ? table[id] : null;
    }

    // ------------------------------------------------------------ constants

    @TruffleBoundary
    public boolean defineConstant(String name, Object value, int line) {
        if (PhpConstants.lookupBuiltin(name) != null || constants.containsKey(name)) {
            warning("Constant " + name + " already defined", line);
            return false;
        }
        constants.put(name, value);
        return true;
    }

    @TruffleBoundary
    public boolean hasConstant(String name) {
        return PhpConstants.lookupBuiltin(name) != null || constants.containsKey(name);
    }

    @TruffleBoundary
    public Object readConstant(String name) {
        Object builtin = PhpConstants.lookupBuiltin(name);
        if (builtin != null) {
            return builtin;
        }
        return constants.get(name);
    }

    // ------------------------------------------------------------ includes

    @TruffleBoundary
    public boolean isIncluded(String canonicalPath) {
        return includedFiles.contains(canonicalPath);
    }

    @TruffleBoundary
    public void markIncluded(String canonicalPath) {
        includedFiles.add(canonicalPath);
    }

    // ------------------------------------------------------------ output

    @TruffleBoundary
    public void echo(String s) {
        output.append(s);
        if (output.length() >= FLUSH_THRESHOLD) {
            flush();
        }
    }

    @TruffleBoundary
    public void flush() {
        if (output.isEmpty()) {
            return;
        }
        try {
            OutputStream out = env.out();
            out.write(output.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new dev.phpruntime.runtime.PhpError("Failed writing output: " + e.getMessage());
        }
        output.setLength(0);
    }

    @TruffleBoundary
    public void warning(String message, int line) {
        String file = request().scriptFilename;
        err.println("PHP Warning:  " + message + (file.isEmpty() ? "" : " in " + file) + (line > 0 ? " on line " + line : ""));
    }
}
