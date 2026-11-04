package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.dsl.NodeChild;
import dev.phpruntime.nodes.PhpNode;

/** Base for DSL-specialised binary operators; children are evaluated left to right. */
@NodeChild("left")
@NodeChild("right")
public abstract class BinaryNode extends PhpNode {
}
