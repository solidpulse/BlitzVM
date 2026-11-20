<?php
interface I1 {}
interface I2 extends I1 {}
trait T1 {}
enum Suit: string { case Hearts = 'H'; }
abstract class AbstractBase { public function __construct($value = 1) {} }
class ParentClass implements I1 {}
class ChildClass extends ParentClass implements I2 {
    use T1;
}

function flags(string $name): string {
    $r = new ReflectionClass($name);
    return $name
        . ' iface=' . (int)$r->isInterface()
        . ' trait=' . (int)$r->isTrait()
        . ' enum=' . (int)$r->isEnum()
        . ' abstract=' . (int)$r->isAbstract()
        . ' final=' . (int)$r->isFinal()
        . ' instantiable=' . (int)$r->isInstantiable()
        . ' internal=' . (int)$r->isInternal()
        . ' user=' . (int)$r->isUserDefined()
        . ' anonymous=' . (int)$r->isAnonymous();
}

foreach (['I1', 'T1', 'Suit', 'AbstractBase', 'ChildClass', 'stdClass', 'Closure'] as $name) {
    echo flags($name), "\n";
}

$r = new ReflectionClass('ChildClass');
echo 'name=', $r->getName(), ' short=', $r->getShortName(), ' namespace=', $r->getNamespaceName(), ' inNamespace=', (int)$r->inNamespace(), "\n";
echo 'parent=', $r->getParentClass()->getName(), "\n";
echo 'interfaces=', implode(',', $r->getInterfaceNames()), "\n";
echo 'interface keys=', implode(',', array_keys($r->getInterfaces())), "\n";
echo 'traits=', implode(',', $r->getTraitNames()), "\n";
echo 'trait keys=', implode(',', array_keys($r->getTraits())), "\n";
echo 'subclass of parent=', (int)$r->isSubclassOf('ParentClass'), ' of self=', (int)$r->isSubclassOf('ChildClass'), ' of I1=', (int)$r->isSubclassOf('I1'), "\n";
echo 'implements I1=', (int)$r->implementsInterface('I1'), ' I2=', (int)$r->implementsInterface('I2'), "\n";
echo 'parent of parent=', var_export($r->getParentClass()->getParentClass(), true), "\n";
echo 'file=', basename($r->getFileName()), ' start=', $r->getStartLine(), ' end=', $r->getEndLine(), ' doc=', var_export($r->getDocComment(), true), "\n";
echo 'i2 interfaces=', implode(',', (new ReflectionClass('I2'))->getInterfaceNames()), "\n";
echo 'parent interfaces=', implode(',', (new ReflectionClass('ParentClass'))->getInterfaceNames()), "\n";

$anonymous = new class {};
$ra = new ReflectionObject($anonymous);
echo 'anonymous=', (int)$ra->isAnonymous(), ' short prefix=', strpos($ra->getShortName(), 'class@anonymous') === 0 ? 'yes' : 'no', "\n";

try {
    $r->implementsInterface('ParentClass');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    $r->isSubclassOf('MissingClass');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
