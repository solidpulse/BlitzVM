package dev.phpruntime.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import dev.phpruntime.PhpContext;
import dev.phpruntime.bytecode.PhpBytecodeRootNode;
import dev.phpruntime.nodes.PhpFunctionRootNode;
import dev.phpruntime.nodes.PhpMainRootNode;
import dev.phpruntime.nodes.PhpRootNode;

/**
 * The file a call came from: its {@code strict_types} setting and the source location of the
 * call. Parameter checks use the caller's mode, returns the declaring file's. Internal calls
 * (builtin callbacks and the like) carry no source location.
 */
public final class CallMode {
    public static final CallMode INTERNAL = new CallMode(false, null, 0, null);

    private final boolean strict;
    private final PhpFunctionRootNode target;
    /** The builtin call site whose location is worked out only when a message needs it. */
    private final Node site;
    private boolean located;
    private String file;
    private int line;

    public CallMode(boolean strict, String file, int line, PhpFunctionRootNode target) {
        this.strict = strict;
        this.file = file;
        this.line = line;
        this.target = target;
        this.site = null;
        this.located = true;
    }

    private CallMode(boolean strict, Node site, PhpFunctionRootNode target) {
        this.strict = strict;
        this.target = target;
        this.site = site;
    }

    /** A weak internal call into {@code target} (array_map and similar callbacks). */
    public static CallMode internal(PhpFunctionRootNode target) {
        return target == null ? INTERNAL : target.internalCallMode();
    }

    public boolean strict() {
        return strict;
    }

    public PhpFunctionRootNode target() {
        return target;
    }

    public String file() {
        locate();
        return file;
    }

    public int line() {
        locate();
        return line;
    }

    @TruffleBoundary
    private void locate() {
        if (located) {
            return;
        }
        located = true;
        SourceSection section = site == null ? null : site.getEncapsulatingSourceSection();
        if (section != null) {
            if (section.getSource() != null && section.getSource().getPath() != null) {
                file = PhpStrings.fromJava(section.getSource().getPath());
            }
            line = section.getStartLine();
        } else if (site != null) {
            String path = PhpBacktrace.sourcePath(site);
            file = path.isEmpty() ? null : path;
            line = PhpBacktrace.sourceLine(site);
        }
    }

    /**
     * The mode of the code calling {@code root}: the builtin callback mode set for this exact
     * call when there is one, else the innermost PHP frame below the callee.
     */
    @TruffleBoundary
    public static CallMode of(PhpBytecodeRootNode root, Node site) {
        PhpContext ctx = PhpContext.get(site);
        CallMode pending = ctx.pendingCallMode();
        if (pending != null && (pending.target() == null || pending.target() == root.function())) {
            return pending;
        }
        CallMode[] found = new CallMode[1];
        boolean[] skippedCallee = new boolean[1];
        Truffle.getRuntime().iterateFrames(fi -> {
            if (!(fi.getCallTarget() instanceof RootCallTarget target)) {
                return null;
            }
            RootNode frameRoot = target.getRootNode();
            if (!PhpFunctionRootNode.isGuestFrame(frameRoot)) {
                return null;
            }
            if (!skippedCallee[0]) {
                skippedCallee[0] = true;
                return null;
            }
            PhpFunctionRootNode fn = PhpFunctionRootNode.ofFrame(frameRoot);
            boolean main = PhpMainRootNode.isMain(frameRoot);
            if (fn == null && !main) {
                // A wrapper root (Closure::__invoke and friends): keep looking for real code.
                return null;
            }
            boolean strict = strictness(frameRoot);
            SourceSection section = PhpBytecodeRootNode.callSiteSection(fi);
            Node callNode = fi.getCallNode();
            String file = null;
            int line = 0;
            if (section != null) {
                if (section.getSource() != null && section.getSource().getPath() != null) {
                    file = PhpStrings.fromJava(section.getSource().getPath());
                }
                line = section.getStartLine();
            } else if (callNode != null) {
                String path = PhpBacktrace.sourcePath(callNode);
                file = path.isEmpty() ? null : path;
                line = PhpBacktrace.sourceLine(callNode);
            }
            found[0] = new CallMode(strict, file, line, null);
            return Boolean.TRUE;
        });
        return found[0] != null ? found[0] : INTERNAL;
    }

    /** The strict_types flag of an executing root, wrapper roots included. */
    private static boolean strictness(RootNode root) {
        if (root instanceof PhpRootNode php) {
            return php.strictTypes();
        }
        if (root instanceof PhpBytecodeRootNode bytecode) {
            return bytecode.strictTypes();
        }
        if (root instanceof com.oracle.truffle.api.bytecode.ContinuationRootNode continuation
                && continuation.getSourceRootNode() instanceof PhpBytecodeRootNode bytecode) {
            return bytecode.strictTypes();
        }
        return false;
    }

    /**
     * The strict_types setting of the PHP code that called internal code at {@code site}. The
     * caller is the first guest frame above this one; internal roots are not guest frames.
     */
    @TruffleBoundary
    public static boolean strictOfCaller(Node site) {
        PhpContext ctx = PhpContext.get(site);
        CallMode pending = ctx.pendingCallMode();
        if (pending != null && pending.target() == null) {
            return pending.strict();
        }
        boolean[] strict = {false};
        Truffle.getRuntime().iterateFrames(fi -> {
            if (!(fi.getCallTarget() instanceof RootCallTarget target)) {
                return null;
            }
            RootNode frameRoot = target.getRootNode();
            if (!PhpFunctionRootNode.isGuestFrame(frameRoot)) {
                return null;
            }
            strict[0] = strictness(frameRoot);
            return Boolean.TRUE;
        });
        return strict[0];
    }

    /** The mode for a call made directly by the code at {@code site}. */
    public static boolean strictAt(Node site) {
        RootNode root = site.getRootNode();
        return root instanceof PhpRootNode php ? php.strictTypes()
                : root instanceof PhpBytecodeRootNode bytecode && bytecode.strictTypes();
    }

    /**
     * The mode of the PHP code that called a builtin at {@code site}: used by call_user_func,
     * which forwards its caller's mode to the callback (array_map and friends do not).
     */
    public static CallMode atBuiltin(Node site, PhpFunctionRootNode target) {
        return new CallMode(site != null && strictAt(site), site, target);
    }
}
