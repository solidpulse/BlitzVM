package dev.phpruntime.nodes.expr;

import dev.phpruntime.nodes.PhpNode;

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

    public ClassRefNode classRef() {
        return classRef;
    }

    public String constName() {
        return constName;
    }

    @Override
    public void emit(dev.phpruntime.bytecode.BytecodeEmitter e) {
        if (literalClassName) {
            e.builder().emitLoadConstant(classRef.staticName());
            return;
        }
        e.builder().beginClassConst(constName);
        e.classRef(classRef);
        e.builder().endClassConst();
    }
}
