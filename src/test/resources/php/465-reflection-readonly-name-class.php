<?php
set_error_handler(function ($n, $s) { echo "warning:$s\n"; return true; });

class ReflectorTarget {
    public $property;
    public const CONSTANT = 1;

    public function method(): void {
    }
}

enum ReflectorEnum: string {
    case A = 'a';
}

$reflectors = [
    'ReflectionClass' => [new ReflectionClass(ReflectorTarget::class), ['name']],
    'ReflectionObject' => [new ReflectionObject(new ReflectorTarget()), ['name']],
    'ReflectionMethod' => [new ReflectionMethod(ReflectorTarget::class, 'method'), ['name', 'class']],
    'ReflectionFunction' => [new ReflectionFunction('strlen'), ['name']],
    'ReflectionProperty' => [new ReflectionProperty(ReflectorTarget::class, 'property'), ['name', 'class']],
    'ReflectionClassConstant' => [new ReflectionClassConstant(ReflectorTarget::class, 'CONSTANT'), ['name', 'class']],
    'ReflectionEnum' => [new ReflectionEnum(ReflectorEnum::class), ['name']],
    'ReflectionEnumUnitCase' => [new ReflectionEnumUnitCase(ReflectorEnum::class, 'A'), ['name', 'class']],
    'ReflectionEnumBackedCase' => [new ReflectionEnumBackedCase(ReflectorEnum::class, 'A'), ['name', 'class']],
];

foreach ($reflectors as $label => [$reflector, $properties]) {
    foreach ($properties as $property) {
        try {
            $reflector->{$property} = 'changed';
            echo $label, ' ', $property, " ok\n";
        } catch (Throwable $e) {
            echo $label, ' ', $property, ' ', get_class($e), ': ', $e->getMessage(), "\n";
        }
    }
}

$class = new ReflectionClass(ReflectorTarget::class);
var_dump($class->name, isset($class->name), property_exists($class, 'name'), get_object_vars($class));

$property = new ReflectionProperty(ReflectionClass::class, 'name');
var_dump($property->isPublic(), $property->isReadOnly());
var_dump($property->getValue($class));
try {
    $property->setValue($class, 'changed');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    $property->setRawValue($class, 'changed');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    $property->setRawValueWithoutLazyInitialization($class, 'changed');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

unset($class->name);
var_dump(isset($class->name));
try {
    $class->name = 'changed';
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    var_dump($class->name);
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

$method = new ReflectionMethod(ReflectorTarget::class, 'method');
var_dump($method->name, $method->class);
unset($method->class);
try {
    $method->class = 'changed';
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
