<?php
// ReflectionProperty::getValue()/setValue(): visibility, statics, readonly, coercion, hooks.

class Access
{
    public int $number = 1;
    protected string $note = 'note';
    private array $hidden = ['a' => 1];
    public static int $counter = 5;
    protected static string $shared = 's';
    public readonly string $sealed;

    public function __construct()
    {
        $this->sealed = 'sealed';
    }
}

$object = new Access();
$class = new ReflectionClass(Access::class);

function show(ReflectionProperty $property, object $object): void
{
    try {
        var_dump($property->getValue($object));
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
    try {
        $property->setValue($object, $property->getName() === 'number' ? '42' : 'changed');
        var_dump($property->getValue($object));
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}

show($class->getProperty('number'), $object);
show($class->getProperty('note'), $object);
show($class->getProperty('hidden'), $object);
show($class->getProperty('sealed'), $object);

$counter = $class->getProperty('counter');
var_dump($counter->isStatic(), $counter->getValue());
$counter->setValue(11);
var_dump(Access::$counter, $counter->getValue());
$counter->setValue($object, 12);
var_dump(Access::$counter);
$shared = $class->getProperty('shared');
$shared->setValue('shared!');
var_dump(Access::$shared ?? 'no');
try {
    var_dump($shared->getValue());
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

class Hooked
{
    public int $raw = 0;
    public int $value = 0 {
        get {
            return $this->value * 10;
        }
        set {
            $this->value = $value + 1;
        }
    }

    public function setValue(int $value): void
    {
        $this->value = $value;
    }
}

$hooked = new Hooked();
$hooked->setValue(4);
$property = new ReflectionProperty(Hooked::class, 'value');
var_dump($property->getValue($hooked));
$property->setValue($hooked, 7);
var_dump($hooked->value, $property->getValue($hooked));
$property->setRawValue($hooked, 2);
var_dump($hooked->value, $property->getValue($hooked));

class ReadonlyBare
{
    public readonly int $value;
}

$bare = (new ReflectionClass(ReadonlyBare::class))->newInstanceWithoutConstructor();
$readonly = new ReflectionProperty(ReadonlyBare::class, 'value');
var_dump($readonly->isInitialized($bare));
$readonly->setValue($bare, 3);
var_dump($readonly->getValue($bare));
try {
    $readonly->setValue($bare, 4);
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

class Typed
{
    public int $count = 0;
    public ?string $label = null;
    public string $name = '';
}

$typed = new Typed();
$countProperty = new ReflectionProperty(Typed::class, 'count');
foreach ([7, '8', 9.6, true] as $value) {
    try {
        $countProperty->setValue($typed, $value);
        var_dump($typed->count);
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
$labelProperty = new ReflectionProperty(Typed::class, 'label');
$labelProperty->setValue($typed, 123);
var_dump($typed->label);
try {
    $countProperty->setValue($typed, null);
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

class PrivateParent
{
    private string $secret = 'parent';
    protected string $family = 'parent';
}

class PrivateChild extends PrivateParent
{
    private string $secret = 'child';
}

$child = new PrivateChild();
$childClass = new ReflectionClass(PrivateChild::class);
$childSecret = $childClass->getProperty('secret');
var_dump($childSecret->getValue($child), $childSecret->getDeclaringClass()->getName());
$childSecret->setValue($child, 'changed');
var_dump($childSecret->getValue($child));
$family = $childClass->getProperty('family');
var_dump($family->getValue($child));
$family->setValue($child, 'changed');
var_dump($family->getValue($child));

$dynamicObject = new stdClass();
$dynamicObject->item = 'start';
$dynamic = new ReflectionProperty($dynamicObject, 'item');
var_dump($dynamic->isDefault(), $dynamic->getValue($dynamicObject));
$dynamic->setValue($dynamicObject, 'end');
var_dump($dynamicObject->item);
