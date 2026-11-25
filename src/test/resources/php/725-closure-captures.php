<?php
// Each closure captures the loop variable's value at creation.
$closures = [];
foreach ([1, 2, 3] as $value) {
    $closures[] = function () use ($value): int {
        return $value;
    };
}
foreach ($closures as $closure) {
    echo $closure(), ' ';
}
echo "\n";

// A by-reference capture sees later writes to the captured variable.
$total = 0;
$add = function (int $value) use (&$total): void {
    $total += $value;
};
$add(2);
$add(3);
echo $total, "\n";

// A closure that captures $this and one that captures the class scope.
final class Widget
{
    private int $size = 4;

    public function reader(): Closure
    {
        return function (): int {
            return $this->size;
        };
    }

    public function staticReader(): Closure
    {
        return static function (): string {
            return self::class;
        };
    }
}
$widget = new Widget();
echo $widget->reader()(), ' ', $widget->staticReader()(), "\n";

// Arrow functions copy the values they use, even inside a loop.
$arrows = [];
foreach ([10, 20] as $number) {
    $arrows[] = fn (): int => $number * 2;
}
echo $arrows[0](), ' ', $arrows[1](), "\n";

// Nested closures and immediately invoked closures.
$outer = function (int $base): Closure {
    return function (int $value) use ($base): Closure {
        return fn (int $extra): int => $base + $value + $extra;
    };
};
echo $outer(100)(10)(1), "\n";

// Captured objects are shared, captured arrays are copied.
$shared = new stdClass();
$shared->value = 1;
$array = [1];
$shareClosure = function () use ($shared, $array): void {
    $shared->value = 2;
    $array[] = 2;
};
$shareClosure();
echo $shared->value, ' ', count($array), "\n";

// Closure::fromCallable() creates a closure from another callable.
$double = Closure::fromCallable('double_value');
echo $double(21), "\n";

function double_value(int $value): int
{
    return $value * 2;
}

// A closure created in a function body captures that call's values.
function multiplier(int $factor): Closure
{
    return fn (int $value): int => $value * $factor;
}
echo multiplier(3)(7), ' ', multiplier(5)(7), "\n";
