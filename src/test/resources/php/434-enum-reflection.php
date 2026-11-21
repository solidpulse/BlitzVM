<?php
enum Suit: string {
    case Hearts = 'H';
    case Spades = 'S';
    const Wild = self::Spades;
}

enum Level: int {
    case Low = 1;
}

enum Pure {
    case A;
    case B;
}

function show(string $label, callable $fn): void {
    try {
        $result = $fn();
        if ($result instanceof UnitEnum) {
            echo $label, '=', $result->name, "\n";
        } elseif ($result === null) {
            echo $label, "=null\n";
        } elseif (is_bool($result)) {
            echo $label, '=', $result ? 'true' : 'false', "\n";
        } else {
            echo $label, '=', $result, "\n";
        }
    } catch (Throwable $e) {
        echo $label, '!', get_class($e), ': ', $e->getMessage(), "\n";
    }
}

foreach ([Suit::class, Level::class, Pure::class] as $class) {
    $enum = new ReflectionEnum($class);
    echo $enum->getName(), ' ', (int) $enum->isEnum(), ' ', (int) $enum->isBacked(), ' ';
    $type = $enum->getBackingType();
    echo $type === null ? 'none' : $type->getName(), "\n";
    foreach ($enum->getCases() as $case) {
        echo get_class($case), ' ', $case->getName(), ' ', $case->getValue()->name,
            ' ', $case->getEnum()->getName(), ' ',
            (int) $case->isPublic(), (int) $case->isEnumCase();
        if ($case instanceof ReflectionEnumBackedCase) {
            echo ' ', $case->getBackingValue();
        }
        echo "\n";
    }
}

$enum = new ReflectionEnum(Suit::Hearts);
echo $enum->getName(), ' ', (int) $enum->hasCase('Hearts'), ' ',
    (int) $enum->hasCase('Wild'), ' ', (int) $enum->hasCase('Nope'), "\n";
echo $enum->getCase('Spades')->getBackingValue(), "\n";

show('missing-case', fn () => $enum->getCase('Nope'));
show('not-enum', fn () => new ReflectionEnum(stdClass::class));
show('missing-class', fn () => new ReflectionEnum('NoSuchClass'));
show('no-args', fn () => new ReflectionEnum());
show('cases-args', fn () => $enum->getCases(1));
show('unit-backing', fn () => new ReflectionEnumBackedCase(Pure::class, 'A'));
show('unit-case-missing', fn () => new ReflectionEnumUnitCase(Suit::class, 'Nope'));
show('backing-unit', fn () => (new ReflectionEnumUnitCase(Suit::class, 'Hearts'))->getBackingValue());

$reflection = new ReflectionClass(Suit::class);
echo (int) $reflection->isEnum(), ' ', (int) $reflection->isFinal(), ' ',
    (int) $reflection->isInstantiable(), "\n";
echo implode(',', $reflection->getInterfaceNames()), "\n";
echo implode(',', array_keys($reflection->getConstants())), "\n";
$constants = $reflection->getReflectionConstants();
foreach ($constants as $constant) {
    echo $constant->getName(), ' ', (int) $constant->isEnumCase(), ' ';
}
echo "\n";
echo $reflection->getConstants()['Hearts']->name, ' ',
    $reflection->getReflectionConstant('Wild')->getValue()->name, "\n";
$properties = $reflection->getProperties();
foreach ($properties as $property) {
    echo $property->getName(), ' ', (int) $property->isReadOnly(), ' ';
}
echo "\n";
echo (new ReflectionMethod(Suit::class, 'cases'))->isStatic() ? 'static' : 'instance', ' ',
    (new ReflectionMethod(Suit::class, 'from'))->getName(), ' ',
    (int) $reflection->hasMethod('tryFrom'), "\n";

$low = new ReflectionEnumBackedCase(Level::class, 'Low');
echo $low->getBackingValue(), ' ', $low->getValue()->name, ' ', $low->getEnum()->getName(), "\n";
$pureCase = new ReflectionEnumUnitCase(Pure::class, 'B');
echo $pureCase->getName(), ' ', $pureCase->getValue()->name, "\n";
