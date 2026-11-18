package dev.phpruntime.nodes.var;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpError;
import dev.phpruntime.runtime.PhpReference;

/**
 * {@code list($a, $b)} or {@code [$a, 'k' => &$b, [, $c]]} destructuring target. The
 * bytecode emitter walks the pattern; it has no execution semantics of its own.
 */
public final class DestructureLValueNode extends LValueNode {

    @Children private final PhpNode[] keyNodes;
    @Children private final LValueNode[] targetNodes;
    @CompilationFinal(dimensions = 1) private final long[] autoIndices;
    @CompilationFinal(dimensions = 1) private final boolean[] byRef;
    private final boolean hasAnyRef;

    public DestructureLValueNode(PhpNode[] keyNodes, LValueNode[] targetNodes, long[] autoIndices, boolean[] byRef) {
        this.keyNodes = keyNodes;
        this.targetNodes = targetNodes;
        this.autoIndices = autoIndices;
        this.byRef = byRef;
        boolean anyRef = false;
        for (int i = 0; i < targetNodes.length; i++) {
            if (byRef[i] || (targetNodes[i] instanceof DestructureLValueNode sub && sub.hasAnyRef())) {
                anyRef = true;
                break;
            }
        }
        this.hasAnyRef = anyRef;
    }

    public boolean hasAnyRef() {
        return hasAnyRef;
    }

    public PhpNode[] keyNodes() {
        return keyNodes;
    }

    public LValueNode[] targetNodes() {
        return targetNodes;
    }

    public long[] autoIndices() {
        return autoIndices;
    }

    public boolean[] byRef() {
        return byRef;
    }

    @Override
    public Object read(VirtualFrame frame, Object[] keys) {
        com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
        throw new PhpError("Cannot use list() / array destructuring in read context", this);
    }

    @Override
    public void write(VirtualFrame frame, Object[] keys, Object value) {
        throw new IllegalStateException("AST destructuring execution removed");
    }

    @Override
    public void bindCell(VirtualFrame frame, Object[] keys, PhpReference cell) {
        throw new IllegalStateException("AST destructuring execution removed");
    }

    @Override
    public void unset(VirtualFrame frame, Object[] keys) {
        com.oracle.truffle.api.CompilerDirectives.transferToInterpreter();
        throw new PhpError("Cannot unset destructuring pattern", this);
    }

    @Override
    public String describe() {
        return "[...]";
    }
}
