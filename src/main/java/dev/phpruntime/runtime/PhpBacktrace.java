package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.PhpContext;
import dev.phpruntime.nodes.PhpMainRootNode;
import dev.phpruntime.nodes.PhpRootNode;
import dev.phpruntime.nodes.expr.IncludeNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Stack frame capture and PHP-compatible trace formatting for exceptions, engine errors,
 * {@code debug_backtrace()} and {@code debug_print_backtrace()}.
 */
public final class PhpBacktrace {

    public static final long PROVIDE_OBJECT = 1L;
    public static final long IGNORE_ARGS = 2L;

    public record FrameEntry(String file, int line, String function, String className,
                             String type, PhpObject object, Object[] args) {
    }

    /** Lazy holder stored inside an exception's private {@code $trace} slot until inspected. */
    public static final class LazyTrace {
        private final FrameEntry[] frames;
        private PhpArray materialized;

        public LazyTrace(FrameEntry[] frames) {
            this.frames = frames;
        }

        public FrameEntry[] frames() {
            return frames;
        }

        @TruffleBoundary
        public PhpArray toPhpArray() {
            if (materialized == null) {
                materialized = buildTraceArray(frames, false, true);
            }
            return materialized;
        }
    }

    private PhpBacktrace() {
    }

    /**
     * Captures the current guest call stack starting at {@code topLocation} (for example the
     * {@code new} node or engine error location). Does not allocate {@link PhpArray} maps until
     * {@code getTrace()} or {@code var_dump()} inspects the trace.
     */
    @TruffleBoundary
    public static LazyTrace capture(Node topLocation, boolean includeArgs) {
        return new LazyTrace(walkFrames(topLocation, includeArgs, false, 0));
    }

    /** Captures a guest stack trace whose frame #0 is the active builtin call {@code builtinName}. */
    @TruffleBoundary
    public static LazyTrace captureFromBuiltin(Node callSite, String builtinName) {
        FrameEntry[] callerFrames = walkFrames(callSite, false, false, 0);
        String file = sourcePath(callSite);
        int line = sourceLine(callSite);
        FrameEntry[] all = new FrameEntry[callerFrames.length + 1];
        all[0] = new FrameEntry(file, line, builtinName, null, null, null, null);
        System.arraycopy(callerFrames, 0, all, 1, callerFrames.length);
        return new LazyTrace(all);
    }

    /**
     * The marker for a frame whose call site is not guest code: a callback invoked by a builtin,
     * which php reports as {@code [internal function]}.
     */
    public static final Object INTERNAL_SITE = new Object();

    /**
     * Snapshots the frames of {@link PhpContext}'s call-site stack, which every guest call
     * pushes and pops. This is what an exception created in guest code uses: php records the
     * call sites at object creation, and reading them from the stack the runtime already keeps
     * costs a store per call instead of a Truffle frame walk, which costs microseconds from
     * compiled code.
     *
     * <p>Each entry is the call site of one active call. Its callee is the function owning the
     * next inner entry's site (or, for the innermost entry, the function at {@code location}),
     * so an entry is one reference.
     */
    @TruffleBoundary
    public static LazyTrace captureFromContext(PhpContext ctx, Node location) {
        return new LazyTrace(framesFromContext(ctx, location, 0));
    }

    /** The innermost {@code limit} frames of the call-site stack (all of them for 0). */
    private static FrameEntry[] framesFromContext(PhpContext ctx, Node location, int limit) {
        int depth = ctx.traceDepth();
        if (depth == 0) {
            return new FrameEntry[0];
        }
        FrameEntry[] out = new FrameEntry[limit > 0 ? Math.min(limit, depth) : depth];
        int n = 0;
        Object callee = frameOfRoot(location == null ? null : location.getRootNode());
        for (int i = depth - 1; i >= 0 && n < out.length; i--) {
            Object site = ctx.traceSite(i);
            FrameEntry entry = traceEntry(site, callee);
            if (entry != null) {
                out[n++] = entry;
            }
            callee = frameOfSite(site);
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }

    /** A file's top-level code, which has no function metadata of its own. */
    private static final Object MAIN = new Object();

    private static Object frameOfSite(Object site) {
        if (site instanceof TraceSite ts) {
            site = ts.site();
        }
        if (site instanceof Node node) {
            return frameOfRoot(node.getRootNode());
        }
        return null;
    }

    private static Object frameOfRoot(RootNode root) {
        if (root instanceof dev.phpruntime.bytecode.PhpBytecodeRootNode b) {
            return b.function() != null ? b.function() : MAIN;
        }
        if (root instanceof PhpFunctionRootNode fn) {
            return fn;
        }
        if (root instanceof PhpMainRootNode) {
            return MAIN;
        }
        return null;
    }

    /** A site that also carries what a method's class or a closure's binding is at run time. */
    public record TraceSite(Node site, PhpClass traitSelf, Boolean boundThis) {
        public TraceSite(Node site, PhpClass traitSelf) {
            this(site, traitSelf, null);
        }
    }

    /**
     * {@code site}, wrapped when a closure runs with a different {@code $this} binding than its
     * declaration implies ({@code Closure::bind}, {@code Closure::call}).
     */
    public static Object bindingSite(Object site, PhpFunctionRootNode fn, boolean boundThis) {
        if (fn != null && fn.isClosure() && fn.closureBoundThis() != boundThis && site instanceof Node node) {
            return new TraceSite(node, null, boundThis);
        }
        return site;
    }

    private static FrameEntry traceEntry(Object site, Object callee) {
        if (callee == MAIN) {
            if (site instanceof dev.phpruntime.nodes.expr.IncludeNode include) {
                return new FrameEntry(sourcePath(include), sourceLine(include),
                        include.kind().name().toLowerCase(java.util.Locale.ROOT), null, null, null, null);
            }
            if (site instanceof Node node) {
                return new FrameEntry(sourcePath(node), sourceLine(node), "eval", null, null, null, null);
            }
            return null;
        }
        if (!(callee instanceof PhpFunctionRootNode fn)) {
            return null;
        }
        String file = null;
        int line = 0;
        PhpClass traitSelf = null;
        Boolean boundThis = null;
        Object node = site;
        if (site instanceof TraceSite ts) {
            traitSelf = ts.traitSelf();
            boundThis = ts.boundThis();
            node = ts.site();
        }
        if (node instanceof Node n) {
            file = sourcePath(n);
            line = sourceLine(n);
        }
        String type = boundThis != null
                ? (fn.declaringClass() == null ? null : (boundThis ? "->" : "::"))
                : traceType(fn);
        return new FrameEntry(file, line, traceName(fn), traceClass(fn, traitSelf), type, null, null);
    }

    /** The function name php prints for a frame: closures carry their declaration site. */
    private static String traceName(PhpFunctionRootNode fn) {
        return fn.isClosure() ? fn.qualifiedCallableName() : fn.rawFunctionName();
    }

    private static String traceClass(PhpFunctionRootNode fn, PhpClass traitSelf) {
        return traitSelf != null ? traitSelf.name() : fn.declaringClass();
    }

    private static String traceType(PhpFunctionRootNode fn) {
        if (fn.isClosure()) {
            return fn.declaringClass() == null ? null : (fn.closureBoundThis() ? "->" : "::");
        }
        if (fn.isMethod()) {
            return fn.isStaticMethod() ? "::" : "->";
        }
        return null;
    }

    /**
     * {@code debug_backtrace()} frames without arguments or objects, read from the call-site
     * stack instead of a Truffle frame walk: the same frames an exception created at the
     * builtin's call site records, which is what php returns.
     */
    @TruffleBoundary
    public static FrameEntry[] captureDebugFromContext(PhpContext ctx, int limit) {
        return framesFromContext(ctx, ctx.currentBuiltinCallSite(), limit);
    }

    /** Captures frames for {@code debug_backtrace($options, $limit)} and {@code debug_print_backtrace()}. */
    @TruffleBoundary
    public static FrameEntry[] captureDebug(long options, int limit) {
        boolean includeArgs = (options & IGNORE_ARGS) == 0;
        boolean includeObject = (options & PROVIDE_OBJECT) != 0;
        return walkFrames(null, includeArgs, includeObject, limit);
    }

    private record PendingCall(String function, String className, String type, PhpObject object, Object[] args) {
    }

    @TruffleBoundary
    private static FrameEntry[] walkFrames(Node startLocation, boolean includeArgs, boolean includeObject, int limit) {
        List<FrameEntry> out = new ArrayList<>(8);
        PendingCall[] pending = new PendingCall[1];
        boolean[] started = new boolean[]{startLocation != null};

        Truffle.getRuntime().iterateFrames(fi -> {
            if (!(fi.getCallTarget() instanceof RootCallTarget rct)) {
                return null;
            }
            RootNode root = rct.getRootNode();
            if (!dev.phpruntime.nodes.PhpFunctionRootNode.isGuestFrame(root)) {
                // Skip Java-implemented built-in method RootNodes (e.g. Exception::__construct).
                return null;
            }
            if (!started[0]) {
                // Builtin call (debug_backtrace / debug_print_backtrace): the topmost guest frame
                // IS the function containing the debug_backtrace() call, so start from it.
                started[0] = true;
            } else if (pending[0] != null) {
                PendingCall pc = pending[0];
                Node callNode = fi.getCallNode();
                SourceSection bcSite = root instanceof dev.phpruntime.bytecode.PhpBytecodeRootNode
                        || root instanceof com.oracle.truffle.api.bytecode.ContinuationRootNode
                        ? dev.phpruntime.bytecode.PhpBytecodeRootNode.callSiteSection(fi) : null;
                String file = bcSite != null ? sectionPath(bcSite, callNode) : sourcePath(callNode);
                int line = bcSite != null ? bcSite.getStartLine() : sourceLine(callNode);
                String fnName = pc.function();
                if (fnName == null) {
                    IncludeNode inc = findIncludeNode(callNode);
                    fnName = inc != null ? includeKindName(inc.kind()) : "include";
                }
                out.add(new FrameEntry(file, line, fnName, pc.className(), pc.type(), pc.object(), pc.args()));
                pending[0] = null;
                if (limit > 0 && out.size() >= limit) {
                    return Boolean.TRUE;
                }
            }

            if (dev.phpruntime.nodes.PhpFunctionRootNode.ofFrame(root) instanceof PhpFunctionRootNode fnRoot) {
                Frame f = dev.phpruntime.nodes.PhpFunctionRootNode.functionFrame(root,
                        fi.getFrame(FrameInstance.FrameAccess.READ_ONLY));
                Object[] rawArgs = f.getArguments();
                if (fnRoot.isClosure()) {
                    Object thisObj = rawArgs.length > 0 ? rawArgs[0] : PhpNull.INSTANCE;
                    PhpObject recv = thisObj instanceof PhpObject o ? o : null;
                    String clsName = fnRoot.declaringClass();
                    if (clsName == null && rawArgs.length > 1 && rawArgs[1] instanceof PhpClass c) {
                        clsName = c.name();
                    }
                    String type = clsName != null ? (recv != null ? "->" : "::") : null;
                    Object[] callArgs = includeArgs && rawArgs.length > 3
                            ? Arrays.copyOfRange(rawArgs, 3, rawArgs.length)
                            : (includeArgs ? new Object[0] : null);
                    pending[0] = new PendingCall(
                            fnRoot.rawFunctionName(), clsName, type,
                            includeObject && clsName != null ? recv : null, callArgs);
                } else if (fnRoot.isMethod()) {
                    Object thisObj = rawArgs.length > 0 ? rawArgs[0] : PhpNull.INSTANCE;
                    PhpObject recv = thisObj instanceof PhpObject o ? o : null;
                    String clsName = fnRoot.declaringClass();
                    if (fnRoot.traitSelfSlot() >= 0 && rawArgs.length > 2 && rawArgs[2] instanceof PhpClass ts) {
                        clsName = ts.name();
                    } else if (clsName == null && rawArgs.length > 1 && rawArgs[1] instanceof PhpClass c) {
                        clsName = c.name();
                    }
                    String type = recv != null ? "->" : "::";
                    int off = fnRoot.argOffset();
                    Object[] callArgs = includeArgs && rawArgs.length > off
                            ? Arrays.copyOfRange(rawArgs, off, rawArgs.length)
                            : (includeArgs ? new Object[0] : null);
                    pending[0] = new PendingCall(
                            fnRoot.rawFunctionName(), clsName, type,
                            includeObject ? recv : null, callArgs);
                } else {
                    Object[] callArgs = includeArgs
                            ? Arrays.copyOf(rawArgs, rawArgs.length)
                            : null;
                    pending[0] = new PendingCall(fnRoot.rawFunctionName(), null, null, null, callArgs);
                }
            } else if (PhpMainRootNode.isMain(root)) {
                Frame f = fi.getFrame(FrameInstance.FrameAccess.READ_ONLY);
                Object[] rawArgs = f.getArguments();
                if (!PhpMainRootNode.isEntryArgs(rawArgs)) {
                    Object incPath = rawArgs[0];
                    pending[0] = new PendingCall(null, null, null, null,
                            includeArgs ? new Object[]{incPath instanceof String s ? PhpStrings.fromJava(s) : incPath} : null);
                } else {
                    // Entry {main} reached.
                    return Boolean.TRUE;
                }
            }
            return null;
        });
        return out.toArray(new FrameEntry[0]);
    }

    private static IncludeNode findIncludeNode(Node n) {
        for (Node cur = n; cur != null; cur = cur.getParent()) {
            if (cur instanceof IncludeNode inc) {
                return inc;
            }
            if (cur instanceof RootNode) {
                break;
            }
        }
        return null;
    }

    @TruffleBoundary
    public static PhpArray buildTraceArray(FrameEntry[] frames, boolean provideObject, boolean omitFunctionArgs) {
        PhpArray list = new PhpArray();
        for (FrameEntry f : frames) {
            PhpArray row = new PhpArray();
            if (f.file() != null && !f.file().isEmpty()) {
                row.put("file", f.file());
            }
            if (f.line() > 0) {
                row.put("line", (long) f.line());
            }
            boolean isInclude = isIncludeName(f.function());
            if (isInclude && !omitFunctionArgs && f.args() != null) {
                row.put("args", argsToPhpArray(f.args()));
                row.put("function", f.function());
            } else {
                row.put("function", f.function());
                if (f.className() != null) {
                    row.put("class", f.className());
                    if (provideObject && f.object() != null) {
                        row.put("object", f.object());
                    }
                    row.put("type", f.type());
                }
                if (!omitFunctionArgs && f.args() != null) {
                    row.put("args", argsToPhpArray(f.args()));
                }
            }
            list.append(row);
        }
        return list;
    }

    private static PhpArray argsToPhpArray(Object[] args) {
        PhpArray a = new PhpArray();
        for (Object v : args) {
            if (v instanceof PhpReference ref) {
                v = ref.getValue() == null ? PhpNull.INSTANCE : ref.getValue();
            }
            a.append(PhpOps.copyOnAssign(v));
        }
        return a;
    }

    private static boolean isIncludeName(String fn) {
        return "include".equals(fn) || "include_once".equals(fn)
                || "require".equals(fn) || "require_once".equals(fn);
    }

    @TruffleBoundary
    public static String formatTraceAsString(FrameEntry[] frames) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (FrameEntry f : frames) {
            sb.append('#').append(i++).append(' ');
            if (f.file() != null && !f.file().isEmpty()) {
                sb.append(f.file()).append('(').append(f.line()).append("): ");
            } else {
                sb.append("[internal function]: ");
            }
            if (f.className() != null) {
                sb.append(f.className()).append(f.type());
            }
            sb.append(f.function()).append('(');
            if (isIncludeName(f.function()) && f.args() != null && f.args().length > 0) {
                sb.append(formatTraceArg(f.args()[0]));
            }
            sb.append(')').append((char) 10);
        }
        sb.append('#').append(i).append(" {main}");
        return sb.toString();
    }

    /** Formats a trace array (or {@link LazyTrace}) into PHP's {@code getTraceAsString()} text. */
    @TruffleBoundary
    public static String formatTraceValue(Object traceVal) {
        if (traceVal instanceof LazyTrace lt) {
            return formatTraceAsString(lt.frames());
        }
        if (traceVal instanceof PhpArray arr) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (Object item : arr.values()) {
                if (!(item instanceof PhpArray row)) {
                    continue;
                }
                String file = row.containsKey("file") ? PhpOps.toStr(row.get("file")) : "";
                long line = row.containsKey("line") ? PhpOps.toLong(row.get("line")) : 0;
                String fn = row.containsKey("function") ? PhpOps.toStr(row.get("function")) : "";
                String cls = row.containsKey("class") ? PhpOps.toStr(row.get("class")) : null;
                String type = row.containsKey("type") ? PhpOps.toStr(row.get("type")) : "";
                sb.append('#').append(i++).append(' ');
                if (!file.isEmpty()) {
                    sb.append(file).append('(').append(line).append("): ");
                } else {
                    sb.append("[internal function]: ");
                }
                if (cls != null) {
                    sb.append(cls).append(type);
                }
                sb.append(fn).append('(');
                if (isIncludeName(fn) && row.containsKey("args") && row.get("args") instanceof PhpArray incArgs
                        && incArgs.size() > 0) {
                    sb.append(formatTraceArg(incArgs.values().get(0)));
                }
                sb.append(')').append((char) 10);
            }
            sb.append('#').append(i).append(" {main}");
            return sb.toString();
        }
        return "#0 {main}";
    }

    @TruffleBoundary
    public static String formatDebugPrintBacktrace(FrameEntry[] frames, boolean ignoreArgs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < frames.length; i++) {
            FrameEntry f = frames[i];
            sb.append('#').append(i).append(' ');
            if (f.file() != null && !f.file().isEmpty()) {
                sb.append(f.file()).append('(').append(f.line()).append("): ");
            } else {
                sb.append("[internal function]: ");
            }
            if (f.className() != null) {
                sb.append(f.className()).append(f.type());
            }
            sb.append(f.function()).append('(');
            if (!ignoreArgs && f.args() != null) {
                for (int j = 0; j < f.args().length; j++) {
                    if (j > 0) {
                        sb.append(", ");
                    }
                    sb.append(formatDebugArg(f.args()[j]));
                }
            }
            sb.append(')').append((char) 10);
        }
        return sb.toString();
    }

    private static String formatTraceArg(Object v) {
        if (v instanceof String s) {
            return s.isEmpty() ? "''" : "'...'";
        }
        return formatDebugArg(v);
    }

    private static String formatDebugArg(Object v) {
        if (v instanceof PhpReference ref) {
            v = ref.getValue();
        }
        if (v == null || v == PhpNull.INSTANCE) {
            return "NULL";
        }
        if (v instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (v instanceof Long l) {
            return Long.toString(l);
        }
        if (v instanceof Double d) {
            return PhpOps.formatDouble(d, 14);
        }
        if (v instanceof String s) {
            return s.isEmpty() ? "''" : "'...'";
        }
        if (v instanceof PhpArray) {
            return "Array";
        }
        if (v instanceof PhpObject o) {
            return "Object(" + o.phpClass().name() + ")";
        }
        return String.valueOf(v);
    }

    public static String sourcePath(Node location) {
        if (location == null) {
            return "";
        }
        SourceSection ss = location.getEncapsulatingSourceSection();
        if (ss != null && ss.getSource() != null && ss.getSource().getPath() != null) {
            return PhpStrings.fromJava(ss.getSource().getPath());
        }
        RootNode root = location.getRootNode();
        if (root instanceof PhpRootNode pr && pr.source() != null && pr.source().getPath() != null) {
            return PhpStrings.fromJava(pr.source().getPath());
        }
        return "";
    }

    private static String sectionPath(SourceSection ss, Node fallback) {
        if (ss.getSource() != null && ss.getSource().getPath() != null) {
            return PhpStrings.fromJava(ss.getSource().getPath());
        }
        return sourcePath(fallback);
    }

    /** The line of a builtin's call site: an AST node's own line, or a bytecode site's section. */
    public static int callSiteLine(Node site) {
        if (site instanceof dev.phpruntime.nodes.PhpNode node) {
            return Math.max(0, node.line());
        }
        return Math.max(0, sourceLine(site));
    }

    public static int sourceLine(Node location) {
        if (location == null) {
            return 0;
        }
        SourceSection ss = location.getEncapsulatingSourceSection();
        if (ss != null) {
            return ss.getStartLine();
        }
        return 0;
    }

    private static String includeKindName(IncludeNode.Kind kind) {
        return switch (kind) {
            case INCLUDE -> "include";
            case INCLUDE_ONCE -> "include_once";
            case REQUIRE -> "require";
            case REQUIRE_ONCE -> "require_once";
        };
    }
}
