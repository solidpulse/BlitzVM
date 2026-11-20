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
public record CallMode(boolean strict, String file, int line, PhpFunctionRootNode target) {

    public static final CallMode INTERNAL = new CallMode(false, null, 0, null);

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
    @TruffleBoundary
    public static CallMode atBuiltin(Node site, PhpFunctionRootNode target) {
        SourceSection section = site == null ? null : site.getEncapsulatingSourceSection();
        String file = null;
        int line = 0;
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
        return new CallMode(site != null && strictAt(site), file, line, target);
    }
}
