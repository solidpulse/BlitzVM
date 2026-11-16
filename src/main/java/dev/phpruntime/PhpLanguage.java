package dev.phpruntime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;
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
public final class PhpLanguage extends TruffleLanguage<PhpContext> {

    public static final String ID = "php";
    public static final String MIME_TYPE = "application/x-php";

    private static final LanguageReference<PhpLanguage> REFERENCE = LanguageReference.create(PhpLanguage.class);

    private final ConcurrentHashMap<String, CachedSource> sources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ResolvedInclude> includes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Source, CallTarget> localIncludes = new ConcurrentHashMap<>();

    /**
     * How long a resolved include stays trusted without touching the file system, like
     * opcache.revalidate_freq. 0 checks on every include.
     */
    private static final long INCLUDE_REVALIDATE_NANOS = Long.getLong("blitz.include.revalidateMs", 2000L) * 1_000_000L;
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
                    all.putAll(SplClasses.buildTemplates(this));
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

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    public PhpClassTemplate mysqliTemplate(List<PhpClassTemplate.PropertyDecl> props) {
        PhpClassTemplate tpl = mysqliTemplate;
        if (tpl == null) {
            synchronized (this) {
                tpl = mysqliTemplate;
                if (tpl == null) {
                    tpl = new PhpClassTemplate(nextTemplateId(), "mysqli", false, false, false, false,
                            null, List.of(), props, List.of(), Map.of());
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
                        methods.put(lower, new PhpClassTemplate.MethodDecl(name, lower, methodId(name), Visibility.PUBLIC,
                                name.equals("bind") || name.equals("fromCallable"), false, false,
                                new dev.phpruntime.nodes.call.ClosureMethodRootNode(this, name).getCallTarget()));
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

    private record CachedSource(long modified, Source source) {
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
        return functionIds.computeIfAbsent(PhpStrings.lower(name), n -> nextFunctionId.getAndIncrement());
    }

    public int methodId(String name) {
        return methodIds.computeIfAbsent(PhpStrings.lower(name), k -> nextMethodId.getAndIncrement());
    }

    public int classId(String name) {
        return classIds.computeIfAbsent(PhpStrings.lower(name), k -> nextClassId.getAndIncrement());
    }

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
     * An include path already resolved to a parsed file: what {@code include} would find for
     * this path string from this directory, checked at {@code checkedAt} (System.nanoTime).
     */
    public record ResolvedInclude(String canonicalPath, CallTarget target, long checkedAt) {
    }

    /** The cached resolution for this include key, or null if absent or due for revalidation. */
    public ResolvedInclude cachedInclude(String key) {
        ResolvedInclude r = includes.get(key);
        if (r != null && INCLUDE_REVALIDATE_NANOS > 0 && System.nanoTime() - r.checkedAt() < INCLUDE_REVALIDATE_NANOS) {
            return r;
        }
        return null;
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
        CachedSource cached = sources.get(key);
        if (cached != null && cached.modified() == modified) {
            return cached.source();
        }
        Source src = Source.newBuilder(ID, canonical).encoding(java.nio.charset.StandardCharsets.ISO_8859_1).cached(true).build();
        sources.put(key, new CachedSource(modified, src));
        return src;
    }

    @Override
    protected PhpContext createContext(Env env) {
        ensureBuiltinTemplates();
        return new PhpContext(this, env);
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
        return new Parser(this, source).parseMain().getCallTarget();
    }

    /** A second engine-wide variant; its arguments supply the invocation's symbols/class scope. */
    public CallTarget parseLocalBytes(Source source) {
        return localIncludes.computeIfAbsent(source, s -> new Parser(this, s, true).parseMain().getCallTarget());
    }

    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        // A PHP request is single-threaded, but the scheduler may resume it on another
        // carrier thread, so we allow any one thread at a time.
        return singleThreaded;
    }
}
