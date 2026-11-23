package dev.phpruntime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.source.Source;
import dev.phpruntime.nodes.PhpMainRootNode;
import dev.phpruntime.nodes.call.ClosureInvokeRootNode;
import dev.phpruntime.parser.Parser;
import dev.phpruntime.runtime.PhpClassTemplate;
import dev.phpruntime.runtime.PhpExceptions;
import dev.phpruntime.runtime.SplClasses;
import dev.phpruntime.runtime.PhpStrings;
import dev.phpruntime.runtime.Visibility;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Truffle entry point. {@link #parse} turns PHP source into a tree of self-specialising
 * AST nodes; Graal partially evaluates hot trees into machine code when running on a
 * JVMCI-enabled JDK, and the same tree simply interprets on a stock JDK.
 */
@TruffleLanguage.Registration(
        id = PhpLanguage.ID,
        name = "PHP",
        defaultMimeType = PhpLanguage.MIME_TYPE,
        characterMimeTypes = PhpLanguage.MIME_TYPE,
        fileTypeDetectors = PhpFileDetector.class,
        contextPolicy = TruffleLanguage.ContextPolicy.SHARED)
@ProvidedTags({StandardTags.RootTag.class, StandardTags.RootBodyTag.class})
public final class PhpLanguage extends TruffleLanguage<PhpContextHolder> {

    public static final String ID = "php";
    public static final String MIME_TYPE = "application/x-php";

    private static final LanguageReference<PhpLanguage> REFERENCE = LanguageReference.create(PhpLanguage.class);

    private final ConcurrentHashMap<String, CachedSource> sources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ResolvedInclude> includes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Source, CallTarget> localIncludes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Source, CallTarget> evals = new ConcurrentHashMap<>();

    /**
     * How long a resolved include stays trusted without touching the file system, like
     * opcache.revalidate_freq. 0 checks on every include.
     */
    /**
     * Read when the language instance is created, not during class initialization:
     * the class must be image-build-time initialized so its engine can be cached.
     */
    private final long includeRevalidateNanos = Long.getLong("blitz.include.revalidateMs", 2000L) * 1_000_000L;
    private final ConcurrentHashMap<String, Integer> functionIds = new ConcurrentHashMap<>();
    private final AtomicInteger nextFunctionId = new AtomicInteger();
    private final ConcurrentHashMap<String, Integer> methodIds = new ConcurrentHashMap<>();
    private final AtomicInteger nextMethodId = new AtomicInteger();
    private final ConcurrentHashMap<String, Integer> classIds = new ConcurrentHashMap<>();
    private final AtomicInteger nextClassId = new AtomicInteger();
    private final ConcurrentHashMap<String, Integer> globalIds = new ConcurrentHashMap<>();
    private volatile String[] globalNamesById = new String[64];
    private final AtomicInteger nextGlobalId = new AtomicInteger();
    private final AtomicLong nextTemplateId = new AtomicLong(2);
    private final ConcurrentHashMap<List<PhpClassTemplate>, Long> hierarchyIds = new ConcurrentHashMap<>();
    private final AtomicLong nextHierarchyId = new AtomicLong(1);
    private final PhpClassTemplate stdClassTemplate = new PhpClassTemplate(
            0L, "stdClass", false, false, false, false, null, List.of(), List.of(), List.of(), Map.of());
    private volatile PhpClassTemplate closureTemplate;
    private volatile PhpClassTemplate mysqliTemplate;
    private volatile PhpClassTemplate mysqliResultTemplate;
    private volatile PhpClassTemplate mysqliStmtTemplate;
    private volatile Map<String, PhpClassTemplate> builtinClassTemplates;
    private volatile String[] builtinClassNamesById;

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private Map<String, PhpClassTemplate> ensureBuiltinTemplates() {
        Map<String, PhpClassTemplate> m = builtinClassTemplates;
        if (m == null) {
            synchronized (this) {
                m = builtinClassTemplates;
                if (m == null) {
                    Map<String, PhpClassTemplate> all = new java.util.LinkedHashMap<>(PhpExceptions.buildTemplates(this));
                    dev.phpruntime.builtins.DateTimeClasses.buildTemplates(this, all);
                    dev.phpruntime.builtins.IntlClasses.buildTemplates(this, all);
                    all.putAll(SplClasses.buildTemplates(this));
                    dev.phpruntime.builtins.DirClasses.buildTemplates(this, all);
                    dev.phpruntime.builtins.PdoClasses.buildTemplates(this, all);
                    dev.phpruntime.builtins.ReflectionClasses.buildTemplates(this, all);
                    dev.phpruntime.builtins.RandomClasses.buildTemplates(this, all);
                    m = Map.copyOf(all);
                    int max = -1;
                    for (String lower : m.keySet()) {
                        int id = classId(lower);
                        if (id > max) {
                            max = id;
                        }
                    }
                    String[] arr = new String[max + 1];
                    for (String lower : m.keySet()) {
                        arr[classId(lower)] = lower;
                    }
                    builtinClassNamesById = arr;
                    builtinClassTemplates = m;
                }
            }
        }
        return m;
    }

    public PhpClassTemplate stdClassTemplate() {
        return stdClassTemplate;
    }

    /** The mysqli template already built for this language, or null before its first use. */
    public PhpClassTemplate mysqliTemplateOrNull() {
        return mysqliTemplate;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public PhpClassTemplate mysqliTemplate(List<PhpClassTemplate.PropertyDecl> props,
                                           Map<String, PhpClassTemplate.MethodDecl> methods) {
        PhpClassTemplate tpl = mysqliTemplate;
        if (tpl == null) {
            synchronized (this) {
                tpl = mysqliTemplate;
                if (tpl == null) {
                    tpl = new PhpClassTemplate(nextTemplateId(), "mysqli", false, false, false, false,
                            null, List.of(), props, List.of(), methods);
                    mysqliTemplate = tpl;
                }
            }
        }
        return tpl;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public PhpClassTemplate mysqliResultTemplate(List<PhpClassTemplate.PropertyDecl> props) {
        PhpClassTemplate tpl = mysqliResultTemplate;
        if (tpl == null) {
            synchronized (this) {
                tpl = mysqliResultTemplate;
                if (tpl == null) {
                    tpl = new PhpClassTemplate(nextTemplateId(), "mysqli_result", false, false, false, false,
                            null, List.of(), props, List.of(), Map.of());
                    mysqliResultTemplate = tpl;
                }
            }
        }
        return tpl;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public PhpClassTemplate mysqliStmtTemplate(List<PhpClassTemplate.PropertyDecl> props) {
        PhpClassTemplate tpl = mysqliStmtTemplate;
        if (tpl == null) {
            synchronized (this) {
                tpl = mysqliStmtTemplate;
                if (tpl == null) {
                    tpl = new PhpClassTemplate(nextTemplateId(), "mysqli_stmt", false, false, false, false,
                            null, List.of(), props, List.of(), Map.of());
                    mysqliStmtTemplate = tpl;
                }
            }
        }
        return tpl;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public PhpClassTemplate closureTemplate() {
        PhpClassTemplate tpl = closureTemplate;
        if (tpl == null) {
            synchronized (this) {
                tpl = closureTemplate;
                if (tpl == null) {
                    PhpClassTemplate.MethodDecl invokeDecl = new PhpClassTemplate.MethodDecl(
                            "__invoke", "__invoke", methodId("__invoke"), Visibility.PUBLIC,
                            false, false, false, new ClosureInvokeRootNode(this).getCallTarget());
                    Map<String, PhpClassTemplate.MethodDecl> methods = new java.util.LinkedHashMap<>();
                    methods.put("__invoke", invokeDecl);
                    for (String name : List.of("bind", "bindTo", "call", "fromCallable")) {
                        String lower = name.toLowerCase();
                        List<String> parameterNames = switch (name) {
                            case "bind" -> List.of("closure", "newThis", "newScope");
                            case "bindTo" -> List.of("newThis", "newScope");
                            case "call" -> List.of("newThis", "args");
                            default -> List.of("callback");
                        };
                        List<dev.phpruntime.runtime.PhpParameter> parameters = new java.util.ArrayList<>();
                        for (String parameterName : parameterNames) {
                            parameters.add(new dev.phpruntime.runtime.PhpParameter(parameterName, null, List.of(),
                                    false, parameterName.equals("args"), false, null, null));
                        }
                        dev.phpruntime.runtime.PhpSignature signature =
                                new dev.phpruntime.runtime.PhpSignature(parameters, null, List.of());
                        methods.put(lower, new PhpClassTemplate.MethodDecl(name, lower, methodId(name), Visibility.PUBLIC,
                                name.equals("bind") || name.equals("fromCallable"), false, false,
                                new dev.phpruntime.nodes.call.ClosureMethodRootNode(this, name).getCallTarget(),
                                signature));
                    }
                    tpl = new PhpClassTemplate(
                            1L, "Closure", false, false, true, false, null,
                            List.of(), List.of(), List.of(), methods);
                    closureTemplate = tpl;
                }
            }
        }
        return tpl;
    }

    public PhpClassTemplate builtinClassTemplate(String lowerName) {
        return ensureBuiltinTemplates().get(lowerName);
    }

    public PhpClassTemplate builtinClassTemplateById(int id) {
        String[] names = builtinClassNamesById;
        if (names == null) {
            ensureBuiltinTemplates();
            names = builtinClassNamesById;
        }
        if (id >= 0 && id < names.length && names[id] != null) {
            return builtinClassTemplates.get(names[id]);
        }
        return null;
    }

    /**
     * A parsed file and the stat fields it was read under. The modification time alone is not
     * enough: Symfony's PhpFilesAdapter backdates every cache file it writes to a fixed
     * {@code startTime - 10}, so a rewritten file can keep its old mtime; size, inode (a rename
     * over the file) and ctime (an in-place write) catch that.
     */
    private record CachedSource(long modified, long size, long inode, long ctime, Source source) {
    }

    public static PhpLanguage get(com.oracle.truffle.api.nodes.Node node) {
        return REFERENCE.get(node);
    }

    /**
     * A dense id per function name (case-insensitive), shared by all contexts. Call sites
     * keep the id and each context keeps its functions in an array indexed by it, so a call
     * finds the current context's function with one array load instead of a map lookup.
     */
    public int functionId(String name) {
        String lower = PhpStrings.lower(name);
        Integer id = functionIds.get(lower);
        return id != null ? id : functionIds.computeIfAbsent(lower, n -> nextFunctionId.getAndIncrement());
    }

    /** The id of a function name that some file has declared or called, or -1 if none has. */
    public int functionIdIfKnown(String lowerName) {
        Integer id = functionIds.get(lowerName);
        return id == null ? -1 : id;
    }

    public int methodId(String name) {
        return methodIds.computeIfAbsent(PhpStrings.lower(name), k -> nextMethodId.getAndIncrement());
    }

    public int classId(String name) {
        return classIds.computeIfAbsent(PhpStrings.lower(name), k -> nextClassId.getAndIncrement());
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public int globalId(String name) {
        Integer existing = globalIds.get(name);
        if (existing != null) {
            return existing;
        }
        synchronized (globalIds) {
            existing = globalIds.get(name);
            if (existing != null) {
                return existing;
            }
            int id = nextGlobalId.getAndIncrement();
            String[] names = globalNamesById;
            if (id >= names.length) {
                names = java.util.Arrays.copyOf(names, Math.max(id + 1, names.length * 2));
            }
            names[id] = name;
            globalNamesById = names;
            globalIds.put(name, id);
            return id;
        }
    }

    public Integer lookupGlobalId(String name) {
        return globalIds.get(name);
    }

    public int globalIdCount() {
        return nextGlobalId.get();
    }

    public String globalName(int id) {
        String[] names = globalNamesById;
        return id >= 0 && id < names.length ? names[id] : null;
    }

    public long nextTemplateId() {
        return nextTemplateId.getAndIncrement();
    }

    public long hierarchyId(List<PhpClassTemplate> chain) {
        return hierarchyIds.computeIfAbsent(chain, k -> nextHierarchyId.getAndIncrement());
    }

    /**
     * The hierarchy id every class of one name has had so far, valid while no context declares
     * that name with a different parent chain. Compiled code reads it as a constant instead of
     * looking the class up in the running context's table.
     */
    public record StableHierarchy(long id, com.oracle.truffle.api.Assumption valid) {
    }

    private final java.util.concurrent.ConcurrentHashMap<Integer, StableHierarchy> stableHierarchies =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Records that a class of name id {@code classId} with hierarchy {@code hierarchyId} exists. */
    public void noteHierarchy(int classId, long hierarchyId) {
        StableHierarchy seen = stableHierarchies.computeIfAbsent(classId, k -> new StableHierarchy(hierarchyId,
                com.oracle.truffle.api.Truffle.getRuntime().createAssumption("stable hierarchy")));
        if (seen.id() != hierarchyId) {
            seen.valid().invalidate();
        }
    }

    /** The stable hierarchy of a class name, or null if no class of that name was created yet. */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public StableHierarchy stableHierarchy(int classId) {
        return stableHierarchies.get(classId);
    }

    /**
     * An include path already resolved to a parsed file: what {@code include} would find for
     * this path string from this directory, checked at {@code checkedAt} (System.nanoTime).
     */
    public record ResolvedInclude(String canonicalPath, CallTarget target, long checkedAt) {
    }

    /** The cached resolution for this include key, or null if absent or due for revalidation. */
    public ResolvedInclude cachedInclude(String key) {
        ResolvedInclude r = includes.get(key);
        if (r != null && includeRevalidateNanos > 0 && System.nanoTime() - r.checkedAt() < includeRevalidateNanos) {
            return r;
        }
        return null;
    }

    /**
     * opcache_invalidate: forget the parsed source of this canonical path and every include
     * resolution that points at it, so the next include stats and reparses the file.
     * Returns whether anything was cached.
     */
    public boolean invalidateInclude(String canonicalPath) {
        boolean had = sources.remove(canonicalPath) != null;
        had |= includes.values().removeIf(r -> r.canonicalPath().equals(canonicalPath));
        return had;
    }

    /** opcache_is_script_cached: whether a parsed source for this canonical path is held. */
    public boolean isIncludeCached(String canonicalPath) {
        return sources.containsKey(canonicalPath);
    }

    public ResolvedInclude cacheInclude(String key, String canonicalPath, CallTarget target) {
        ResolvedInclude r = new ResolvedInclude(canonicalPath, target, System.nanoTime());
        includes.put(key, r);
        return r;
    }

    /** Loads a cached Source keyed by canonical path and modification time. */
    public Source loadSource(TruffleFile canonical) throws IOException {
        String key = canonical.getPath();
        long modified = canonical.getLastModifiedTime().toMillis();
        long size = canonical.size();
        long inode = -1;
        long ctime = -1;
        try {
            inode = canonical.getAttribute(TruffleFile.UNIX_INODE);
            ctime = canonical.getAttribute(TruffleFile.UNIX_CTIME).to(java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch (UnsupportedOperationException e) {
            // Not a POSIX file system: mtime and size only.
        }
        CachedSource cached = sources.get(key);
        if (cached != null && cached.modified() == modified && cached.size() == size
                && cached.inode() == inode && cached.ctime() == ctime) {
            return cached.source();
        }
        Source src = Source.newBuilder(ID, canonical).encoding(java.nio.charset.StandardCharsets.ISO_8859_1).cached(true).build();
        sources.put(key, new CachedSource(modified, size, inode, ctime, src));
        return src;
    }

    @Override
    protected PhpContextHolder createContext(Env env) {
        ensureBuiltinTemplates();
        return PhpContextHolder.create(this, env);
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        Source source = request.getSource();
        // Polyglot character sources are Java text; files are explicitly read as bytes.
        if (source.getPath() == null) {
            source = Source.newBuilder(ID, PhpStrings.fromJava(source.getCharacters().toString()), source.getName())
                    .uri(source.getURI()).cached(source.isCached()).build();
        }
        return parseBytes(source);
    }

    /** Internal sources (eval) already contain PHP bytes, unlike polyglot character sources. */
    public CallTarget parseBytes(Source source) {
        return dev.phpruntime.bytecode.BytecodeEmitter.mainTarget(this, new Parser(this, source).parseMain());
    }

    /** A second engine-wide variant; its arguments supply the invocation's symbols/class scope. */
    public CallTarget parseLocalBytes(Source source) {
        return localIncludes.computeIfAbsent(source, s -> dev.phpruntime.bytecode.BytecodeEmitter.mainTarget(this,
                new Parser(this, s, true).parseMain()));
    }

    public CallTarget parseEvalBytes(Source source) {
        return evals.computeIfAbsent(source, this::parseBytes);
    }

    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        // A PHP request is single-threaded, but the scheduler may resume it on another
        // carrier thread, so we allow any one thread at a time.
        return singleThreaded;
    }
}
