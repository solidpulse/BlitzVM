<?php
final class Counter
{
    public static int $count = 0;
    private static int $secret = 5;
    protected static ?string $label = null;
    public static $untyped;
    public static $viaExpr = null;

    public static function bump(): int
    {
        return ++self::$secret;
    }

    public static function secret(): int
    {
        return self::$secret;
    }

    public static function label(): ?string
    {
        return self::$label;
    }

    public static function setLabel(?string $value): void
    {
        self::$label = $value;
    }
}

echo Counter::$count, "\n";
Counter::$count = 10;
Counter::$count += 5;
echo Counter::$count, ' ', ++Counter::$count, ' ', Counter::$count--, ' ', Counter::$count, "\n";

$cell = &Counter::$count;
$cell = 42;
echo Counter::$count, "\n";
Counter::$count = 7;
echo $cell, "\n";

echo Counter::bump(), ' ', Counter::bump(), ' ', Counter::secret(), "\n";
Counter::setLabel('x');
echo Counter::label() ?? 'null', "\n";

$value = 7;
Counter::$untyped = &$value;
echo Counter::$untyped, "\n";
$value = 9;
echo Counter::$untyped, "\n";
Counter::$untyped = 11;
echo $value, ' ', Counter::$untyped, "\n";

final class Typed
{
    public static int $uninitialized;
    public static string $name = 'typed';
}

try {
    echo Typed::$uninitialized, "\n";
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
Typed::$uninitialized = 3;
echo Typed::$uninitialized, ' ', Typed::$name, "\n";

// A class declared after the first call through the static-property site.
function lateProperty(): int
{
    return Late::$number;
}

try {
    echo lateProperty(), "\n";
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

if (true) {
    final class Late
    {
        public static int $number = 9;
    }
}
echo lateProperty(), "\n";

// An inherited static property is one property until the child redeclares it.
class ParentClass
{
    public static string $shared = 'parent';
    public static string $overridden = 'parent';
}
class ChildClass extends ParentClass
{
    public static string $overridden = 'child';
}
echo ParentClass::$shared, ' ', ChildClass::$shared, "\n";
ChildClass::$shared = 'changed';
echo ParentClass::$shared, ' ', ChildClass::$shared, "\n";
echo ParentClass::$overridden, ' ', ChildClass::$overridden, "\n";

trait HasStatic
{
    public static string $fromTrait = 'trait';
    public static function traitValue(): string
    {
        return self::$fromTrait;
    }
}
class UsesTrait
{
    use HasStatic;
}
echo UsesTrait::$fromTrait, ' ', UsesTrait::traitValue(), "\n";
UsesTrait::$fromTrait = 'changed';
echo HasStatic::$fromTrait, ' ', UsesTrait::$fromTrait, "\n";
