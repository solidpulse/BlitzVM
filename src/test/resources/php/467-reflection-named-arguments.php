<?php
set_error_handler(function ($n, $s) { echo "warning:$s\n"; return true; });

class NamedTarget {
    public $property;
    public const CONSTANT = 1;

    public function method(int $first = 1, int $second = 2): int {
        return $first + $second;
    }
}

enum NamedEnum: string {
    case A = 'a';
}

$class = new ReflectionClass(objectOrClass: NamedTarget::class);
echo 'class=', $class->getName(), "\n";

$object = new ReflectionObject(object: new NamedTarget());
echo 'object=', $object->getName(), "\n";

$method = new ReflectionMethod(objectOrMethod: NamedTarget::class, method: 'method');
echo 'method=', $method->getName(), ' declaring=', $method->getDeclaringClass()->getName(), "\n";

$methodTwo = new ReflectionMethod(NamedTarget::class, method: 'method');
echo 'method two=', $methodTwo->getName(), "\n";

$methodReversed = new ReflectionMethod(method: 'method', objectOrMethod: NamedTarget::class);
echo 'method reversed=', $methodReversed->getName(), "\n";

$function = new ReflectionFunction(function: 'strlen');
echo 'function=', $function->getName(), "\n";

$property = new ReflectionProperty(class: NamedTarget::class, property: 'property');
echo 'property=', $property->getName(), ' declaring=', $property->getDeclaringClass()->getName(), "\n";

$propertyReversed = new ReflectionProperty(property: 'property', class: NamedTarget::class);
echo 'property reversed=', $propertyReversed->getName(), "\n";

$constant = new ReflectionClassConstant(class: NamedTarget::class, constant: 'CONSTANT');
echo 'constant=', $constant->getName(), ' value=', $constant->getValue(), "\n";

$enum = new ReflectionEnum(objectOrClass: NamedEnum::class);
echo 'enum=', $enum->getName(), ' backed=', (int) $enum->isBacked(), "\n";

$unitCase = new ReflectionEnumUnitCase(class: NamedEnum::class, constant: 'A');
echo 'unit case=', $unitCase->getName(), ' enum=', $unitCase->getEnum()->getName(), "\n";

$backedCase = new ReflectionEnumBackedCase(class: NamedEnum::class, constant: 'A');
echo 'backed case=', $backedCase->getName(), ' value=', $backedCase->getBackingValue(), "\n";

$closure = new ReflectionFunction(function: fn (int $value): int => $value + 1);
echo 'closure anonymous=', (int) $closure->isAnonymous(), "\n";

try {
    new ReflectionClass(unknown: NamedTarget::class);
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

try {
    new ReflectionMethod(NamedTarget::class, objectOrMethod: NamedTarget::class);
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

try {
    new ReflectionProperty(class: NamedTarget::class, property: 'missing');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
