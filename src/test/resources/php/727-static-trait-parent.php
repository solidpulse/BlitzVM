<?php
trait Factory
{
    public static function make(): string
    {
        return static::class . ':' . self::class;
    }

    public static function fromTrait(): string
    {
        return 'trait';
    }
}

class First
{
    use Factory;
}
class Second
{
    use Factory;
}

echo First::make(), ' ', Second::make(), "\n";
echo First::fromTrait(), ' ', Second::fromTrait(), "\n";

// parent::, self:: and static:: from an ordinary static method.
class Base
{
    public static function name(): string
    {
        return 'base';
    }

    public static function viaSelf(): string
    {
        return self::name();
    }

    public static function viaStatic(): string
    {
        return static::name();
    }

    public static function viaParent(): string
    {
        return 'base-parent';
    }
}
class Child extends Base
{
    public static function name(): string
    {
        return 'child';
    }

    public static function viaParent(): string
    {
        return parent::viaParent() . '+child';
    }
}

echo Base::viaSelf(), ' ', Base::viaStatic(), ' ', Child::viaSelf(), ' ', Child::viaStatic(), "\n";
echo Child::viaParent(), ' ', Base::viaParent(), "\n";

// __callStatic receives calls that would otherwise be undefined.
final class Magic
{
    public static function __callStatic(string $name, array $arguments): string
    {
        return $name . '(' . implode(',', $arguments) . ')';
    }
}
echo Magic::anything(1, 2, 3), "\n";

// An instance __call receives a method call that does not exist.
final class MagicInstance
{
    public function __call(string $name, array $arguments): string
    {
        return 'instance-' . $name . '(' . implode(':', $arguments) . ')';
    }
}
$magicInstance = new MagicInstance();
echo $magicInstance->missing(4, 5), "\n";

// One spread-call site switches between a real method and __call.
final class RealMethod
{
    public function m(int $a, int $b): string
    {
        return 'real:' . ($a + $b);
    }
}
final class MagicMethod
{
    public function __call(string $name, array $arguments): string
    {
        return 'magic:' . $name . ':' . implode(',', $arguments);
    }
}
function callSpread(object $object, array $arguments): string
{
    return $object->m(...$arguments);
}
echo callSpread(new RealMethod(), [1, 2]), "\n";
echo callSpread(new MagicMethod(), [3, 4]), "\n";
echo callSpread(new RealMethod(), [5, 6]), "\n";

// Abstract and non-static methods cannot be called statically.
abstract class AbstractStatic
{
    abstract public static function missing(): void;
}
try {
    AbstractStatic::missing();
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

final class InstanceOnly
{
    public function method(): string
    {
        return 'instance';
    }
}
try {
    InstanceOnly::method();
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

// Enum methods go through the same static resolution.
enum Suit: string
{
    case Hearts = 'H';
    case Spades = 'S';

    public static function named(string $name): self
    {
        return self::from($name);
    }
}
$suit = Suit::from('H');
echo $suit->name, ' ', $suit->value, ' ', Suit::named('S')->name, ' ', count(Suit::cases()), "\n";

// A class declared after the first call through the site.
function lateStatic(): string
{
    return LateStatic::value();
}
try {
    echo lateStatic(), "\n";
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
if (true) {
    final class LateStatic
    {
        public static function value(): string
        {
            return 'late-static';
        }
    }
}
echo lateStatic(), "\n";

// Static call through an object is allowed for a static method.
$object = new First();
echo $object::make(), "\n";
