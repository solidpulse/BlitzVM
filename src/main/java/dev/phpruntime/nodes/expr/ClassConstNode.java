package dev.phpruntime.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import dev.phpruntime.nodes.PhpNode;
import dev.phpruntime.runtime.PhpClass;
import dev.phpruntime.runtime.PhpError;

/** {@code Foo::CONST}, {@code self::CONST}, {@code static::CONST}, {@code parent::CONST}, {@code $obj::class}. */
public final class ClassConstNode extends PhpNode {

    @Child private ClassRefNode classRef;
    private final String constName;
    private final boolean literalClassName;

    public ClassConstNode(ClassRefNode classRef, String constName) {
        this.classRef = classRef;
        this.constName = constName;
        this.literalClassName = constName.equalsIgnoreCase("class") && classRef.kind() == ClassRefNode.Kind.NAMED;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (literalClassName) {
            return classRef.staticName();
        }
        PhpClass cls = classRef.resolve(frame);
        Object val = cls.lookupConstant(constName);
        if (val == null) {
            throw new PhpError("Undefined constant " + cls.name() + "::" + constName, this);
        }
        return val;
    }
}
