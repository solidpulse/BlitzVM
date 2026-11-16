package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import dev.phpruntime.PhpContext;
import dev.phpruntime.PhpLanguage;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.PhpRootNode;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpOps;
import dev.phpruntime.runtime.PhpStrings;

import java.io.IOException;

/** {@code include}, {@code include_once}, {@code require}, {@code require_once}. */
public final class IncludeNode extends PhpNode {

    public enum Kind { INCLUDE, INCLUDE_ONCE, REQUIRE, REQUIRE_ONCE }

    private final Kind kind;
    @Child private PhpNode pathExpr;
    @Child private IndirectCallNode callNode = Truffle.getRuntime().createIndirectCallNode();

    public IncludeNode(Kind kind, PhpNode pathExpr) {
        this.kind = kind;
        this.pathExpr = pathExpr;
    }

    public Kind kind() {
        return kind;
    }

    private record PreparedInclude(CallTarget target, String canonicalPath) {
    }

    @Override
    public Object execute(VirtualFrame frame) {
        String path = PhpOps.toStr(pathExpr.execute(frame));
        // TODO(dynamic-scope): PHP runs includes inside functions in the caller's local scope; we run in global scope.
        Object prepared = prepare(path);
        if (prepared instanceof PreparedInclude inc) {
            return callNode.call(inc.target(), new Object[]{inc.canonicalPath()});
        }
        return prepared;
    }

    @TruffleBoundary
    private Object prepare(String path) {
        PhpContext ctx = PhpContext.get(this);
        boolean once = kind == Kind.INCLUDE_ONCE || kind == Kind.REQUIRE_ONCE;
        // Fast path: the same path string from the same file resolved recently. No stat,
        // realpath or parse-cache lookup; WordPress does ~500 includes per request.
        String cacheKey = includeKey(ctx, path);
        PhpLanguage.ResolvedInclude hit = cacheKey == null ? null : ctx.language().cachedInclude(cacheKey);
        if (hit != null) {
            if (once && ctx.isIncluded(hit.canonicalPath())) {
                return Boolean.TRUE;
            }
            ctx.markIncluded(hit.canonicalPath());
            return new PreparedInclude(hit.target(), hit.canonicalPath());
        }
        TruffleLanguage.Env env = ctx.env();
        TruffleFile resolved = resolveFile(env, path);
        if (resolved == null) {
            return missing(ctx, path);
        }
        TruffleFile canonical;
        try {
            canonical = resolved.getCanonicalFile();
        } catch (IOException e) {
            canonical = resolved.getAbsoluteFile().normalize();
        }
        String key = canonical.getPath();
        if (once && ctx.isIncluded(key)) {
            return Boolean.TRUE;
        }
        ctx.markIncluded(key);
        Source source;
        try {
            source = ctx.language().loadSource(canonical);
        } catch (IOException e) {
            return missing(ctx, path);
        }
        CallTarget target;
        try {
            target = env.parsePublic(source);
        } catch (dev.phpruntime.parser.PhpParseError pe) {
            String msg = pe.getMessage();
            throw new PhpError(PhpError.PARSE_ERROR, msg != null ? msg : "syntax error",
                    source.getPath() != null ? source.getPath() : key, pe.line(), this);
        }
        if (cacheKey != null) {
            ctx.language().cacheInclude(cacheKey, key, target);
        }
        return new PreparedInclude(target, key);
    }

    /**
     * What an include of {@code path} from this node resolves against: the path itself when
     * absolute, else the including file and the working directory too. Null if uncacheable.
     */
    private String includeKey(PhpContext ctx, String path) {
        if (path.isEmpty()) {
            return null;
        }
        if (path.charAt(0) == '/') {
            return path;
        }
        RootNode root = getRootNode();
        String srcPath = root instanceof PhpRootNode r && r.source() != null ? r.source().getPath() : null;
        if (srcPath == null) {
            return null;
        }
        return srcPath + '\0' + path + '\0' + ctx.env().getCurrentWorkingDirectory().getPath();
    }

    private Object missing(PhpContext ctx, String path) {
        if (kind == Kind.REQUIRE || kind == Kind.REQUIRE_ONCE) {
            throw new PhpError("Failed opening required '" + path + "'", this);
        }
        ctx.warning("include(" + path + "): Failed to open stream: No such file or directory", line());
        ctx.warning("include(): Failed opening '" + path + "' for inclusion", line());
        return Boolean.FALSE;
    }

    private TruffleFile resolveFile(TruffleLanguage.Env env, String path) {
        if (path.isEmpty()) {
            return null;
        }
        try {
            path = PhpStrings.toJava(path);
            TruffleFile direct = env.getPublicTruffleFile(path);
            if (direct.isAbsolute()) {
                return isRegularFile(direct) ? direct : null;
            }
            RootNode root = getRootNode();
            String srcPath = root instanceof PhpRootNode r && r.source() != null ? r.source().getPath() : null;
            if (srcPath != null && !srcPath.isEmpty()) {
                TruffleFile parent = env.getPublicTruffleFile(srcPath).getParent();
                if (parent != null) {
                    TruffleFile inDir = parent.resolve(path);
                    if (isRegularFile(inDir)) {
                        return inDir;
                    }
                }
            }
            TruffleFile inCwd = env.getCurrentWorkingDirectory().resolve(path);
            if (isRegularFile(inCwd)) {
                return inCwd;
            }
        } catch (SecurityException | IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    private static boolean isRegularFile(TruffleFile file) {
        try {
            return file.isRegularFile();
        } catch (SecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Attempts {@code include_once} relative to the entry script or cwd without warnings (for {@code spl_autoload}). */
    @TruffleBoundary
    public static boolean tryIncludeOnce(PhpContext ctx, String path) {
        TruffleLanguage.Env env = ctx.env();
        TruffleFile resolved = resolveStatic(env, ctx.request().scriptFilename, path);
        if (resolved == null) {
            return false;
        }
        TruffleFile canonical;
        try {
            canonical = resolved.getCanonicalFile();
        } catch (IOException e) {
            canonical = resolved.getAbsoluteFile().normalize();
        }
        String key = canonical.getPath();
        if (ctx.isIncluded(key)) {
            return true;
        }
        ctx.markIncluded(key);
        try {
            Source source = ctx.language().loadSource(canonical);
            CallTarget target = env.parsePublic(source);
            target.call(new Object[]{key});
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static TruffleFile resolveStatic(TruffleLanguage.Env env, String scriptFilename, String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            path = PhpStrings.toJava(path);
            if (scriptFilename != null && !scriptFilename.isEmpty()) {
                TruffleFile parent = env.getPublicTruffleFile(scriptFilename).getParent();
                if (parent != null) {
                    TruffleFile inDir = parent.resolve(path);
                    if (isRegularFile(inDir)) {
                        return inDir;
                    }
                }
            }
            TruffleFile inCwd = env.getCurrentWorkingDirectory().resolve(path);
            if (isRegularFile(inCwd)) {
                return inCwd;
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }
}
