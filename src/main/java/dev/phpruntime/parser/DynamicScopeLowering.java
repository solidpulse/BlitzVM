package dev.phpruntime.parser;

import com.oracle.truffle.api.nodes.Node;
import dev.phpruntime.nodes.PhpRootNode;
import dev.phpruntime.nodes.var.LocalVarNode;
import dev.phpruntime.nodes.var.ScopedVarNode;
import dev.phpruntime.nodes.stmt.GlobalImportNode;
import dev.phpruntime.nodes.stmt.StaticDeclNode;
import dev.phpruntime.runtime.PhpScopeLayout;

/** Parse-time specialization after the whole body is known, without scanning nested functions. */
final class DynamicScopeLowering {
    static <T extends PhpRootNode> T finish(T root) {
        if (root.getFrameDescriptor().getInfo() instanceof PhpScopeLayout layout && !layout.included()) {
            root.adoptChildren();
            lower(root, layout.tableSlot());
        }
        return root;
    }

    private static void lower(Node node, int tableSlot) {
        if (node instanceof LocalVarNode local) {
            local.replace(new ScopedVarNode(tableSlot, local.slot(), local.varName()).at(local.line()));
            return;
        }
        if (node instanceof GlobalImportNode global) { global.dynamicScope(tableSlot); }
        if (node instanceof StaticDeclNode statics) { statics.dynamicScope(tableSlot); }
        // Nested function/closure targets are not children: only this body's locals are lowered.
        for (Node child : node.getChildren()) { lower(child, tableSlot); }
    }
}
