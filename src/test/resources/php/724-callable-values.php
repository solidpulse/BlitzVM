<?php
function double(int $x): int
{
    return $x * 2;
}

final class Callables
{
    public static function staticMethod(int $x): int
    {
        return $x + 100;
    }

    public function method(int $x): int
    {
        return $x + 10;
    }

    public function __invoke(int $x): int
    {
        return $x + 1;
    }

    public function __call(string $name, array $args): string
    {
        return $name . '(' . implode(',', $args) . ')';
    }
}

$instance = new Callables();

// The kinds a callable value can have.
$closure = fn (int $x): int => $x + 1000;
$userFunction = 'double';
$staticName = 'Callables::staticMethod';
$methodArray = [$instance, 'method'];
$staticArray = ['Callables', 'staticMethod'];
$invokable = new Callables();
$builtin = 'abs';
$magic = [$instance, 'missing'];

foreach ([
    $closure, $userFunction, $staticName, $methodArray, $staticArray, $invokable, $builtin,
] as $callable) {
    echo $callable(-7), ' ';
}
echo $magic(-7), "\n";

// First-class callables are closure objects backed by one function or method.
$firstClassFunction = double(...);
$firstClassStatic = Callables::staticMethod(...);
$firstClassMethod = $instance->method(...);
$firstClassBuiltin = strrev(...);
echo $firstClassFunction(3), ' ', $firstClassStatic(3), ' ', $firstClassMethod(3), ' ',
    $firstClassBuiltin('abc'), "\n";

// A closure rebound with Closure::bind runs with the new $this and scope.
final class Scope
{
    private int $value = 5;

    public function method(): int
    {
        return 1;
    }
}
$reader = function (): int {
    return $this->value;
};
$bound = Closure::bind($reader, new Scope(), Scope::class);
echo $bound(), "\n";

$callMethod = function (): int {
    return $this->method();
};
$scoped = Closure::bind($callMethod, new Scope(), Scope::class);
echo $scoped(), "\n";

// A static closure has no $this to bind; a null binding keeps its scope.
$staticClosure = static function (): string {
    return 'static';
};
echo $staticClosure(), ' ', Closure::bind($staticClosure, null, Scope::class)(), "\n";

// Closures keep their captured values and bindings across calls.
function makeCounter(int $start): Closure
{
    return function (int $step = 1) use ($start): int {
        static $calls = 0;
        return $start + $step * ++$calls;
    };
}
$counter = makeCounter(10);
echo $counter(), ' ', $counter(2), ' ', $counter(3), "\n";

// Calling the result of a call directly.
echo (fn (int $x): int => $x + 1)(41), "\n";
