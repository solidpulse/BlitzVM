package dev.phpruntime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.PhpCallable;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpClosure;
import dev.phpruntime.runtime.PhpConstants;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpFunction;
import dev.phpruntime.runtime.PhpNull;
import dev.phpruntime.runtime.PhpObject;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpReference;
import dev.phpruntime.runtime.PhpRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final Map<String, PhpClass> classes = new HashMap<>();
    private PhpClass[] classesById = new PhpClass[128];
    private final Map<String, Object> constants = new java.util.LinkedHashMap<>();
    private final Set<String> includedFiles = new HashSet<>();
    private long nextObjectId = 1;
    private long nextAnonymousId = 1;
    private final PhpClass stdClass;
    private final PhpClass closureClass;
    private record ShutdownEntry(Object callable, Object[] args) {
    }

    private record ErrorHandlerEntry(Object callable, long errorLevels) {
    }

    private final List<Object> autoloaders = new ArrayList<>();
    private final Set<String> autoloadingInProgress = new HashSet<>();
    private final List<ShutdownEntry> shutdownFunctions = new ArrayList<>();
    private final Deque<ErrorHandlerEntry> errorHandlers = new ArrayDeque<>();
    private final IdentityHashMap<Object, Map<String, PhpReference>> staticLocals = new IdentityHashMap<>();
    private boolean inErrorHandler;
    private final StringBuilder output = new StringBuilder();
    private final PrintStream err;
    private PhpRequest request;
    /** Per-request state owned by builtins (strtok position, default timezone); see builtins.ContextState. */
    private Object builtinState;

    PhpContext(PhpLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
        this.err = new PrintStream(env.err(), true, StandardCharsets.UTF_8);
        this.stdClass = declareClass(language.stdClassTemplate());
        this.closureClass = declareClass(language.closureTemplate());
    }

    public PhpClass stdClass() {
        return stdClass;
    }

    public PhpClass closureClass() {
        return closureClass;
    }

    public PhpObject newClosure(PhpClosure closure) {
        return new PhpObject(closureClass, nextObjectId(), closure);
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

    public Object builtinState() {
        return builtinState;
    }

    public void setBuiltinState(Object state) {
        builtinState = state;
    }

    // ------------------------------------------------------------ globals

    @TruffleBoundary
    public Object readGlobal(String name) {
        Object v = globals.get(name);
        return v instanceof PhpReference ref ? ref.value : v;
    }

    @TruffleBoundary
    public void writeGlobal(String name, Object value) {
        Object cur = globals.get(name);
        if (cur instanceof PhpReference ref) {
            ref.value = value;
        } else {
            globals.put(name, value);
        }
    }

    @TruffleBoundary
    public boolean hasGlobal(String name) {
        Object v = readGlobal(name);
        return v != null && v != PhpNull.INSTANCE;
    }

    @TruffleBoundary
    public PhpReference globalRef(String name) {
        Object cur = globals.get(name);
        if (cur instanceof PhpReference ref) {
            return ref;
        }
        PhpReference ref = new PhpReference(cur == null ? PhpNull.INSTANCE : cur);
        globals.put(name, ref);
        return ref;
    }

    @TruffleBoundary
    public PhpReference getStaticLocal(Object ownerKey, String name) {
        Map<String, PhpReference> map = staticLocals.get(ownerKey);
        return map == null ? null : map.get(name);
    }

    @TruffleBoundary
    public PhpReference initStaticLocal(Object ownerKey, String name, Object initialValue) {
        Map<String, PhpReference> map = staticLocals.computeIfAbsent(ownerKey, k -> new HashMap<>());
        PhpReference ref = map.get(name);
        if (ref == null) {
            ref = new PhpReference(initialValue);
            map.put(name, ref);
        }
        return ref;
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

    // ------------------------------------------------------------ classes

    public PhpClass classById(int id) {
        PhpClass[] table = classesById;
        return id < table.length ? table[id] : null;
    }

    @TruffleBoundary
    public PhpClass lookupClass(String name) {
        return classes.get(name.toLowerCase());
    }

    /** class_alias(): makes an existing class reachable under another name in this request. */
    @TruffleBoundary
    public boolean aliasClass(String alias, PhpClass cls) {
        String lower = alias.toLowerCase();
        if (classes.containsKey(lower)) {
            return false;
        }
        classes.put(lower, cls);
        int id = language.classId(lower);
        if (id >= classesById.length) {
            classesById = Arrays.copyOf(classesById, Math.max(id + 1, classesById.length * 2));
        }
        classesById[id] = cls;
        return true;
    }

    /** Constants defined with define() or const in this request, in definition order. */
    public Map<String, Object> userConstants() {
        return java.util.Collections.unmodifiableMap(constants);
    }

    @TruffleBoundary
    public PhpClass lookupOrAutoloadClass(String name) {
        String lower = name.toLowerCase();
        PhpClass cls = classes.get(lower);
        if (cls != null || autoloaders.isEmpty() || !autoloadingInProgress.add(lower)) {
            return cls;
        }
        try {
            for (Object cbVal : List.copyOf(autoloaders)) {
                PhpCallable cb = PhpCallable.resolveOrNull(this, cbVal);
                if (cb != null) {
                    cb.call(this, null, null, name);
                    cls = classes.get(lower);
                    if (cls != null) {
                        return cls;
                    }
                }
            }
            return null;
        } finally {
            autoloadingInProgress.remove(lower);
        }
    }

    @TruffleBoundary
    public boolean registerAutoload(Object callable, boolean prepend) {
        for (Object existing : autoloaders) {
            if (sameCallback(existing, callable)) {
                return true;
            }
        }
        if (prepend) {
            autoloaders.add(0, callable);
        } else {
            autoloaders.add(callable);
        }
        return true;
    }

    @TruffleBoundary
    public boolean unregisterAutoload(Object callable) {
        for (int i = 0; i < autoloaders.size(); i++) {
            if (sameCallback(autoloaders.get(i), callable)) {
                autoloaders.remove(i);
                return true;
            }
        }
        return false;
    }

    @TruffleBoundary
    public PhpArray autoloadFunctions() {
        PhpArray out = new PhpArray();
        for (Object cb : autoloaders) {
            out.append(PhpOps.copyOnAssign(cb));
        }
        return out;
    }

    private static boolean sameCallback(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a instanceof String sa && b instanceof String sb) {
            return sa.equalsIgnoreCase(sb);
        }
        if (a instanceof PhpArray aa && b instanceof PhpArray ab) {
            return PhpOps.strictEquals(aa, ab);
        }
        return Objects.equals(a, b);
    }

    @TruffleBoundary
    public boolean canHoistClass(PhpClassTemplate template) {
        if (template.parentName() != null && lookupClass(template.parentName()) == null) {
            return false;
        }
        for (String iface : template.interfaceNames()) {
            if (lookupClass(iface) == null) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public PhpClass declareClass(PhpClassTemplate template) {
        String lower = template.lowerName();
        if (classes.containsKey(lower)) {
            String kind = template.isInterface() ? "interface" : "class";
            throw new PhpError("Cannot declare " + kind + " " + template.name() + ", because the name is already in use");
        }
        PhpClass parent = null;
        if (template.parentName() != null) {
            parent = lookupOrAutoloadClass(template.parentName());
            if (parent == null) {
                String kind = template.isInterface() ? "Interface" : "Class";
                throw new PhpError(kind + " \"" + template.parentName() + "\" not found");
            }
            if (!template.isInterface() && parent.isInterface()) {
                throw new PhpError(template.name() + " cannot extend interface " + parent.name());
            }
            if (!template.isInterface() && parent.isFinal()) {
                throw new PhpError("Class " + template.name() + " cannot extend final class " + parent.name());
            }
        }
        List<PhpClass> ifaces = new ArrayList<>(template.interfaceNames().size());
        for (String ifaceName : template.interfaceNames()) {
            PhpClass iface = lookupOrAutoloadClass(ifaceName);
            if (iface == null) {
                throw new PhpError("Interface \"" + ifaceName + "\" not found");
            }
            if (!iface.isInterface()) {
                throw new PhpError(template.name() + " cannot implement " + iface.name() + " - it is not an interface");
            }
            ifaces.add(iface);
        }
        PhpClass cls = new PhpClass(language, template, parent, ifaces);
        classes.put(lower, cls);
        int id = language.classId(lower);
        if (id >= classesById.length) {
            classesById = Arrays.copyOf(classesById, Math.max(id + 1, classesById.length * 2));
        }
        classesById[id] = cls;
        cls.initStaticProperties();
        return cls;
    }

    public long nextObjectId() {
        return nextObjectId++;
    }

    public long nextAnonymousId() {
        return nextAnonymousId++;
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
        int sep = name.indexOf("::");
        if (sep > 0) {
            PhpClass cls = lookupClass(name.substring(0, sep));
            return cls != null && cls.lookupConstant(name.substring(sep + 2)) != null;
        }
        return PhpConstants.lookupBuiltin(name) != null || constants.containsKey(name);
    }

    @TruffleBoundary
    public Object readConstant(String name) {
        int sep = name.indexOf("::");
        if (sep > 0) {
            PhpClass cls = lookupClass(name.substring(0, sep));
            return cls == null ? null : cls.lookupConstant(name.substring(sep + 2));
        }
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

    /** One ob_start() level: its buffered text and, for ob_list_handlers, its handler. */
    public static final class OutputBuffer {
        public final StringBuilder text = new StringBuilder();
        public Object handler;
        public String handlerName = "default output handler";
    }

    /** ob_start() levels, innermost last. Empty means echo goes straight to the response. */
    private final java.util.ArrayList<OutputBuffer> outputBuffers = new java.util.ArrayList<>();
    private boolean outputStarted;

    public java.util.List<OutputBuffer> outputBuffers() {
        return outputBuffers;
    }

    /** Appends to the innermost output buffer, or to the response when none is open. */
    @TruffleBoundary
    public void echo(String s) {
        if (!outputBuffers.isEmpty()) {
            outputBuffers.get(outputBuffers.size() - 1).text.append(s);
            return;
        }
        if (!s.isEmpty()) {
            outputStarted = true;
        }
        output.append(s);
        if (output.length() >= FLUSH_THRESHOLD) {
            flushOutput();
        }
    }

    /** headers_sent(): whether any output has gone past the output buffers. */
    public boolean outputStarted() {
        return outputStarted;
    }

    /** End of request: flushes any output buffers still open, innermost first, then the response. */
    @TruffleBoundary
    public void flush() {
        while (!outputBuffers.isEmpty()) {
            OutputBuffer top = outputBuffers.remove(outputBuffers.size() - 1);
            echo(top.text.toString());
        }
        flushOutput();
    }

    /** Writes what has reached the response so far (PHP's flush()); open output buffers stay open. */
    @TruffleBoundary
    public void flushOutput() {
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
    public void registerShutdownFunction(Object callable, Object[] args) {
        shutdownFunctions.add(new ShutdownEntry(callable, args));
    }

    @TruffleBoundary
    public void runShutdownFunctions() {
        for (int i = 0; i < shutdownFunctions.size(); i++) {
            ShutdownEntry entry = shutdownFunctions.get(i);
            PhpCallable cb = PhpCallable.resolveOrNull(this, entry.callable());
            if (cb != null) {
                try {
                    cb.call(this, null, null, entry.args());
                } catch (ControlFlow.Exit e) {
                    break;
                }
            }
        }
    }

    @TruffleBoundary
    public Object setErrorHandler(Object callable, long errorLevels) {
        ErrorHandlerEntry prev = errorHandlers.peek();
        errorHandlers.push(new ErrorHandlerEntry(callable, errorLevels));
        return prev == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(prev.callable());
    }

    @TruffleBoundary
    public boolean restoreErrorHandler() {
        if (!errorHandlers.isEmpty()) {
            errorHandlers.pop();
        }
        return true;
    }

    @TruffleBoundary
    public void warning(String message, int line) {
        String file = request().scriptFilename;
        ErrorHandlerEntry handler = errorHandlers.peek();
        if (handler != null && handler.callable() != PhpNull.INSTANCE && !inErrorHandler
                && (handler.errorLevels() & 2L) != 0) {
            PhpCallable cb = PhpCallable.resolveOrNull(this, handler.callable());
            if (cb != null) {
                inErrorHandler = true;
                try {
                    Object handled = cb.call(this, null, null, 2L, message, file, (long) line);
                    if (!Boolean.FALSE.equals(handled)) {
                        return;
                    }
                } finally {
                    inErrorHandler = false;
                }
            }
        }
        err.println("PHP Warning:  " + message + (file.isEmpty() ? "" : " in " + file) + (line > 0 ? " on line " + line : ""));
    }
}
