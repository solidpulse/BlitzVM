<?php
interface RootInterface { public function rootMethod(): void; const ROOT_CONST = 'root'; }
interface ChildInterface extends RootInterface { public function childMethod(): void; const CHILD_CONST = 'child'; }
trait FirstTrait {
    public $firstProperty = 'first';
    public const FIRST_CONST = 'first';
    private $firstPrivate = 'first-private';
    public function firstMethod(): void {}
    private function firstPrivateMethod(): void {}
}
trait SecondTrait {
    public $secondProperty = 'second';
    public const SECOND_CONST = 'second';
    public function secondMethod(): void {}
}
class TraitBase {
    private $basePrivate = 'base';
    protected $baseProtected = 'base';
    public $basePublic = 'base';
    private const BASE_PRIVATE_CONST = 'base-private';
    protected const BASE_PROTECTED_CONST = 'base-protected';
    public function baseMethod(): void {}
    private function basePrivateMethod(): void {}
}
class TraitChild extends TraitBase implements ChildInterface {
    use SecondTrait;
    use FirstTrait { firstMethod as aliasedFirst; }
    public $basePublic = 'override';
    public function baseMethod(): void {}
    public function rootMethod(): void {}
    public function childMethod(): void {}
}

$r = new ReflectionClass('TraitChild');
echo 'methods=';
foreach ($r->getMethods() as $method) {
    echo $method->getName(), '@', $method->getDeclaringClass()->getName(), ',';
}
echo "\n";
echo 'properties=';
foreach ($r->getProperties() as $property) {
    echo $property->getName(), '@', $property->getDeclaringClass()->getName(), ',';
}
echo "\n";
echo 'constants=';
foreach ($r->getReflectionConstants() as $constant) {
    echo $constant->getName(), '@', $constant->getDeclaringClass()->getName(), ',';
}
echo "\n";
echo 'interfaces=', implode(',', $r->getInterfaceNames()), "\n";
echo 'traits=', implode(',', $r->getTraitNames()), "\n";
echo 'defaults=', json_encode($r->getDefaultProperties()), "\n";
echo 'trait method hasMethod=', (int)$r->hasMethod('firstMethod'), ' private trait=', (int)$r->hasMethod('firstPrivateMethod'), "\n";
echo 'private inherited method=', (int)$r->hasMethod('basePrivateMethod'), ' private inherited property=', (int)$r->hasProperty('basePrivate'), "\n";
echo 'overridden property value=', $r->getProperty('basePublic')->getValue(new TraitChild()), ' declaring=', $r->getProperty('basePublic')->getDeclaringClass()->getName(), "\n";
echo 'interface methods=';
foreach ((new ReflectionClass('ChildInterface'))->getMethods() as $method) {
    echo $method->getName(), '@', $method->getDeclaringClass()->getName(), ',';
}
echo "\n";
echo 'interface constants=', json_encode((new ReflectionClass('ChildInterface'))->getConstants()), "\n";
echo 'root filter=', count($r->getMethods(ReflectionMethod::IS_PUBLIC | ReflectionMethod::IS_PRIVATE)), "\n";

abstract class PartialImplementation implements ChildInterface {}
$partial = new ReflectionClass('PartialImplementation');
$fromInterface = $partial->getMethod('rootMethod');
echo 'partial method=', $fromInterface->getName(), ' declaring=', $fromInterface->getDeclaringClass()->getName(),
    ' abstract=', (int)$fromInterface->isAbstract(), "\n";
try {
    $fromInterface->invoke(new PartialImplementation());
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
