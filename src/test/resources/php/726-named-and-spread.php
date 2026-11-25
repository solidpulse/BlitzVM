<?php
function combine(int $a = 1, int $b = 2, int $c = 3): string
{
    return $a . '-' . $b . '-' . $c;
}

final class Named
{
    public function method(int $a = 1, int $b = 2, int $c = 3): string
    {
        return $a . '-' . $b . '-' . $c;
    }

    public static function staticMethod(int $a = 1, int $b = 2, int $c = 3): string
    {
        return $a . '-' . $b . '-' . $c;
    }

    public function __construct(public int $a = 1, public int $b = 2, public int $c = 3)
    {
    }

    public function describe(): string
    {
        return $this->a . '-' . $this->b . '-' . $this->c;
    }
}

// Named arguments, in any order and mixed with positional ones.
echo combine(c: 30, a: 10), "\n";
echo combine(10, c: 30), "\n";
echo combine(10, 20, 30), "\n";

$named = new Named(b: 20);
echo $named->method(c: 30), ' ', $named->method(c: 30, a: 10), "\n";
echo Named::staticMethod(c: 30, a: 10), "\n";
echo (new Named(c: 30, a: 10))->describe(), "\n";
echo (new Named(10, c: 30))->describe(), "\n";

// Named arguments to a callable value.
$callable = 'combine';
echo $callable(c: 30, a: 10), "\n";
$closure = fn (int $a = 1, int $b = 2, int $c = 3): string => $a . '-' . $b . '-' . $c;
echo $closure(c: 30, b: 20), "\n";

// Positional spread and string-keyed spread (named arguments).
$positional = [10, 20, 30];
echo combine(...$positional), "\n";
$namedSpread = ['c' => 30, 'a' => 10];
echo combine(...$namedSpread), "\n";
echo combine(10, ...['c' => 30]), "\n";
echo combine(...['a' => 10], ...['c' => 30]), "\n";

echo $named->method(...$positional), "\n";
echo $named->method(...['c' => 30, 'a' => 10]), "\n";
echo Named::staticMethod(...$positional), "\n";
echo (new Named(...$positional))->describe(), "\n";
echo (new Named(...['c' => 30, 'a' => 10]))->describe(), "\n";

// Errors: an unknown name and a duplicate parameter.
try {
    echo combine(nope: 1), "\n";
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    echo combine(1, a: 2), "\n";
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

// Named arguments to a constructor that has no constructor still error.
final class EmptyClass
{
}
try {
    echo (new EmptyClass(a: 1))->describe ?? '', "\n";
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
