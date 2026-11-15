package dev.phpruntime;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.source.Source;
import dev.phpruntime.nodes.PhpMainRootNode;
import dev.phpruntime.parser.Parser;
import dev.phpruntime.runtime.PhpClassTemplate;

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
    private final ConcurrentHashMap<String, Integer> functionIds = new ConcurrentHashMap<>();
    private final AtomicInteger nextFunctionId = new AtomicInteger();
    private final ConcurrentHashMap<String, Integer> methodIds = new ConcurrentHashMap<>();
    private final AtomicInteger nextMethodId = new AtomicInteger();
    private final ConcurrentHashMap<String, Integer> classIds = new ConcurrentHashMap<>();
    private final AtomicInteger nextClassId = new AtomicInteger();
    private final AtomicLong nextTemplateId = new AtomicLong(1);
    private final ConcurrentHashMap<List<PhpClassTemplate>, Long> hierarchyIds = new ConcurrentHashMap<>();
    private final AtomicLong nextHierarchyId = new AtomicLong(1);
    private final PhpClassTemplate stdClassTemplate = new PhpClassTemplate(
            0L, "stdClass", false, false, false, false, null, List.of(), List.of(), List.of(), Map.of());

    public PhpClassTemplate stdClassTemplate() {
        return stdClassTemplate;
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
        return functionIds.computeIfAbsent(name.toLowerCase(), n -> nextFunctionId.getAndIncrement());
    }

    public int methodId(String name) {
        return methodIds.computeIfAbsent(name.toLowerCase(), k -> nextMethodId.getAndIncrement());
    }

    public int classId(String name) {
        return classIds.computeIfAbsent(name.toLowerCase(), k -> nextClassId.getAndIncrement());
    }

    public long nextTemplateId() {
        return nextTemplateId.getAndIncrement();
    }

    public long hierarchyId(List<PhpClassTemplate> chain) {
        return hierarchyIds.computeIfAbsent(chain, k -> nextHierarchyId.getAndIncrement());
    }

    /** Loads a cached Source keyed by canonical path and modification time. */
    public Source loadSource(TruffleFile canonical) throws IOException {
        String key = canonical.getPath();
        long modified = canonical.getLastModifiedTime().toMillis();
        CachedSource cached = sources.get(key);
        if (cached != null && cached.modified() == modified) {
            return cached.source();
        }
        Source src = Source.newBuilder(ID, canonical).cached(true).build();
        sources.put(key, new CachedSource(modified, src));
        return src;
    }

    @Override
    protected PhpContext createContext(Env env) {
        return new PhpContext(this, env);
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        Source source = request.getSource();
        PhpMainRootNode root = new Parser(this, source).parseMain();
        return root.getCallTarget();
    }

    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        // A PHP request is single-threaded, but the scheduler may resume it on another
        // carrier thread, so we allow any one thread at a time.
        return singleThreaded;
    }
}
