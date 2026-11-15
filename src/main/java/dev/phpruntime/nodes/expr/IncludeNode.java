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
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.nodes.PhpRootNode;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpOps;

import java.io.IOException;

/** {@code include}, {@code include_once}, {@code require}, {@code require_once}. */
public final class IncludeNode extends PhpNode {

    public enum Kind { INCLUDE, INCLUDE_ONCE, REQUIRE, REQUIRE_ONCE }

    private static final Object[] INCLUDED_ARGS = {Boolean.TRUE};

    private final Kind kind;
    @Child private PhpNode pathExpr;
    @Child private IndirectCallNode callNode = Truffle.getRuntime().createIndirectCallNode();

    public IncludeNode(Kind kind, PhpNode pathExpr) {
        this.kind = kind;
        this.pathExpr = pathExpr;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        String path = PhpOps.toStr(pathExpr.execute(frame));
        // TODO(dynamic-scope): PHP runs includes inside functions in the caller's local scope; we run in global scope.
        Object prepared = prepare(path);
        if (prepared instanceof CallTarget target) {
            return callNode.call(target, INCLUDED_ARGS);
        }
        return prepared;
    }

    @TruffleBoundary
    private Object prepare(String path) {
        PhpContext ctx = PhpContext.get(this);
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
        boolean once = kind == Kind.INCLUDE_ONCE || kind == Kind.REQUIRE_ONCE;
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
        return env.parsePublic(source);
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
}
