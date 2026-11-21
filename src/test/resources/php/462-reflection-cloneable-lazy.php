<?php
set_error_handler(function ($n, $s) { echo "warning:$s\n"; return true; });

abstract class AbstractThing {}
interface ThingInterface {}
trait ThingTrait {}
enum UnitThing { case A; }
enum BackedThing: string { case A = 'a'; }
class PlainThing {}
class PrivateClone { private function __clone() {} }
class ProtectedClone { protected function __clone() {} }
class PublicClone { public function __clone() {} }
readonly class ReadonlyThing { public int $x; }
final class FinalThing {}

$anonymous = new class {};

foreach ([
    'AbstractThing', 'ThingInterface', 'ThingTrait', 'UnitThing', 'BackedThing',
    'PlainThing', 'PrivateClone', 'ProtectedClone', 'PublicClone', 'ReadonlyThing', 'FinalThing',
] as $name) {
    $class = new ReflectionClass($name);
    echo $name, ' cloneable=', (int) $class->isCloneable(), "\n";
}
echo 'anonymous cloneable=', (int) (new ReflectionClass($anonymous))->isCloneable(), "\n";

foreach (['stdClass', 'Closure', 'DateTime', 'ArrayObject', 'ReflectionClass', 'ReflectionFunction', 'Exception', 'Generator'] as $name) {
    $class = new ReflectionClass($name);
    echo 'internal ', $name, ' cloneable=', (int) $class->isCloneable(), "\n";
}

class CloneParent {}
class CloneChildWithPrivateClone extends CloneParent { private function __clone() {} }
class CloneChildWithPublicClone extends CloneParent { public function __clone() {} }
class ExceptionChild extends Exception {}

foreach (['CloneChildWithPrivateClone', 'CloneChildWithPublicClone', 'ExceptionChild'] as $name) {
    $class = new ReflectionClass($name);
    echo $name, ' cloneable=', (int) $class->isCloneable(), "\n";
}

$plain = new ReflectionClass(PlainThing::class);
var_dump($plain->isUninitializedLazyObject(new PlainThing()));
var_dump($plain->isUninitializedLazyObject(new PlainThing()));

class PlainThingChild extends PlainThing {}
var_dump($plain->isUninitializedLazyObject(new PlainThingChild()));

$interface = new ReflectionClass(ThingInterface::class);
$implementation = new class implements ThingInterface {};
var_dump($interface->isUninitializedLazyObject($implementation));

foreach ([null, 1, 1.5, 'text', [], new stdClass()] as $value) {
    try {
        $plain->isUninitializedLazyObject($value);
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}

try {
    $plain->isUninitializedLazyObject();
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    $plain->isUninitializedLazyObject(new PlainThing(), 1);
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

$objectReflection = new ReflectionObject(new PlainThing());
var_dump($objectReflection->isUninitializedLazyObject(new PlainThing()));
try {
    $objectReflection->isUninitializedLazyObject(new stdClass());
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

try {
    $interface->isUninitializedLazyObject(new PlainThing());
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
