<?php
// ReflectionClass::newInstanceWithoutConstructor() and newInstanceArgs().

class Defaults
{
    public int $count = 3;
    public string $label = 'x';
    public ?array $items = null;
    public int $uninitialized;
    public readonly string $readonlyValue;
    public static int $stat = 9;

    public function __construct(public string $name = 'n', public int $size = 1)
    {
        $this->readonlyValue = 'set';
    }
}

$reflection = new ReflectionClass(Defaults::class);
$bare = $reflection->newInstanceWithoutConstructor();
var_dump($bare);
var_dump($bare->count, $bare->label, $bare->items);
echo 'static=', $bare::$stat, "\n";

try {
    echo $bare->uninitialized;
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    echo $bare->readonlyValue;
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

$positional = $reflection->newInstanceArgs(['bob', 4]);
var_dump($positional->name, $positional->size, $positional->count, $positional->readonlyValue);

$named = $reflection->newInstanceArgs(['size' => 7, 'name' => 'ann']);
var_dump($named->name, $named->size);

$partial = $reflection->newInstanceArgs(['name' => 'solo']);
var_dump($partial->name, $partial->size);

$coerced = $reflection->newInstanceArgs(['name' => 12, 'size' => '5']);
var_dump($coerced->name, $coerced->size);

class ByReference
{
    public function __construct(&$value, int &$count, int $plain = 0)
    {
        $value .= '!';
        $count++;
        $this->plain = $plain;
    }

    public int $plain;
}

$value = 'start';
$count = 10;
$byReference = (new ReflectionClass(ByReference::class))->newInstanceArgs([&$value, &$count, 5]);
var_dump($value, $count, $byReference->plain);

$namedByReference = (new ReflectionClass(ByReference::class))->newInstanceArgs(
    ['count' => &$count, 'plain' => 6, 'value' => &$value]
);
var_dump($value, $count, $namedByReference->plain);

class NoConstructor
{
    public int $a = 1;
}

try {
    (new ReflectionClass(NoConstructor::class))->newInstanceArgs([1]);
} catch (ReflectionException $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

try {
    (new ReflectionClass(Defaults::class))->newInstanceArgs([]);
} catch (ArgumentCountError $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

try {
    (new ReflectionClass(Defaults::class))->newInstanceArgs(['nope' => 1]);
} catch (ArgumentCountError|Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

try {
    (new ReflectionClass(Defaults::class))->newInstanceWithoutConstructor(['x']);
} catch (ArgumentCountError $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

$std = (new ReflectionClass(stdClass::class))->newInstanceWithoutConstructor();
var_dump($std instanceof stdClass, get_class($std));

$dateTime = (new ReflectionClass(DateTime::class))->newInstanceWithoutConstructor();
var_dump($dateTime instanceof DateTime);

$internalArgs = (new ReflectionClass(ArrayIterator::class))->newInstanceArgs([[1, 2]]);
var_dump($internalArgs->getArrayCopy());

interface NotInstantiable
{
}
trait AlsoNot
{
}
enum NotEither
{
    case A;
}
abstract class Abs
{
}

foreach ([NotInstantiable::class, AlsoNot::class, NotEither::class, Abs::class] as $name) {
    try {
        (new ReflectionClass($name))->newInstanceWithoutConstructor();
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}

try {
    (new ReflectionClass(Defaults::class))->newInstance();
} catch (ArgumentCountError $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
$viaNewInstance = (new ReflectionClass(Defaults::class))->newInstance('arg');
var_dump($viaNewInstance->name, $viaNewInstance->size);
