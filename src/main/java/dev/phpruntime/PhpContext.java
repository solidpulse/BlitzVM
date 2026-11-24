package dev.phpruntime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.builtins.Builtins;
import dev.phpruntime.nodes.ControlFlow;
import dev.phpruntime.runtime.PhpArray;
import dev.phpruntime.runtime.CallMode;
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
import dev.phpruntime.runtime.PhpQuery;
import dev.phpruntime.runtime.PhpRequest;
import dev.phpruntime.runtime.PhpStrings;

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
 * Per-request state: the global symbol table, the function table and the output
 * buffer. A reused polyglot context gets a new instance through
 * {@link PhpContextHolder#install()} at the start of every request, which keeps PHP's
 * shared-nothing request model even though the host context is pooled.
 */
public final class PhpContext {

    private static final ContextReference<PhpContextHolder> REFERENCE = ContextReference.create(PhpLanguage.class);
    private static final int FLUSH_THRESHOLD = 8192;

    private final PhpLanguage language;
    private final TruffleLanguage.Env env;
    private PhpReference[] globalsById;
    /** Null until the script uses $GLOBALS or extract(): most requests never allocate it. */
    private Map<String, PhpReference> dynamicGlobals;
    private final Map<String, Integer> globalOrder = new java.util.LinkedHashMap<>();
    /** Null until a declaration reserves a name; a plain request has no reserved globals. */
    private Set<String> reservedGlobals;
    /** Script-declared functions in declaration order; lookups go through {@link #functionsById}. */
    private List<PhpFunction> declaredFunctions;
    private PhpFunction[] functionsById = new PhpFunction[16];
    private final Map<String, PhpClass> classes = new HashMap<>();
    private PhpClass[] classesById;
    /**
     * lookupClass() results keyed by the name exactly as given. Builtins such as is_a() and
     * class_exists() receive the same class-name strings over and over (literals keep their
     * cached hash), so a hit skips lowercasing and rehashing. A found class is checked against
     * {@link #classesById}, so it cannot outlive the class it names. A miss is stored as the
     * {@link #classGeneration} it was seen at: Tempest asks is_a() and class_exists() about
     * names that are not classes (type names, optional integrations) thousands of times per
     * request, and any class declaration makes those entries stale.
     */
    private final HashMap<String, Object> classByExactName = new HashMap<>();
    /** Bumped whenever a class or alias is added to {@link #classes}. */
    private int classGeneration;
    private int missingClassEntries;
    /** Null until a class's static properties or constants are used; the by-id array mirrors it. */
    private IdentityHashMap<PhpClass, PhpClass.RequestState> classStates;
    private final Map<String, Object> constants = new java.util.LinkedHashMap<>();
    /**
     * Names that {@link #hasConstant} has already found. A found constant never becomes
     * undefined and php does not allow redefining one, so a hit stays valid for the request;
     * missing names are not stored because define() can add them later. Keyed as given minus a
     * leading backslash.
     */
    private final HashMap<String, Boolean> foundConstants = new HashMap<>();
    private final Set<String> includedFiles = new HashSet<>();
    private long nextObjectId = 1;
    private long nextEnumObjectId = 1L << 32;
    private long nextAnonymousId = 1;
    private final PhpClass stdClass;
    private final PhpClass closureClass;
    public record ShutdownEntry(Object callable, Object[] args) {
    }

    public record ErrorHandlerEntry(Object callable, long errorLevels) {
    }

    /** Autoload registrations, allocated on the first spl_autoload_register(); null means empty. */
    private List<Object> autoloaders;
    private Set<String> autoloadingInProgress;
    private List<ShutdownEntry> shutdownFunctions;
    private Deque<ErrorHandlerEntry> errorHandlers;
    private Deque<Object> exceptionHandlers;
    private IdentityHashMap<Object, Map<String, PhpReference>> staticLocals;
    private boolean inErrorHandler;
    /** php.ini-production's error_reporting, E_ALL &amp; ~E_DEPRECATED, as ini_get reports. */
    public static final long DEFAULT_ERROR_REPORTING = 22527L;
    /**
     * The levels {@code @expr} leaves enabled while the operand runs: E_ERROR, E_PARSE,
     * E_CORE_ERROR, E_COMPILE_ERROR, E_USER_ERROR and E_RECOVERABLE_ERROR (php 8's 4437).
     */
    public static final long SILENCE_MASK = 4437L;
    /** The error_reporting level: which diagnostics the default handler displays. */
    private long errorReporting = DEFAULT_ERROR_REPORTING;
    /**
     * The "error_reporting" ini entry. It is normally equal to {@link #errorReporting}, but
     * {@code @expr} masks the runtime level only, so ini_get keeps reporting the full level.
     */
    private long errorReportingIni = DEFAULT_ERROR_REPORTING;

    public long errorReporting() {
        return errorReporting;
    }

    public void setErrorReporting(long level) {
        errorReporting = level;
    }

    public long errorReportingIni() {
        return errorReportingIni;
    }

    public void setErrorReportingIni(long level) {
        errorReportingIni = level;
    }
    private final StringBuilder output = new StringBuilder();
    /** Built on first use: a PrintStream allocates a writer, an encoder and two 8 KB buffers. */
    private PrintStream err;
    private PhpRequest request;
    /**
     * The host handler for a long-lived worker context, or null for an ordinary request
     * context. It lives as long as the context, unlike the per-request {@link PhpRequest}.
     */
    private dev.phpruntime.runtime.WorkerHandler workerHandler;
    /** Per-request state owned by builtins (strtok position, default timezone); see builtins.ContextState. */
    private Object builtinState;
    /** Reflection class handles and layout caches, built on first reflection use. */
    private dev.phpruntime.builtins.ReflectionClasses.Handles reflectionHandles;
    private Node currentBuiltinCallSite;
    private String byteStringOrigin;
    private Boolean hostFileSystem;
    /** Absolute host path of the working directory, cached across the file builtins; chdir sets it. */
    private String hostWorkingDirectory;
    private boolean hostWorkingDirectoryKnown;
    /** Set only by the snapshot harness (task 37a); null in ordinary requests. */
    private dev.phpruntime.snapshot.SnapshotRun snapshotRun;
    /** True when a template run was aborted at the boot-include boundary. */
    private boolean snapshotAborted;
    /** True when exit() or an uncaught error ended the request; the host discards the context. */
    private boolean endedAbnormally;

    PhpContext(PhpLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
        // Global ids below this are the language's fixed ones (superglobals, argv, argc); a
        // request that touches a later name grows the table then, not in every constructor.
        this.globalsById = new PhpReference[32];
        // Both classes are linked once per engine; size the table for their ids so the two
        // registrations below do not copy it, without reserving room for every builtin.
        this.classesById = new PhpClass[Math.max(8, Math.max(
                language.classId("stdclass"), language.classId("closure")) + 1)];
        this.stdClass = registerPrelinked(language.stdClassTemplate());
        this.closureClass = registerPrelinked(language.closureTemplate());
    }

    /**
     * Registers stdClass and Closure without going through declaration linking. Their
     * templates are linked the first time by the full path, which keeps the duplicate-name
     * and interface checks; every later request reuses that linked class.
     */
    private PhpClass registerPrelinked(PhpClassTemplate template) {
        PhpClassTemplate.Linked linked = template.linked();
        if (linked != null && linked.matches(null, List.of(), List.of())) {
            PhpClass cls = linked.cls();
            classes.put(template.lowerName(), cls);
            classGeneration++;
            int id = cls.classId();
            if (id >= classesById.length) {
                classesById = Arrays.copyOf(classesById, Math.max(id + 1, classesById.length * 2));
            }
            classesById[id] = cls;
            return cls;
        }
        return declareClassInternal(template, false);
    }

    private PrintStream errStream() {
        PrintStream e = err;
        if (e == null) {
            e = new PrintStream(env.err(), true, StandardCharsets.ISO_8859_1);
            err = e;
        }
        return e;
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
        return REFERENCE.get(node).current();
    }

    public TruffleLanguage.Env env() {
        return env;
    }

    /**
     * Whether file checks (file_exists, is_file, is_dir, is_readable, realpath, includes) can be
     * answered with {@link java.io.File}. True only for the host's default file system, decided
     * once so a sandboxed or virtual file system keeps the TruffleFile path.
     */
    public boolean usesHostFileSystem() {
        Boolean cached = hostFileSystem;
        if (cached == null) {
            cached = dev.phpruntime.runtime.HostFiles.isDefaultHostFileSystem(env);
            hostFileSystem = cached;
        }
        return cached;
    }

    /**
     * The host path that relative PHP paths resolve against, or null when the host file system
     * has no working directory. Cached because every relative file check needs it and
     * {@code env.getCurrentWorkingDirectory()} allocates a fresh path object each time; chdir
     * replaces the value through {@link #setHostWorkingDirectory}.
     */
    public String hostWorkingDirectory() {
        if (!hostWorkingDirectoryKnown) {
            TruffleFile cwd = env.getCurrentWorkingDirectory();
            hostWorkingDirectory = cwd == null ? null : cwd.getPath();
            hostWorkingDirectoryKnown = true;
        }
        return hostWorkingDirectory;
    }

    /** Records the directory chdir just installed, so relative host paths follow it. */
    public void setHostWorkingDirectory(String path) {
        hostWorkingDirectory = path;
        hostWorkingDirectoryKnown = path != null;
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

    /** Binds the host request to this fresh context before its first line runs. */
    public void bindRequest(PhpRequest request) {
        this.request = request;
    }

    /** The worker-mode handler for this context, or null when it is not a worker. */
    public dev.phpruntime.runtime.WorkerHandler workerHandler() {
        return workerHandler;
    }

    public void setWorkerHandler(dev.phpruntime.runtime.WorkerHandler handler) {
        this.workerHandler = handler;
    }

    public Object builtinState() {
        return builtinState;
    }

    public void setBuiltinState(Object state) {
        builtinState = state;
    }

    /** Reflection class handles for this request, created on first use. */
    public dev.phpruntime.builtins.ReflectionClasses.Handles reflectionHandles() {
        dev.phpruntime.builtins.ReflectionClasses.Handles handles = reflectionHandles;
        if (handles == null) {
            handles = new dev.phpruntime.builtins.ReflectionClasses.Handles();
            reflectionHandles = handles;
        }
        return handles;
    }

    /** The name of the builtin running at {@link #currentBuiltinCallSite}, for its trace frame. */
    private String currentBuiltinName;

    public String currentBuiltinName() {
        return currentBuiltinName;
    }

    /** Sets the running builtin's call site and name; returns the previous name to restore. */
    public String enterBuiltin(Node site, String name) {
        String previous = currentBuiltinName;
        currentBuiltinCallSite = site;
        currentBuiltinName = name;
        return previous;
    }

    public void exitBuiltin(Node site, String name) {
        currentBuiltinCallSite = site;
        currentBuiltinName = name;
    }

    public Node currentBuiltinCallSite() {
        return currentBuiltinCallSite;
    }

    public void setCurrentBuiltinCallSite(Node site) {
        currentBuiltinCallSite = site;
    }

    public String byteStringOrigin() {
        return byteStringOrigin;
    }

    public void setByteStringOrigin(String origin) {
        byteStringOrigin = origin;
    }

    // ------------------------------------------------------------ globals

    @TruffleBoundary
    public void reserveGlobals(String[] names) {
        if (names.length == 0) {
            return;
        }
        Set<String> reserved = reservedGlobals;
        if (reserved == null) {
            reserved = new HashSet<>();
            reservedGlobals = reserved;
        }
        for (String name : names) {
            globalOrder.putIfAbsent(name, language.globalId(name));
            reserved.add(name);
        }
    }

    public boolean isReservedGlobal(String name) {
        return reservedGlobals != null && reservedGlobals.contains(name);
    }

    private Map<String, PhpReference> dynamicGlobals() {
        Map<String, PhpReference> m = dynamicGlobals;
        if (m == null) {
            m = new java.util.LinkedHashMap<>();
            dynamicGlobals = m;
        }
        return m;
    }

    @TruffleBoundary
    public void initializeGlobalOrder() {
        globalOrder.clear();
        for (String name : new String[]{"argv", "argc", "_GET", "_POST", "_COOKIE", "_FILES", "_SERVER"}) {
            Integer id = language.lookupGlobalId(name);
            if (id != null && readGlobalById(id) != null) { globalOrder.put(name, id); }
        }
    }

    @TruffleBoundary
    private void exposeAutoGlobal(String name) {
        if (name.equals("_ENV") || name.equals("_REQUEST")) {
            Integer id = language.lookupGlobalId(name);
            if (id != null) { globalOrder.putIfAbsent(name, id); }
        }
    }

    @TruffleBoundary
    public void exposeAutoGlobalById(int id) { exposeAutoGlobal(language.globalName(id)); }

    /** extract can create an ordinary symbol called GLOBALS; literal $GLOBALS remains special. */
    @TruffleBoundary
    public void bindExtractedGlobal(String name, PhpReference cell) {
        if (name.equals("GLOBALS")) { dynamicGlobals().put(name, cell.alias()); }
        else { bindGlobal(name, cell); }
    }

    @TruffleBoundary
    public void writeExtractedGlobal(String name, Object value) {
        if (name.equals("GLOBALS")) {
            PhpReference cell = dynamicGlobals == null ? null : dynamicGlobals.get(name);
            if (cell == null) { dynamicGlobals().put(name, PhpReference.local(value)); }
            else { cell.setValue(value); }
        } else { writeGlobal(name, value); }
    }

    @TruffleBoundary
    public Object readGlobalSymbol(String name) {
        if (name.equals("GLOBALS")) {
            PhpReference cell = dynamicGlobals == null ? null : dynamicGlobals.get(name);
            return cell == null ? null : cell.getValue();
        }
        return readGlobal(name);
    }

    @TruffleBoundary
    public PhpReference globalSymbolRef(String name) {
        if (!name.equals("GLOBALS")) { return globalRef(name); }
        return dynamicGlobals().computeIfAbsent(name, n -> new PhpReference(PhpNull.INSTANCE));
    }

    @TruffleBoundary
    public void unsetGlobalSymbol(String name) {
        if (name.equals("GLOBALS")) { if (dynamicGlobals != null) { dynamicGlobals.remove(name); } }
        else { unsetGlobal(name); }
    }

    public Object readGlobalById(int id) {
        PhpReference[] table = globalsById;
        if (id < table.length) {
            PhpReference cell = table[id];
            return cell == null ? null : cell.getValue();
        }
        return null;
    }

    public void writeGlobalById(int id, Object value) {
        PhpReference[] table = globalsById;
        if (id < table.length) {
            PhpReference cell = table[id];
            if (cell != null) {
                cell.setValue(value);
                return;
            }
        }
        createGlobalCellById(id, value);
    }

    public String appendGlobalById(int id, String suffix, boolean resultNeeded) {
        PhpReference[] table = globalsById;
        PhpReference cell = id < table.length ? table[id] : null;
        if (cell == null) { cell = createGlobalCellById(id, null); }
        return cell.appendString(suffix, resultNeeded);
    }

    @TruffleBoundary
    private PhpReference createGlobalCellById(int id, Object value) {
        if (id >= globalsById.length) {
            globalsById = Arrays.copyOf(globalsById, Math.max(id + 1, globalsById.length * 2));
        }
        PhpReference cell = globalsById[id];
        if (cell == null) {
            cell = PhpReference.local(value);
            globalsById[id] = cell;
            globalOrder.put(language.globalName(id), id);
        } else {
            cell.setValue(value);
        }
        return cell;
    }

    public PhpReference globalRefById(int id) {
        PhpReference[] table = globalsById;
        if (id < table.length) {
            PhpReference cell = table[id];
            if (cell != null) {
                cell.ensureDefined();
                return cell.alias();
            }
        }
        return createGlobalCellById(id, PhpNull.INSTANCE).alias();
    }

    public void bindGlobalById(int id, PhpReference cell) {
        if (id >= globalsById.length) {
            growGlobalsTable(id);
        }
        if (globalsById[id] == null) { recordGlobal(id); }
        globalsById[id] = cell.alias();
    }

    @TruffleBoundary
    private void recordGlobal(int id) { globalOrder.put(language.globalName(id), id); }

    @TruffleBoundary
    private void growGlobalsTable(int id) {
        globalsById = Arrays.copyOf(globalsById, Math.max(id + 1, globalsById.length * 2));
    }

    public void unsetGlobalById(int id) {
        PhpReference[] table = globalsById;
        if (id < table.length) {
            table[id] = null;
            forgetGlobal(id);
        }
    }

    @TruffleBoundary
    private void forgetGlobal(int id) {
        String name = language.globalName(id);
        if (reservedGlobals == null || !reservedGlobals.contains(name)) { globalOrder.remove(name); }
    }

    @TruffleBoundary
    public Object readGlobal(String name) {
        if ("GLOBALS".equals(name)) {
            return buildGlobalsSnapshot();
        }
        Integer id = language.lookupGlobalId(name);
        if (id != null) {
            exposeAutoGlobal(name);
            return readGlobalById(id);
        }
        PhpReference cell = dynamicGlobals == null ? null : dynamicGlobals.get(name);
        return cell == null ? null : cell.getValue();
    }

    /**
     * The global's value without exposing an auto-global ({@code $_ENV}, {@code $_REQUEST}) in
     * the global order. The snapshot layer needs to look at those without changing what the
     * request reports, since {@code $GLOBALS} and the fingerprint see only the order.
     */
    @TruffleBoundary
    public Object peekGlobal(String name) {
        Integer id = language.lookupGlobalId(name);
        if (id != null) {
            PhpReference[] table = globalsById;
            return id < table.length && table[id] != null ? table[id].getValue() : null;
        }
        PhpReference cell = dynamicGlobals == null ? null : dynamicGlobals.get(name);
        return cell == null ? null : cell.getValue();
    }

    @TruffleBoundary
    public void writeGlobal(String name, Object value) {
        if ("GLOBALS".equals(name)) {
            throw PhpError.fatal("$GLOBALS can only be modified using the $GLOBALS[$name] = $value syntax");
        }
        writeGlobalById(language.globalId(name), value);
    }

    @TruffleBoundary
    public boolean hasGlobal(String name) {
        Object v = readGlobal(name);
        return v != null && v != PhpNull.INSTANCE;
    }

    @TruffleBoundary
    public boolean isGlobalDefined(String name) {
        if ("GLOBALS".equals(name)) {
            return false;
        }
        Integer id = language.lookupGlobalId(name);
        if (id != null) {
            PhpReference[] table = globalsById;
            return id < table.length && table[id] != null && table[id].getValue() != null;
        }
        PhpReference cell = dynamicGlobals == null ? null : dynamicGlobals.get(name);
        return cell != null && cell.getValue() != null;
    }

    @TruffleBoundary
    public PhpReference globalRef(String name) {
        if ("GLOBALS".equals(name)) {
            throw PhpError.fatal("Cannot acquire reference to $GLOBALS");
        }
        Integer id = language.lookupGlobalId(name);
        if (id != null) {
            return globalRefById(id);
        }
        PhpReference cell = dynamicGlobals == null ? null : dynamicGlobals.get(name);
        if (cell != null) {
            cell.ensureDefined();
            return cell;
        }
        return globalRefById(language.globalId(name));
    }

    @TruffleBoundary
    public void bindGlobal(String name, PhpReference cell) {
        if ("GLOBALS".equals(name)) {
            throw PhpError.fatal("$GLOBALS can only be modified using the $GLOBALS[$name] = $value syntax");
        }
        bindGlobalById(language.globalId(name), cell);
    }

    @TruffleBoundary
    public PhpArray buildGlobalsSnapshot() {
        PhpArray snap = new PhpArray();
        for (var entry : globalOrder.entrySet()) {
            int i = entry.getValue();
            PhpReference cell = i < globalsById.length ? globalsById[i] : null;
            if (cell != null && cell.getValue() != null) {
                String name = entry.getKey();
                if (name != null && !"GLOBALS".equals(name)) {
                    if (cell.isAliased()) { snap.putRef(name, cell); }
                    else { snap.put(name, PhpOps.copyOnAssign(cell.getValue())); }
                }
            }
        }
        if (dynamicGlobals != null) {
            for (Map.Entry<String, PhpReference> e : dynamicGlobals.entrySet()) {
                PhpReference cell = e.getValue();
                if (cell != null && cell.getValue() != null && !"GLOBALS".equals(e.getKey())) {
                    if (cell.isAliased()) { snap.putRef(e.getKey(), cell); }
                    else { snap.put(e.getKey(), PhpOps.copyOnAssign(cell.getValue())); }
                }
            }
        }
        return snap;
    }

    // ------------------------------------------------------------ worker mode

    private static final String[] WORKER_SUPERGLOBALS =
            {"_GET", "_POST", "_COOKIE", "_FILES", "_REQUEST", "_SERVER", "_ENV"};

    /** The superglobals a worker had before its first request; restored after every request. */
    public static final class Superglobals {
        private final Map<String, Object> values;

        private Superglobals(Map<String, Object> values) {
            this.values = values;
        }
    }

    /** Captures the worker's own superglobal values before the first request replaces them. */
    @TruffleBoundary
    public Superglobals captureSuperglobals() {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (String name : WORKER_SUPERGLOBALS) {
            values.put(name, peekGlobal(name));
        }
        return new Superglobals(values);
    }

    /**
     * Starts one request on a long-lived worker context. Unlike a fresh context, the script's
     * globals, functions, classes and static state stay; only what a request owns is replaced.
     */
    @TruffleBoundary
    public void beginWorkerRequest(PhpRequest req) {
        bindRequest(req);
        // A request starts with no output buffers and a response that has not been sent.
        if (buffers != null) {
            buffers.clear();
        }
        output.setLength(0);
        outputStarted = false;
        PhpArray get = req.queryString.isEmpty() ? toArray(req.get) : PhpQuery.parseQuery(req.queryString);
        String contentType = PhpQuery.header(req.headers, "Content-Type");
        PhpArray post = !req.body.isEmpty() && contentType != null
                && PhpStrings.lower(contentType).startsWith("application/x-www-form-urlencoded")
                ? PhpQuery.parseQuery(req.body) : toArray(req.post);
        writeGlobal("_GET", get);
        writeGlobal("_POST", post);
        PhpArray all = get.share().forWrite();
        for (var e : post.entries()) {
            all.put(e.getKey(), e.getValue());
        }
        writeGlobal("_REQUEST", all);
        PhpArray cookies = PhpQuery.parseCookies(PhpQuery.header(req.headers, "Cookie"));
        writeGlobal("_COOKIE", cookies);
        // FrankenPHP's variables_order is EGPCS with an empty request_order, so cookies are
        // part of $_REQUEST and win over GET and POST for the same name.
        for (var e : cookies.entries()) {
            all.put(e.getKey(), e.getValue());
        }
        writeGlobal("_FILES", new PhpArray());
        PhpArray environment = new PhpArray();
        req.environment.forEach((key, value) -> environment.put(
                PhpStrings.fromJava(key), PhpStrings.fromJava(value)));
        writeGlobal("_ENV", environment);
        writeGlobal("_SERVER", toArray(req.server));
        if (!req.scriptFilename.isEmpty() && !req.server.containsKey("SCRIPT_FILENAME")) {
            PhpArray server = toArray(req.server);
            server.put("SCRIPT_FILENAME", PhpStrings.fromJava(req.scriptFilename));
            writeGlobal("_SERVER", server);
        }
    }

    /**
     * Ends one request on a long-lived worker context: flushes what the request left open,
     * restores the worker's own superglobals and binds the worker's own request again.
     */
    @TruffleBoundary
    public void endWorkerRequest(Superglobals boot, PhpRequest bootRequest) {
        try {
            flush();
        } catch (Throwable ignored) {
            // A cancelled context cannot write anything; the response was already settled.
        }
        if (buffers != null) {
            buffers.clear();
        }
        output.setLength(0);
        outputStarted = false;
        for (Map.Entry<String, Object> e : boot.values.entrySet()) {
            writeGlobal(e.getKey(), e.getValue());
        }
        bindRequest(bootRequest);
    }

    /** Everything the current request produced but has not written yet, without running handlers. */
    @TruffleBoundary
    public String takePendingOutput() {
        StringBuilder sb = new StringBuilder();
        if (buffers != null) {
            for (OutputBuffer b : buffers) {
                sb.append(b.text);
            }
            buffers.clear();
        }
        sb.append(output);
        output.setLength(0);
        return sb.toString();
    }

    /** How many shutdown functions exist now; a worker request runs only the ones added later. */
    public int shutdownFunctionCount() {
        return shutdownFunctions == null ? 0 : shutdownFunctions.size();
    }

    /** Runs the shutdown functions registered since {@code from}, in registration order. */
    @TruffleBoundary
    public void runShutdownFunctionsFrom(int from) {
        if (shutdownFunctions == null) {
            return;
        }
        for (int i = from; i < shutdownFunctions.size(); i++) {
            ShutdownEntry entry = shutdownFunctions.get(i);
            PhpCallable cb = PhpCallable.resolveOrNull(this, entry.callable());
            if (cb != null) {
                try {
                    cb.call(this, null, null, entry.args());
                } catch (ControlFlow.Exit e) {
                    endedAbnormally = true;
                    break;
                }
            }
        }
    }

    /** Drops the request's shutdown functions so the worker's own ones still see script end. */
    public void removeShutdownFunctionsFrom(int from) {
        if (shutdownFunctions != null) {
            while (shutdownFunctions.size() > from) {
                shutdownFunctions.remove(shutdownFunctions.size() - 1);
            }
        }
    }

    private static PhpArray toArray(Map<String, String> m) {
        PhpArray a = new PhpArray();
        m.forEach(a::put);
        return a;
    }

    @TruffleBoundary
    public PhpReference getStaticLocal(Object ownerKey, String name) {
        Map<String, PhpReference> map = staticLocals == null ? null : staticLocals.get(ownerKey);
        return map == null ? null : map.get(name);
    }

    /** The static-local cells belonging to a function root or closure object, for reflection. */
    @TruffleBoundary
    public Map<String, PhpReference> staticLocalsOf(Object ownerKey) {
        return staticLocals == null ? null : staticLocals.get(ownerKey);
    }

    private IdentityHashMap<Object, Map<String, PhpReference>> staticLocals() {
        IdentityHashMap<Object, Map<String, PhpReference>> map = staticLocals;
        if (map == null) {
            map = new IdentityHashMap<>();
            staticLocals = map;
        }
        return map;
    }

    @TruffleBoundary
    public PhpReference initStaticLocal(Object ownerKey, String name, Object initialValue) {
        Map<String, PhpReference> map = staticLocals().computeIfAbsent(ownerKey,
                k -> new java.util.LinkedHashMap<>());
        PhpReference ref = map.get(name);
        if (ref == null) {
            ref = new PhpReference(initialValue);
            map.put(name, ref);
        }
        return ref;
    }

    @TruffleBoundary
    public void copyClosureStatics(PhpObject original, PhpObject copy, boolean share) {
        Map<String, PhpReference> values = staticLocals == null ? null : staticLocals.get(original);
        if (values == null) { return; }
        if (share) {
            staticLocals().put(copy, values);
        } else {
            Map<String, PhpReference> cloned = new java.util.LinkedHashMap<>();
            for (var entry : values.entrySet()) {
                cloned.put(entry.getKey(), new PhpReference(PhpOps.copyOnAssign(entry.getValue().getValue())));
            }
            staticLocals().put(copy, cloned);
        }
    }

    @TruffleBoundary
    public void unsetGlobal(String name) {
        if ("GLOBALS".equals(name)) {
            throw PhpError.fatal("$GLOBALS can only be modified using the $GLOBALS[$name] = $value syntax");
        }
        Integer id = language.lookupGlobalId(name);
        if (id != null) {
            unsetGlobalById(id);
        } else if (dynamicGlobals != null) {
            dynamicGlobals.remove(name);
        }
    }

    // ------------------------------------------------------------ functions

    @TruffleBoundary
    public PhpFunction lookupFunction(String name) {
        String n = name.startsWith("\\") ? name.substring(1) : name;
        int id = language.functionIdIfKnown(PhpStrings.lower(n));
        return id < 0 ? null : functionById(id);
    }

    @TruffleBoundary
    public void declareFunction(PhpFunction fn) {
        int id = fn.id(language);
        if (fn.clashesWithBuiltin() || (id < functionsById.length && functionsById[id] != null)) {
            throw PhpError.fatal("Cannot redeclare function " + fn.name() + "()");
        }
        if (id >= functionsById.length) {
            functionsById = java.util.Arrays.copyOf(functionsById, Math.max(id + 1, functionsById.length * 2));
        }
        functionsById[id] = fn;
        declaredFunctions().add(fn);
        dev.phpruntime.nodes.PhpFunctionRootNode root =
                dev.phpruntime.nodes.PhpFunctionRootNode.of(fn.callTarget().getRootNode());
        if (root != null && root.implicitNullable().length > 0) {
            for (String parameter : root.implicitNullable()) {
                deprecated(root.qualifiedCallableName() + "(): Implicitly marking parameter $" + parameter
                        + " as nullable is deprecated, the explicit nullable type must be used instead", root.line());
            }
        }
    }

    /** The function with this {@link PhpLanguage#functionId id} in this context, or null. Cheap enough for every call. */
    public PhpFunction functionById(int id) {
        PhpFunction[] table = functionsById;
        return id < table.length ? table[id] : null;
    }

    private List<PhpFunction> declaredFunctions() {
        List<PhpFunction> list = declaredFunctions;
        if (list == null) {
            list = new ArrayList<>();
            declaredFunctions = list;
        }
        return list;
    }

    /** Names of script-declared functions (lowercase), in declaration order, for get_defined_functions(). */
    @TruffleBoundary
    public List<String> definedFunctionNames() {
        List<PhpFunction> functions = declaredFunctions;
        if (functions == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>(functions.size());
        for (PhpFunction fn : functions) {
            names.add(fn.lowerName());
        }
        return names;
    }

    // ------------------------------------------------------------ classes

    public PhpClass classById(int id) {
        PhpClass[] table = classesById;
        if (id < table.length) {
            PhpClass cls = table[id];
            if (cls != null) {
                return cls;
            }
        }
        return resolveBuiltinById(id);
    }

    @TruffleBoundary
    private PhpClass resolveBuiltinById(int id) {
        PhpClassTemplate tpl = language.builtinClassTemplateById(id);
        return tpl == null ? null : lookupClass(tpl.lowerName());
    }

    @TruffleBoundary
    public PhpClass lookupClass(String name) {
        Object cached = classByExactName.get(name);
        if (cached instanceof PhpClass hit) {
            int id = hit.classId();
            PhpClass[] table = classesById;
            if (id >= 0 && id < table.length && table[id] == hit) {
                return hit;
            }
        } else if (cached instanceof Integer generation && generation == classGeneration) {
            return null;
        }
        String n = name.startsWith("\\") ? name.substring(1) : name;
        String lower = PhpStrings.lower(n);
        PhpClass found = classes.get(lower);
        if (found == null) {
            PhpClassTemplate builtin = language.builtinClassTemplate(lower);
            if (builtin == null) {
                rememberMissingClass(name);
                return null;
            }
            found = declareClassInternal(builtin, true);
        }
        if (found != null) {
            classByExactName.put(name, found);
        }
        return found;
    }

    private void rememberMissingClass(String name) {
        if (missingClassEntries >= 4096) {
            // Names can come from input; keep the table bounded.
            classByExactName.values().removeIf(v -> v instanceof Integer);
            missingClassEntries = 0;
        }
        if (!(classByExactName.put(name, classGeneration) instanceof Integer)) {
            missingClassEntries++;
        }
    }

    /** class_alias(): makes an existing class reachable under another name in this request. */
    @TruffleBoundary
    public boolean aliasClass(String alias, PhpClass cls) {
        String n = alias.startsWith("\\") ? alias.substring(1) : alias;
        String lower = PhpStrings.lower(n);
        if (classes.containsKey(lower) || language.builtinClassTemplate(lower) != null) {
            return false;
        }
        classes.put(lower, cls);
        classGeneration++;
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

    private List<Object> autoloaders() {
        List<Object> list = autoloaders;
        if (list == null) {
            list = new ArrayList<>();
            autoloaders = list;
        }
        return list;
    }

    private Set<String> autoloadingInProgress() {
        Set<String> set = autoloadingInProgress;
        if (set == null) {
            set = new HashSet<>();
            autoloadingInProgress = set;
        }
        return set;
    }

    @TruffleBoundary
    public PhpClass lookupOrAutoloadClass(String name) {
        String n = name.startsWith("\\") ? name.substring(1) : name;
        PhpClass cls = lookupClass(n);
        if (cls != null || !isValidClassName(n)) {
            return cls;
        }
        String lower = PhpStrings.lower(n);
        List<Object> loaders = autoloaders;
        if (loaders == null || loaders.isEmpty() || !autoloadingInProgress().add(lower)) {
            return cls;
        }
        try {
            for (Object cbVal : List.copyOf(loaders)) {
                PhpCallable cb = PhpCallable.resolveOrNull(this, cbVal);
                if (cb != null) {
                    cb.call(this, null, null, n);
                    cls = lookupClass(n);
                    if (cls != null) {
                        return cls;
                    }
                }
            }
            return null;
        } finally {
            autoloadingInProgress().remove(lower);
        }
    }

    /**
     * A class name php will hand to the autoloaders: identifier bytes separated by backslashes.
     * Names with dots, dashes or other punctuation never trigger autoloading; ReflectionClass()
     * and class_exists() report them as missing instead of including whatever file the loader
     * maps them to.
     */
    private static boolean isValidClassName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean valid = c == '_' || c == '\\'
                    || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c >= 0x80;
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public boolean registerAutoload(Object callable, boolean prepend) {
        List<Object> loaders = autoloaders();
        for (Object existing : loaders) {
            if (sameCallback(existing, callable)) {
                return true;
            }
        }
        if (prepend) {
            loaders.add(0, callable);
        } else {
            loaders.add(callable);
        }
        return true;
    }

    @TruffleBoundary
    public boolean unregisterAutoload(Object callable) {
        if (autoloaders == null) {
            return false;
        }
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
        if (autoloaders == null) {
            return out;
        }
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
            return PhpStrings.lower(sa).equals(PhpStrings.lower(sb));
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
        for (PhpClassTemplate.TraitUse use : template.traitUses()) {
            for (String tn : use.traitNames()) {
                if (lookupClass(tn) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    @TruffleBoundary
    public PhpClass declareClass(PhpClassTemplate template) {
        return declareClassInternal(template, false);
    }

    @TruffleBoundary
    private PhpClass declareClassInternal(PhpClassTemplate template, boolean isBuiltin) {
        String lower = template.lowerName();
        if (classes.containsKey(lower) || (!isBuiltin && language.builtinClassTemplate(lower) != null)) {
            String kind = template.isInterface() ? "interface" : (template.isTrait() ? "trait" : "class");
            throw PhpError.fatal("Cannot declare " + kind + " " + template.name() + ", because the name is already in use");
        }
        PhpClass parent = null;
        if (template.parentName() != null) {
            parent = lookupOrAutoloadClass(template.parentName());
            if (parent == null) {
                String kind = template.isInterface() ? "Interface" : "Class";
                throw new PhpError(kind + " \"" + template.parentName() + "\" not found");
            }
            if (!template.isInterface() && (parent.isInterface() || parent.isTrait())) {
                throw PhpError.fatal(template.name() + " cannot extend "
                        + (parent.isTrait() ? "trait " : "interface ") + parent.name());
            }
            if (!template.isInterface() && parent.isFinal()) {
                throw PhpError.fatal("Class " + template.name() + " cannot extend final class " + parent.name());
            }
        }
        List<PhpClass> ifaces = new ArrayList<>(template.interfaceNames().size() + 2);
        for (String ifaceName : template.interfaceNames()) {
            PhpClass iface = lookupOrAutoloadClass(ifaceName);
            if (iface == null) {
                throw new PhpError("Interface \"" + ifaceName + "\" not found");
            }
            if (!iface.isInterface()) {
                throw PhpError.fatal(template.name() + " cannot implement " + iface.name() + " - it is not an interface");
            }
            if (!isBuiltin && !template.isInterface() && "throwable".equals(iface.template().lowerName())
                    && !dev.phpruntime.runtime.PhpExceptions.isThrowable(this, parent)) {
                throw PhpError.fatal("Class " + template.name() + " cannot implement interface Throwable, extend Exception or Error instead");
            }
            ifaces.add(iface);
        }
        if (template.isEnum()) {
            // Every enum implements UnitEnum, and a backed enum also implements BackedEnum;
            // both come after the interfaces the declaration lists, as php reports them. They
            // are part of what the linked class depends on, so the reuse check below must see
            // them: a cached class built against another request's UnitEnum is a different type.
            PhpClass unitEnum = lookupClass("UnitEnum");
            if (unitEnum != null && !containsClass(ifaces, unitEnum)) {
                ifaces.add(unitEnum);
            }
            PhpClass backedEnum = lookupClass("BackedEnum");
            if (template.enumBackingType() != null && backedEnum != null && !containsClass(ifaces, backedEnum)) {
                ifaces.add(backedEnum);
            }
        }
        Map<String, PhpClass> resolvedTraits = template.traitUses().isEmpty() ? Map.of() : new HashMap<>();
        List<PhpClass> traitOrder = template.traitUses().isEmpty() ? List.of() : new ArrayList<>();
        for (PhpClassTemplate.TraitUse use : template.traitUses()) {
            for (String traitName : use.traitNames()) {
                String tLower = PhpStrings.lower(traitName);
                if (!resolvedTraits.containsKey(tLower)) {
                    PhpClass tr = lookupOrAutoloadClass(traitName);
                    if (tr == null) {
                        throw new PhpError("Trait \"" + traitName + "\" not found");
                    }
                    if (!tr.isTrait()) {
                        throw PhpError.fatal(template.name() + " cannot use " + tr.name() + " - it is not a trait");
                    }
                    resolvedTraits.put(tLower, tr);
                    traitOrder.add(tr);
                }
            }
        }
        // Linking depends only on the template and these resolved classes, so a request that
        // resolves the same ones reuses the class another request built and validated. Two
        // requests can resolve the first one at the same time (a boot snapshot build runs next
        // to live requests), so the check and the link are one step per template: otherwise
        // both create a class and a later request can hold one class object while its
        // interfaces point at the other request's copy, which makes instanceof and is_a lie.
        PhpClassTemplate.Linked linked = template.linked();
        PhpClass cls;
        if (linked != null && linked.matches(parent, ifaces, traitOrder)) {
            cls = linked.cls();
        } else {
            synchronized (template) {
                linked = template.linked();
                if (linked != null && linked.matches(parent, ifaces, traitOrder)) {
                    cls = linked.cls();
                } else {
                    cls = linkClass(template, isBuiltin, parent, ifaces, resolvedTraits);
                    template.setLinked(new PhpClassTemplate.Linked(cls, parent,
                            ifaces.toArray(new PhpClass[0]), traitOrder.toArray(new PhpClass[0])));
                }
            }
        }
        classes.put(lower, cls);
        classGeneration++;
        int id = cls.classId();
        if (id >= classesById.length) {
            classesById = Arrays.copyOf(classesById, Math.max(id + 1, classesById.length * 2));
        }
        classesById[id] = cls;
        if (!isBuiltin && !cls.isInterface() && !cls.isTrait()
                && implementsInterfaceNamed(cls, "Serializable")
                && (cls.lookupMethod("__serialize") == null || cls.lookupMethod("__unserialize") == null)) {
            deprecated(cls.name() + " implements the Serializable interface, which is deprecated."
                    + " Implement __serialize() and __unserialize() instead (or in addition, if support for old PHP"
                    + " versions is necessary)", Math.max(0, template.startLine()));
        }
        return cls;
    }

    private static boolean containsClass(List<PhpClass> classes, PhpClass target) {
        for (PhpClass cls : classes) {
            if (cls == target) {
                return true;
            }
        }
        return false;
    }

    private static boolean implementsInterfaceNamed(PhpClass cls, String name) {
        for (PhpClass iface : cls.interfaces()) {
            if (iface.name().equalsIgnoreCase(name) || implementsInterfaceNamed(iface, name)) {
                return true;
            }
        }
        PhpClass parent = cls.parent();
        return parent != null && implementsInterfaceNamed(parent, name);
    }

    /** Builds and validates the class for one combination of parent, interfaces and traits. */
    private PhpClass linkClass(PhpClassTemplate template, boolean isBuiltin, PhpClass parent,
                               List<PhpClass> ifaces, Map<String, PhpClass> resolvedTraits) {
        if (!isBuiltin && parent != null) {
            for (PhpClassTemplate.MethodDecl m : template.methods().values()) {
                PhpClass.MethodEntry inherited = parent.lookupMethod(m.lowerName());
                if (inherited != null && inherited.isFinal()) {
                    throw PhpError.fatal("Cannot override final method " + inherited.declaringClass().name() + "::" + inherited.name() + "()");
                }
            }
        }
        PhpClass cls = new PhpClass(language, template, parent, ifaces, resolvedTraits);
        if (!template.isInterface() && !template.isTrait()) {
            if (cls.hasSplInterface(dev.phpruntime.runtime.SplInterfaces.ITERATOR)
                    && cls.hasSplInterface(dev.phpruntime.runtime.SplInterfaces.AGGREGATE)) {
                throw PhpError.fatal("Class " + template.name() + " cannot implement both Iterator and IteratorAggregate at the same time");
            }
            if (cls.hasSplInterface(dev.phpruntime.runtime.SplInterfaces.TRAVERSABLE)
                    && !cls.hasSplInterface(dev.phpruntime.runtime.SplInterfaces.ITERATOR)
                    && !cls.hasSplInterface(dev.phpruntime.runtime.SplInterfaces.AGGREGATE)) {
                throw PhpError.fatal("Class " + template.name()
                        + " must implement interface Traversable as part of either Iterator or IteratorAggregate");
            }
            if (cls.toStringMethod() != null
                    && !cls.hasSplInterface(dev.phpruntime.runtime.SplInterfaces.STRINGABLE)) {
                ifaces.add(lookupClass("Stringable"));
                cls = new PhpClass(language, template, parent, ifaces, resolvedTraits);
            }
            if (!isBuiltin && !cls.isAbstract()) {
                validateSplMethods(cls, cls);
            }
        }
        return cls;
    }

    /** Per-class request state by class id, the fast path of {@link #classState}. */
    private PhpClass.RequestState[] classStatesById = new PhpClass.RequestState[16];

    /** This request's state of a shared class (static property values, evaluated constants). */
    public PhpClass.RequestState classState(PhpClass cls) {
        int id = cls.classId();
        PhpClass.RequestState[] byId = classStatesById;
        if (id < byId.length) {
            PhpClass.RequestState state = byId[id];
            // Ids come from class names; the identity check keeps two classes of one name apart.
            if (state != null && state.owner() == cls) {
                return state;
            }
        }
        return classStateSlow(cls);
    }

    @TruffleBoundary
    private PhpClass.RequestState classStateSlow(PhpClass cls) {
        IdentityHashMap<PhpClass, PhpClass.RequestState> states = classStates;
        if (states == null) {
            states = new IdentityHashMap<>();
            classStates = states;
        }
        PhpClass.RequestState state = states.get(cls);
        if (state == null) {
            state = new PhpClass.RequestState(cls, this);
            states.put(cls, state);
        }
        int id = cls.classId();
        if (id >= classStatesById.length) {
            classStatesById = java.util.Arrays.copyOf(classStatesById,
                    Math.max(id + 1, classStatesById.length * 2));
        }
        classStatesById[id] = state;
        return state;
    }

    private void validateSplMethods(PhpClass cls, PhpClass inherited) {
        if (inherited.parent() != null) { validateSplMethods(cls, inherited.parent()); }
        for (PhpClass iface : inherited.interfaces()) {
            for (var entry : iface.template().methodSignatures().entrySet()) {
                PhpClass.MethodEntry implementation = cls.lookupMethod(entry.getKey());
                if (implementation == null || implementation.callTarget() == null) {
                    throw PhpError.fatal("Class " + cls.name() + " contains 1 abstract method and must therefore be declared abstract or implement the remaining methods ("
                            + iface.name() + "::" + iface.template().methods().get(entry.getKey()).name() + ")");
                }
                boolean enumInterface = iface.name().equalsIgnoreCase("UnitEnum")
                        || iface.name().equalsIgnoreCase("BackedEnum");
                if (implementation.isStatic() && !enumInterface) {
                    throw PhpError.fatal("Cannot make non static method " + iface.name() + "::" + implementation.name()
                            + "() static in class " + cls.name());
                }
                if (implementation.visibility() != dev.phpruntime.runtime.Visibility.PUBLIC) {
                    throw PhpError.fatal("Access level to " + cls.name() + "::" + implementation.name() + "() must be public (as in interface " + iface.name() + ")");
                }
                if (dev.phpruntime.nodes.PhpFunctionRootNode.of(implementation.callTarget().getRootNode()) instanceof dev.phpruntime.nodes.PhpFunctionRootNode function) {
                    int required = entry.getValue().parameterNames().size();
                    if ((!function.isVariadic() && function.paramNames().length < required) || function.requiredArgs() > required) {
                        throw PhpError.fatal("Declaration of " + cls.name() + "::" + implementation.name() + "() must be compatible with "
                                + iface.name() + "::" + implementation.name() + "()");
                    }
                }
            }
            validateSplMethods(cls, iface);
        }
    }

    public long nextObjectId() {
        return nextObjectId++;
    }

    /**
     * Ids for enum case objects. PHP's enum cases do not consume the ordinary object-id
     * sequence, so the ids here start far above anything a request will print.
     */
    public long nextEnumObjectId() {
        return nextEnumObjectId++;
    }

    public long nextAnonymousId() {
        return nextAnonymousId++;
    }

    // ------------------------------------------------------------ snapshot access

    public dev.phpruntime.snapshot.SnapshotRun snapshotRun() {
        return snapshotRun;
    }

    public void attachSnapshotRun(dev.phpruntime.snapshot.SnapshotRun run) {
        this.snapshotRun = run;
    }

    public void markSnapshotAborted() {
        snapshotAborted = true;
    }

    public boolean snapshotAborted() {
        return snapshotAborted;
    }

    /** Marks a request the host must not reuse its polyglot context for. */
    public void markEndedAbnormally() {
        endedAbnormally = true;
    }

    public boolean endedAbnormally() {
        return endedAbnormally;
    }

    /** One global symbol as the snapshot layer sees it: a name, its id (or -1) and its cell. */
    public record SnapshotGlobal(String name, int id, boolean dynamic, PhpReference cell) {
    }

    /** Every defined global in definition order, id-based first then dynamic, for capture. */
    @TruffleBoundary
    public List<SnapshotGlobal> snapshotGlobals() {
        int dynamicCount = dynamicGlobals == null ? 0 : dynamicGlobals.size();
        List<SnapshotGlobal> out = new ArrayList<>(globalOrder.size() + dynamicCount);
        for (Map.Entry<String, Integer> entry : globalOrder.entrySet()) {
            int id = entry.getValue();
            PhpReference cell = id < globalsById.length ? globalsById[id] : null;
            if (cell != null && cell.getValue() != null && !"GLOBALS".equals(entry.getKey())) {
                out.add(new SnapshotGlobal(entry.getKey(), id, false, cell));
            }
        }
        if (dynamicGlobals != null) {
            for (Map.Entry<String, PhpReference> entry : dynamicGlobals.entrySet()) {
                PhpReference cell = entry.getValue();
                if (cell != null && cell.getValue() != null && !"GLOBALS".equals(entry.getKey())) {
                    out.add(new SnapshotGlobal(entry.getKey(), -1, true, cell));
                }
            }
        }
        return out;
    }

    @TruffleBoundary
    public Map<String, Object> snapshotConstants() {
        return constants;
    }

    @TruffleBoundary
    public Set<String> snapshotIncludedFiles() {
        return includedFiles;
    }

    @TruffleBoundary
    public List<PhpFunction> snapshotDeclaredFunctions() {
        return declaredFunctions == null ? List.of() : declaredFunctions;
    }

    @TruffleBoundary
    public Map<String, PhpClass> snapshotClasses() {
        return classes;
    }

    @TruffleBoundary
    public Iterable<Map.Entry<PhpClass, PhpClass.RequestState>> snapshotClassStates() {
        return classStates == null ? List.of() : classStates.entrySet();
    }

    @TruffleBoundary
    public List<Object> snapshotAutoloaders() {
        return autoloaders == null ? List.of() : autoloaders;
    }

    @TruffleBoundary
    public List<ShutdownEntry> snapshotShutdownFunctions() {
        return shutdownFunctions == null ? List.of() : shutdownFunctions;
    }

    /** The error handler stack, top first. */
    @TruffleBoundary
    public List<ErrorHandlerEntry> snapshotErrorHandlers() {
        return errorHandlers == null ? List.of() : new ArrayList<>(errorHandlers);
    }

    /** The exception handler stack, top first. */
    @TruffleBoundary
    public List<Object> snapshotExceptionHandlers() {
        return exceptionHandlers == null ? List.of() : new ArrayList<>(exceptionHandlers);
    }

    @TruffleBoundary
    public Iterable<Map.Entry<Object, Map<String, PhpReference>>> snapshotStaticLocals() {
        return staticLocals == null ? List.of() : staticLocals.entrySet();
    }

    @TruffleBoundary
    public long snapshotNextObjectId() {
        return nextObjectId;
    }

    @TruffleBoundary
    public long snapshotNextEnumObjectId() {
        return nextEnumObjectId;
    }

    @TruffleBoundary
    public long snapshotNextAnonymousId() {
        return nextAnonymousId;
    }

    @TruffleBoundary
    public void restoreObjectIds(long nextObject, long nextAnonymous) {
        nextObjectId = nextObject;
        nextAnonymousId = nextAnonymous;
    }

    /** Restores the enum-case id stream so cases created after a restore cannot collide. */
    @TruffleBoundary
    public void restoreEnumObjectId(long nextEnum) {
        nextEnumObjectId = nextEnum;
    }

    /** Restores a global cell without changing its aliased flag (bindGlobalById would). */
    @TruffleBoundary
    public void restoreGlobalCell(int id, PhpReference cell) {
        if (id >= globalsById.length) {
            growGlobalsTable(id);
        }
        globalsById[id] = cell;
        recordGlobal(id);
    }

    @TruffleBoundary
    public void restoreDynamicGlobal(String name, PhpReference cell) {
        dynamicGlobals().put(name, cell);
    }

    @TruffleBoundary
    public void restoreConstant(String name, Object value) {
        constants.put(name, value);
    }

    /**
     * Declares every function captured in a snapshot: {@code table} holds them by id, so the
     * lookup table grows once and the declared list takes them in one copy.
     */
    @TruffleBoundary
    public void restoreFunctions(PhpFunction[] table, List<PhpFunction> inOrder) {
        if (table.length > functionsById.length) {
            functionsById = Arrays.copyOf(functionsById, table.length);
        }
        for (int id = 0; id < table.length; id++) {
            if (table[id] != null) {
                functionsById[id] = table[id];
            }
        }
        declaredFunctions().addAll(inOrder);
    }

    /** Registers a class (or class alias) captured in a snapshot; classes are engine-wide objects. */
    @TruffleBoundary
    public void restoreClass(String lowerName, PhpClass cls) {
        PhpClass existing = classes.putIfAbsent(lowerName, cls);
        classGeneration++;
        if (existing != null) {
            if (existing != cls) {
                throw new IllegalStateException("snapshot class " + lowerName
                        + " does not match this request's linked class");
            }
            return;
        }
        int id = cls.classId();
        if (id >= classesById.length) {
            classesById = Arrays.copyOf(classesById, Math.max(id + 1, classesById.length * 2));
        }
        classesById[id] = cls;
    }

    @TruffleBoundary
    public void restoreAutoload(Object callable) {
        autoloaders().add(callable);
    }

    @TruffleBoundary
    public void restoreStaticLocal(Object ownerKey, String name, PhpReference cell) {
        staticLocals().computeIfAbsent(ownerKey, k -> new java.util.LinkedHashMap<>()).put(name, cell);
    }

    @TruffleBoundary
    public PhpClass.RequestState classStateOrNull(PhpClass cls) {
        return classStates == null ? null : classStates.get(cls);
    }

    // ------------------------------------------------------------ callable cache

    /**
     * Per-request memo of callable resolution. PHP function names cannot be redeclared and a
     * class's method table is fixed once the class is declared, so a resolved callback stays
     * valid for the whole request. The caches are small set-associative arrays keyed by String
     * identity: WordPress keeps its callbacks in arrays and the same String objects come back
     * on every call. Four-way sets cost a few more identity checks than direct mapping, but a
     * single unlucky identity-hash collision between two hot names would otherwise evict one
     * of them on every call.
     */
    private record CachedCallable(String name, PhpCallable callable) {
    }

    private record CachedMethod(PhpClass cls, String name, PhpClass.MethodEntry entry) {
    }

    /** The class and method entry behind a {@code ['Cls', 'm']} callable, plus the key that produced it. */
    public record StaticMethodLookup(String className, String methodName, PhpClass cls,
                                     PhpClass.MethodEntry entry) {
    }

    private static final int CACHE_WAYS = 4;
    private static final int FUNCTION_CACHE_SETS = 256;
    private static final int METHOD_CACHE_SETS = 32;
    private static final int STATIC_METHOD_CACHE_SETS = 32;

    /** All three caches stay null until the request resolves a callback or a method by name. */
    private CachedCallable[] functionCache;
    private CachedMethod[] methodCache;
    private StaticMethodLookup[] staticMethodCache;

    /** The immutable callable previously resolved for this exact String object, or null. */
    public PhpCallable cachedFunctionCallable(String name) {
        CachedCallable[] cache = functionCache;
        if (cache == null) {
            return null;
        }
        int base = (System.identityHashCode(name) & (FUNCTION_CACHE_SETS - 1)) * CACHE_WAYS;
        for (int i = 0; i < CACHE_WAYS; i++) {
            CachedCallable e = cache[base + i];
            if (e != null && e.name == name) {
                return e.callable;
            }
        }
        return null;
    }

    public void cacheFunctionCallable(String name, PhpCallable callable) {
        CachedCallable[] cache = functionCache;
        if (cache == null) {
            cache = new CachedCallable[FUNCTION_CACHE_SETS * CACHE_WAYS];
            functionCache = cache;
        }
        int base = (System.identityHashCode(name) & (FUNCTION_CACHE_SETS - 1)) * CACHE_WAYS;
        for (int i = 0; i < CACHE_WAYS; i++) {
            if (cache[base + i] == null) {
                cache[base + i] = new CachedCallable(name, callable);
                return;
            }
        }
        cache[base + CACHE_WAYS - 1] = new CachedCallable(name, callable);
    }

    /** The method entry for this class and method-name String object; the lookup itself is cached. */
    public PhpClass.MethodEntry cachedMethod(PhpClass cls, String name) {
        CachedMethod[] cache = methodCache;
        if (cache == null) {
            cache = new CachedMethod[METHOD_CACHE_SETS * CACHE_WAYS];
            methodCache = cache;
        }
        int hash = System.identityHashCode(cls) * 31 + System.identityHashCode(name);
        int base = (hash & (METHOD_CACHE_SETS - 1)) * CACHE_WAYS;
        for (int i = 0; i < CACHE_WAYS; i++) {
            CachedMethod e = cache[base + i];
            if (e != null && e.cls == cls && e.name == name) {
                return e.entry;
            }
        }
        PhpClass.MethodEntry entry = cls.lookupMethod(name);
        CachedMethod resolved = new CachedMethod(cls, name, entry);
        for (int i = 0; i < CACHE_WAYS; i++) {
            if (cache[base + i] == null) {
                cache[base + i] = resolved;
                return entry;
            }
        }
        cache[base + CACHE_WAYS - 1] = resolved;
        return entry;
    }

    /**
     * Resolves the class and method of a {@code ['Cls', 'm']} callable, caching both by the
     * identity of the two String objects. A class that is not declared yet is not cached: an
     * autoloader may declare it later in the request.
     */
    public StaticMethodLookup lookupStaticMethod(String className, String methodName) {
        StaticMethodLookup[] cache = staticMethodCache;
        if (cache == null) {
            cache = new StaticMethodLookup[STATIC_METHOD_CACHE_SETS * CACHE_WAYS];
            staticMethodCache = cache;
        }
        int hash = System.identityHashCode(className) * 31 + System.identityHashCode(methodName);
        int base = (hash & (STATIC_METHOD_CACHE_SETS - 1)) * CACHE_WAYS;
        for (int i = 0; i < CACHE_WAYS; i++) {
            StaticMethodLookup e = cache[base + i];
            if (e != null && e.className == className && e.methodName == methodName) {
                return e;
            }
        }
        PhpClass cls = lookupOrAutoloadClass(className);
        if (cls == null) {
            return null;
        }
        StaticMethodLookup resolved = new StaticMethodLookup(className, methodName, cls, cls.lookupMethod(methodName));
        for (int i = 0; i < CACHE_WAYS; i++) {
            if (cache[base + i] == null) {
                cache[base + i] = resolved;
                return resolved;
            }
        }
        cache[base + CACHE_WAYS - 1] = resolved;
        return resolved;
    }

    // ------------------------------------------------------------ constants

    @TruffleBoundary
    public boolean defineConstant(String name, Object value, int line) {
        String n = name.startsWith("\\") ? name.substring(1) : name;
        if (PhpConstants.lookupBuiltin(n) != null || constants.containsKey(n)) {
            warning("Constant " + n + " already defined", line);
            return false;
        }
        constants.put(n, value);
        return true;
    }

    public boolean hasConstant(String name) {
        // The constant table is per request and found names stay defined, so the common repeat
        // call is a map hit on the already-normalized name, without a boundary crossing.
        if (!name.isEmpty() && name.charAt(0) != '\\' && name.indexOf("::") < 0
                && foundConstants.containsKey(name)) {
            return true;
        }
        return hasConstantSlow(name);
    }

    @TruffleBoundary
    private boolean hasConstantSlow(String name) {
        String n = name.startsWith("\\") ? name.substring(1) : name;
        int sep = n.indexOf("::");
        if (sep > 0) {
            PhpClass cls = lookupClass(n.substring(0, sep));
            return cls != null && cls.lookupConstant(this, n.substring(sep + 2)) != null;
        }
        if (foundConstants.containsKey(n)) {
            return true;
        }
        // User code cannot define a constant whose name equals a built-in one: define() refuses
        // it (php 8.5 warns, this runtime returns false) and true/false/null are reserved in any
        // case. Checking the user table first therefore cannot change the answer, and it skips
        // the lowercase-then-map lookup of lookupBuiltin for WordPress's own constants.
        boolean found = constants.containsKey(n) || PhpConstants.lookupBuiltin(n) != null;
        if (found) {
            foundConstants.put(n, Boolean.TRUE);
        }
        return found;
    }

    /** A user-defined global constant (no namespace separator handling, no built-ins), or null. */
    @TruffleBoundary
    public Object readUserConstant(String name) {
        return constants.get(name);
    }

    @TruffleBoundary
    public Object readConstant(String name) {
        String n = name.startsWith("\\") ? name.substring(1) : name;
        int sep = n.indexOf("::");
        if (sep > 0) {
            PhpClass cls = lookupClass(n.substring(0, sep));
            return cls == null ? null : cls.lookupConstant(this, n.substring(sep + 2));
        }
        Object builtin = PhpConstants.lookupBuiltin(n);
        if (builtin != null) {
            return builtin;
        }
        return constants.get(n);
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
        public final StringBuilder text;
        public Object handler;
        public String handlerName = "default output handler";

        public OutputBuffer(StringBuilder text) {
            this.text = text;
        }
    }

    /** ob_start() levels, innermost last. Null means echo goes straight to the response. */
    private java.util.ArrayList<OutputBuffer> buffers;
    private boolean outputStarted;
    /**
     * Builders of finished output buffers, reused by the next ob_start() of this request or the
     * next request on this worker. A view render opens one per component, and each fresh
     * builder grew from 16 bytes, allocating every step of the growth.
     */
    private java.util.ArrayDeque<StringBuilder> bufferPool;

    private static final int BUFFER_POOL_SIZE = 8;
    private static final int BUFFER_POOL_MAX_CAPACITY = 1 << 20;

    /** A builder for a new output buffer, keeping the capacity a previous one reached. */
    @TruffleBoundary
    public StringBuilder newOutputBufferText() {
        if (bufferPool != null) {
            StringBuilder reused = bufferPool.pollLast();
            if (reused != null) {
                reused.setLength(0);
                return reused;
            }
        }
        return new StringBuilder();
    }

    /** Returns a finished output buffer's builder for reuse. Its text must be consumed already. */
    @TruffleBoundary
    public void releaseOutputBufferText(StringBuilder text) {
        if (text.capacity() > BUFFER_POOL_MAX_CAPACITY) {
            return; // one huge render must not pin its buffer for the life of the worker
        }
        if (bufferPool == null) {
            bufferPool = new java.util.ArrayDeque<>();
        }
        if (bufferPool.size() < BUFFER_POOL_SIZE) {
            text.setLength(0);
            bufferPool.addLast(text);
        }
    }

    public java.util.List<OutputBuffer> outputBuffers() {
        if (buffers == null) {
            buffers = new java.util.ArrayList<>();
        }
        return buffers;
    }

    /** Appends to the innermost output buffer, or to the response when none is open. */
    @TruffleBoundary
    public void echo(String s) {
        if (PhpStrings.CHECK) {
            PhpStrings.check(s, byteStringOrigin == null ? "echo/output" : "echo/output in " + byteStringOrigin + "()");
        }
        if (buffers != null && !buffers.isEmpty()) {
            buffers.get(buffers.size() - 1).text.append(s);
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
        while (buffers != null && !buffers.isEmpty()) {
            OutputBuffer top = buffers.remove(buffers.size() - 1);
            String text = top.text.toString();
            releaseOutputBufferText(top.text);
            echo(applyObHandler(top, text, 9L));
        }
        flushOutput();
    }

    @TruffleBoundary
    public String applyObHandler(OutputBuffer buf, String text, long phase) {
        if (buf.handler == null || buf.handler == PhpNull.INSTANCE) {
            return text;
        }
        PhpCallable cb = PhpCallable.resolveOrNull(this, buf.handler);
        if (cb == null) {
            return text;
        }
        Object res = cb.call(this, null, null, text, phase);
        return Boolean.FALSE.equals(res) ? text : PhpOps.toStr(res);
    }

    /** Writes what has reached the response so far (PHP's flush()); open output buffers stay open. */
    @TruffleBoundary
    public void flushOutput() {
        if (output.isEmpty()) {
            return;
        }
        try {
            OutputStream out = env.out();
            out.write(PhpStrings.bytes(output.toString()));
            out.flush();
        } catch (IOException e) {
            throw new dev.phpruntime.runtime.PhpError("Failed writing output: " + PhpStrings.fromJava(e.getMessage()));
        }
        output.setLength(0);
    }

    private List<ShutdownEntry> shutdownFunctions() {
        List<ShutdownEntry> list = shutdownFunctions;
        if (list == null) {
            list = new ArrayList<>();
            shutdownFunctions = list;
        }
        return list;
    }

    @TruffleBoundary
    public void registerShutdownFunction(Object callable, Object[] args) {
        shutdownFunctions().add(new ShutdownEntry(callable, args));
    }

    @TruffleBoundary
    public void runShutdownFunctions() {
        if (shutdownFunctions == null) {
            return;
        }
        for (int i = 0; i < shutdownFunctions.size(); i++) {
            ShutdownEntry entry = shutdownFunctions.get(i);
            PhpCallable cb = PhpCallable.resolveOrNull(this, entry.callable());
            if (cb != null) {
                try {
                    cb.call(this, null, null, entry.args());
                } catch (ControlFlow.Exit e) {
                    endedAbnormally = true;
                    break;
                }
            }
        }
    }

    private Deque<ErrorHandlerEntry> errorHandlers() {
        Deque<ErrorHandlerEntry> stack = errorHandlers;
        if (stack == null) {
            stack = new ArrayDeque<>();
            errorHandlers = stack;
        }
        return stack;
    }

    private Deque<Object> exceptionHandlers() {
        Deque<Object> stack = exceptionHandlers;
        if (stack == null) {
            stack = new ArrayDeque<>();
            exceptionHandlers = stack;
        }
        return stack;
    }

    @TruffleBoundary
    public Object setErrorHandler(Object callable, long errorLevels) {
        ErrorHandlerEntry prev = errorHandlers == null ? null : errorHandlers.peek();
        errorHandlers().push(new ErrorHandlerEntry(callable, errorLevels));
        return prev == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(prev.callable());
    }

    @TruffleBoundary
    public boolean restoreErrorHandler() {
        if (errorHandlers != null && !errorHandlers.isEmpty()) {
            errorHandlers.pop();
        }
        return true;
    }

    @TruffleBoundary
    public Object setExceptionHandler(Object callable) {
        Object prev = exceptionHandlers == null ? null : exceptionHandlers.peek();
        exceptionHandlers().push(callable);
        return prev == null ? PhpNull.INSTANCE : PhpOps.copyOnAssign(prev);
    }

    @TruffleBoundary
    public boolean restoreExceptionHandler() {
        if (exceptionHandlers != null && !exceptionHandlers.isEmpty()) {
            exceptionHandlers.pop();
        }
        return true;
    }

    @TruffleBoundary
    public boolean handleUncaughtException(PhpObject exObj) {
        Object handler = exceptionHandlers == null ? null : exceptionHandlers.peek();
        if (handler == null || handler == PhpNull.INSTANCE) {
            return false;
        }
        PhpCallable cb = PhpCallable.resolveOrNull(this, handler);
        if (cb == null) {
            return false;
        }
        try {
            cb.call(this, null, null, exObj);
        } catch (ControlFlow.Exit ignored) {
        }
        return true;
    }

    @TruffleBoundary
    public void warning(String message, int line) {
        emitDiagnostic(2L, "Warning", message, line);
    }

    @TruffleBoundary
    public void notice(String message, int line) {
        emitDiagnostic(8L, "Notice", message, line);
    }

    @TruffleBoundary
    public void deprecated(String message, int line) {
        emitDiagnostic(8192L, "Deprecated", message, line);
    }

    // ---- the call-site stack for PHP stack traces

    /**
     * The call site of every guest call currently on the stack, oldest first. A call site
     * pushes before it invokes the callee and restores the depth afterwards (also while
     * unwinding), so an exception created in a callee reads the frames above it without
     * walking the Truffle stack, which costs microseconds from compiled code. The callee of a
     * frame is the function owning the site of the next inner frame, which keeps the entry to
     * one reference.
     */
    private Object[] traceSites = new Object[64];
    private int traceDepth;

    public int traceDepth() {
        return traceDepth;
    }

    public Object traceSite(int index) {
        return traceSites[index];
    }

    /** Pushes a frame; returns the depth to give back to {@link #popTraceFrame}. */
    public int pushTraceFrame(Object site) {
        int depth = traceDepth;
        Object[] sites = traceSites;
        if (depth == sites.length) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
            growTraceSites(depth);
            sites = traceSites;
        }
        sites[depth] = site;
        traceDepth = depth + 1;
        return depth;
    }

    /** The rare deep-recursion path: keeping the copy out of every call site's compiled code. */
    @TruffleBoundary
    private void growTraceSites(int depth) {
        traceSites = java.util.Arrays.copyOf(traceSites, depth * 2);
    }

    public void popTraceFrame(int depth) {
        traceDepth = depth;
    }

    /** A one-shot mode for the user function a builtin is about to call, or null. */
    private CallMode pendingCallMode;

    public CallMode pendingCallMode() {
        return pendingCallMode;
    }

    public void setPendingCallMode(CallMode mode) {
        this.pendingCallMode = mode;
    }

    /** Named arguments a forwarding builtin or method received, read by its implementation. */
    private dev.phpruntime.runtime.ForwardedNamedArgs forwardedNamed;

    public dev.phpruntime.runtime.ForwardedNamedArgs forwardedNamed() {
        return forwardedNamed;
    }

    public void setForwardedNamed(dev.phpruntime.runtime.ForwardedNamedArgs named) {
        this.forwardedNamed = named;
    }

    /** Takes and clears the forwarded named arguments, so a nested call cannot see them. */
    public dev.phpruntime.runtime.ForwardedNamedArgs takeForwardedNamed() {
        dev.phpruntime.runtime.ForwardedNamedArgs named = forwardedNamed;
        forwardedNamed = null;
        return named;
    }

    /**
     * An error raised by the script itself (trigger_error/user_error), routed through
     * set_error_handler. Returns whether a handler accepted it (and so execution continues).
     */
    @TruffleBoundary
    public boolean userError(long errno, String message, int line) {
        String label = switch ((int) errno) {
            case 256 -> "Fatal error";
            case 512 -> "Warning";
            case 8192 -> "Deprecated";
            case 16384 -> "Deprecated";
            default -> "Notice";
        };
        return emitDiagnostic(errno, label, message, line);
    }

    /** The source file of the innermost PHP frame (an included file, not just the entry script). */
    @TruffleBoundary
    private String currentGuestFile() {
        String path = com.oracle.truffle.api.Truffle.getRuntime().iterateFrames(frame -> {
            if (frame.getCallTarget() instanceof com.oracle.truffle.api.RootCallTarget target
                    && target.getRootNode() instanceof dev.phpruntime.nodes.PhpRootNode root
                    && root.source() != null && root.source().getPath() != null) {
                return root.source().getPath();
            }
            return null;
        });
        return path != null ? path : request().scriptFilename;
    }

    @TruffleBoundary
    private boolean emitDiagnostic(long errno, String label, String message, int line) {
        GuestLocation where = currentGuestLocation();
        String file = PhpStrings.fromJava(where.file());
        if (line <= 0) {
            // Builtins pass 0; php reports the line of the call that raised it.
            int site = currentBuiltinCallSite != null
                    ? dev.phpruntime.runtime.PhpBacktrace.callSiteLine(currentBuiltinCallSite) : 0;
            line = site > 0 ? site : where.line();
        }
        ErrorHandlerEntry handler = errorHandlers == null ? null : errorHandlers.peek();
        if (handler != null && handler.callable() != PhpNull.INSTANCE && !inErrorHandler
                && (handler.errorLevels() & errno) != 0) {
            PhpCallable cb = PhpCallable.resolveOrNull(this, handler.callable());
            if (cb != null) {
                inErrorHandler = true;
                try {
                    Object handled = cb.call(this, null, null, errno, message, file, (long) line);
                    if (!Boolean.FALSE.equals(handled)) {
                        return true;
                    }
                } finally {
                    inErrorHandler = false;
                }
            }
        }
        if ((errorReporting & errno) == 0) {
            // A user handler sees every level it asked for; the default display honours
            // error_reporting, so a masked deprecation stays silent as in php.
            return false;
        }
        errStream().println("PHP " + label + ":  " + message + (file.isEmpty() ? "" : " in " + file) + (line > 0 ? " on line " + line : ""));
        return false;
    }

    private record GuestLocation(String file, int line) {
    }

    /** The file and current line of the innermost PHP frame. */
    @TruffleBoundary
    private GuestLocation currentGuestLocation() {
        GuestLocation found = com.oracle.truffle.api.Truffle.getRuntime().iterateFrames(frame -> {
            if (frame.getCallTarget() instanceof com.oracle.truffle.api.RootCallTarget target
                    && target.getRootNode() instanceof dev.phpruntime.nodes.PhpRootNode root
                    && root.source() != null && root.source().getPath() != null) {
                int line = 0;
                if (root instanceof dev.phpruntime.bytecode.PhpBytecodeRootNode) {
                    com.oracle.truffle.api.source.SourceSection at =
                            dev.phpruntime.bytecode.PhpBytecodeRootNode.callSiteSection(frame);
                    if (at != null) {
                        line = at.getStartLine();
                    }
                }
                return new GuestLocation(root.source().getPath(), line);
            }
            return null;
        });
        return found != null ? found : new GuestLocation(request().scriptFilename, 0);
    }
}
