<?php
set_error_handler(function ($n, $s) { echo "warning:$s\n"; return true; });

class RawProperty {
    public $plain = 'plain';
    public int $typed = 1;
    public int $uninitialized;
    public readonly int $readonlySet;
    public readonly int $readonlyUnset;
    public int $hooked = 1 {
        set {
            $this->hooked = $value * 10;
        }
    }
    public int $virtual {
        get => 99;
    }
    public static $static = 'static';

    public function __construct() {
        $this->readonlySet = 5;
    }
}

interface HookInterface {
    public string $name { get; }
}

abstract class AbstractHook {
    abstract public string $name { get; }
}

$object = new RawProperty();

$plain = new ReflectionProperty(RawProperty::class, 'plain');
$plain->setRawValue($object, 'raw');
var_dump($object->plain);

$typed = new ReflectionProperty(RawProperty::class, 'typed');
$typed->setRawValue($object, '12');
var_dump($object->typed);
try {
    $typed->setRawValue($object, []);
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

$readonlySet = new ReflectionProperty(RawProperty::class, 'readonlySet');
try {
    $readonlySet->setRawValue($object, 8);
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

$readonlyUnset = new ReflectionProperty(RawProperty::class, 'readonlyUnset');
var_dump($readonlyUnset->isInitialized($object));
$readonlyUnset->setRawValue($object, 7);
var_dump($object->readonlyUnset);
try {
    $readonlyUnset->setRawValue($object, 9);
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

$hooked = new ReflectionProperty(RawProperty::class, 'hooked');
$hooked->setRawValue($object, 3);
var_dump($object->hooked);
$object->hooked = 4;
var_dump($object->hooked);

$virtual = new ReflectionProperty(RawProperty::class, 'virtual');
try {
    $virtual->setRawValue($object, 1);
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
var_dump($object->virtual);

$static = new ReflectionProperty(RawProperty::class, 'static');
try {
    $static->setRawValue($object, 'changed');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
var_dump(RawProperty::$static);

$second = new RawProperty();
$withoutLazy = new ReflectionProperty(RawProperty::class, 'plain');
$withoutLazy->setRawValueWithoutLazyInitialization($second, 'without');
var_dump($second->plain);

try {
    $withoutLazy->setRawValueWithoutLazyInitialization(new stdClass(), 'no');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

try {
    $static->setRawValueWithoutLazyInitialization($object, 'no');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

$dynamicObject = new stdClass();
$dynamicObject->dynamic = 'initial';
$dynamic = new ReflectionProperty($dynamicObject, 'dynamic');
$dynamic->setRawValue($dynamicObject, 'raw dynamic');
var_dump($dynamicObject->dynamic);
try {
    $dynamic->setRawValueWithoutLazyInitialization($dynamicObject, 'no');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

$other = new stdClass();
$plain->setRawValue($other, 'other value');
var_dump($other->plain);

try {
    $plain->setRawValueWithoutLazyInitialization($other, 'no');
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

foreach ([
    'hooked' => $hooked,
    'virtual' => $virtual,
    'typed' => $typed,
    'dynamic' => $dynamic,
] as $name => $property) {
    echo $name, ' abstract=', (int) $property->isAbstract(), "\n";
}
echo 'interface abstract=', (int) (new ReflectionProperty(HookInterface::class, 'name'))->isAbstract(), "\n";
echo 'abstract class abstract=', (int) (new ReflectionProperty(AbstractHook::class, 'name'))->isAbstract(), "\n";

foreach (['setRawValue', 'setRawValueWithoutLazyInitialization'] as $method) {
    try {
        $typed->{$method}($object);
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
    try {
        $typed->{$method}();
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
