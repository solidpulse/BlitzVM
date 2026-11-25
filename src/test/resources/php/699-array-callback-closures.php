<?php
// Closure variants the fast path accepts, and the callables it leaves to the builtin.

$nested = function ($v) {
    return array_map(fn ($x) => $x + 1, [$v, $v + 1]);
};
var_dump(array_map($nested, [1, 2]));

$staticFn = static fn ($x) => $x * 3;
var_dump(array_map($staticFn, [1, 2]));
var_dump(array_filter([1, 2, 3], static fn ($x) => $x !== 2));
var_dump(array_reduce([1, 2], static fn ($a, $b) => $a + $b, 10));

$bound = Closure::bind(function ($x) { return $this->base + $x; },
    new class { public int $base = 10; }, null);
var_dump(array_map($bound, [1, 2]));
$boundCmp = Closure::bind(function ($a, $b) { return $this->base + $a <=> $b; },
    new class { public int $base = 0; }, null);
$sorted = [3, 1, 2];
usort($sorted, $boundCmp);
var_dump($sorted);

$generator = function ($v) { yield $v; yield $v + 1; };
$generated = array_map($generator, [1, 2]);
foreach ($generated as $item) {
    echo 'gen ', implode(',', iterator_to_array($item)), "\n";
}

var_dump(array_map(strlen(...), ['abc', 'de']));
var_dump(array_filter(['abc', 'de', 'f'], strlen(...)));
var_dump(array_reduce([1, 2, 3], 'max', 0));

final class M
{
    public function twice(int $x): int { return $x * 2; }
    public static function twiceStatic(int $x): int { return $x * 2; }
}
var_dump(array_map((new M())->twice(...), [3, 4]));
var_dump(array_map(M::twiceStatic(...), [3, 4]));
$sorted = [3, 1, 2];
var_dump(usort($sorted, strcmp(...)));
var_dump($sorted);

// Several arrays and non-closure callables keep the builtin's own paths.
var_dump(array_map(null, [1, 2], [3, 4]));
var_dump(array_map(fn ($a, $b) => $a . $b, [1, 2, 3], [4, 5]));
var_dump(array_map('strtoupper', ['a', 'b']));

final class Doubler
{
    public function __invoke($value) { return $value * 2; }
}
var_dump(array_map(new Doubler(), [1, 2]));
var_dump(array_filter([1, 2, 3, 4], new Doubler()));

// The callback may replace the array through its captured cell; the walk stays on the
// by-value snapshot php takes before the first callback.
$source = ['a' => 1, 'b' => 2, 'c' => 3];
$order = [];
$mutate = function ($v, $k) use (&$source, &$order) {
    $order[] = $k . $v;
    if ($k === 'a') {
        $source['d'] = 4;
        unset($source['b']);
        $source['a'] = 50;
    }
    return $v % 2 === 1;
};
var_dump(array_filter($source, $mutate, ARRAY_FILTER_USE_BOTH));
echo 'order=', json_encode($order), ' source=', json_encode($source), "\n";

$source = [1, 2, 3];
$steps = [];
$total = array_reduce($source, function ($carry, $v) use (&$source, &$steps) {
    $steps[] = $v;
    if ($v === 2) {
        $source[] = 5;
        $source[3] = 40;
    }
    return $carry + $v;
}, 0);
echo 'reduce=', $total, ' steps=', json_encode($steps), ' source=', json_encode($source), "\n";
