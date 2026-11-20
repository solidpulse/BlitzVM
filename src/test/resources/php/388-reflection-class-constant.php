<?php
class ConstantBase {
    private const BASE_PRIVATE = 'base-private';
    protected const BASE_PROTECTED = 'base-protected';
    public const BASE_PUBLIC = 'base-public';
    final public const BASE_FINAL = 'base-final';
}
#[Marker('constants')]
class ConstantChild extends ConstantBase {
    #[Marker('child')]
    private const CHILD_PRIVATE = 'child-private';
    protected const CHILD_PROTECTED = 'child-protected';
    public const CHILD_PUBLIC = 'child-public';
}

$r = new ReflectionClass('ConstantChild');
echo 'all=', json_encode($r->getConstants()), "\n";
echo 'public=', json_encode($r->getConstants(ReflectionClassConstant::IS_PUBLIC)), "\n";
echo 'private=', json_encode($r->getConstants(ReflectionClassConstant::IS_PRIVATE)), "\n";
echo 'constant names=';
foreach ($r->getReflectionConstants() as $constant) {
    echo $constant->getName(), '@', $constant->getDeclaringClass()->getName(), ',';
}
echo "\n";

foreach ($r->getReflectionConstants() as $constant) {
    echo $constant->getName(),
        ' value=', $constant->getValue(),
        ' public=', (int)$constant->isPublic(),
        ' protected=', (int)$constant->isProtected(),
        ' private=', (int)$constant->isPrivate(),
        ' final=', (int)$constant->isFinal(),
        ' modifiers=', $constant->getModifiers(),
        ' declaring=', $constant->getDeclaringClass()->getName(),
        ' attributes=';
    foreach ($constant->getAttributes() as $attribute) {
        echo $attribute->getName(), '@', $attribute->getTarget(), ' ';
    }
    echo "\n";
}

$child = new ReflectionClassConstant('ConstantChild', 'CHILD_PUBLIC');
echo 'constructed=', $child->getName(), '|', $child->getValue(), '|', $child->getDeclaringClass()->getName(), "\n";
$inherited = new ReflectionClassConstant('ConstantChild', 'BASE_PROTECTED');
echo 'inherited=', $inherited->getName(), '|', $inherited->getValue(), '|', $inherited->getDeclaringClass()->getName(), "\n";
$final = new ReflectionClassConstant('ConstantBase', 'BASE_FINAL');
echo 'final=', (int)$final->isFinal(), ' modifiers=', $final->getModifiers(), "\n";

try {
    new ReflectionClassConstant('ConstantChild', 'BASE_PRIVATE');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    new ReflectionClassConstant('MissingClass', 'VALUE');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
