<?php
// array_map/filter/reduce/any/all/find/find_key walk a by-value snapshot: a callback that
// modifies the array it is walking must not change what the walk still visits.

$source = [1, 2, 3];
$seen = [];
$mapped = array_map(function ($v) use (&$source, &$seen) {
    $seen[] = $v;
    if ($v === 1) {
        $source[] = 99;
        $source[0] = 100;
        unset($source[1]);
    }
    return $v * 10;
}, $source);
echo 'map=', json_encode($mapped), ' seen=', json_encode($seen), ' source=', json_encode(array_values($source)), "\n";

$source = ['a' => 1, 'b' => 2, 'c' => 3];
$order = [];
$filtered = array_filter($source, function ($v, $k) use (&$source, &$order) {
    $order[] = $k . $v;
    if ($k === 'a') {
        $source['d'] = 4;
        unset($source['b']);
        $source['a'] = 50;
    }
    return $v % 2 === 1;
}, ARRAY_FILTER_USE_BOTH);
echo 'filter=', json_encode($filtered), ' order=', json_encode($order), ' source=', json_encode($source), "\n";

$source = [1, 2, 3, 4];
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

$source = [1, 2, 3];
$visited = [];
$any = array_any($source, function ($v) use (&$source, &$visited) {
    $visited[] = $v;
    if ($v === 2) {
        $source[] = 4;
        $source[0] = 10;
    }
    return $v === 2;
});
echo 'any=', var_export($any, true), ' visited=', json_encode($visited), ' source=', json_encode($source), "\n";

$source = [1, 2, 3];
$visited = [];
$all = array_all($source, function ($v) use (&$source, &$visited) {
    $visited[] = $v;
    if ($v === 1) {
        unset($source[2]);
        $source[1] = 20;
    }
    return $v < 10;
});
echo 'all=', var_export($all, true), ' visited=', json_encode($visited), ' source=', json_encode($source), "\n";

$source = [5, 6, 7];
$found = array_find($source, function ($v) use (&$source) {
    if ($v === 5) {
        $source[1] = 60;
        $source[] = 8;
    }
    return $v === 6;
});
$source = [5, 6, 7];
$foundKey = array_find_key($source, function ($v, $k) use (&$source) {
    if ($k === 0) {
        unset($source[1]);
        $source[] = 9;
    }
    return $v === 6;
});
echo 'find=', var_export($found, true), ' find_key=', var_export($foundKey, true), "\n";

// References inside an array keep their cells.
$cell = 1;
$refs = [&$cell, 2, &$cell];
$mappedRefs = array_map(function ($v) { return $v; }, $refs);
$cell = 7;
echo 'refs=', json_encode($mappedRefs), ' ', json_encode($refs), "\n";

// String keys keep their order and type in every result.
$keys = ['x' => 'a', 3 => 'b', 'y' => 'c', 0 => 'd'];
echo 'keys-map=', json_encode(array_map('strtoupper', $keys)), "\n";
echo 'keys-filter=', json_encode(array_filter($keys, fn ($v) => $v !== 'b')), "\n";
echo 'keys-any=', var_export(array_any($keys, fn ($v) => $v === 'c'), true), "\n";
echo 'keys-all=', var_export(array_all($keys, fn ($v) => is_string($v)), true), "\n";
echo 'keys-find=', var_export(array_find_key($keys, fn ($v) => $v === 'd'), true), "\n";

// Multi-array map and non-closure callables take the other callback paths.
echo 'multi=', json_encode(array_map(fn ($a, $b) => $a . $b, [1, 2, 3], [4, 5])), "\n";
echo 'multi-null=', json_encode(array_map(null, ['a', 'b'], [1, 2])), "\n";

final class Doubler
{
    public function __invoke($value)
    {
        return $value * 2;
    }
}
echo 'invoke=', json_encode(array_map(new Doubler(), [1, 2])), "\n";
echo 'invoke-filter=', json_encode(array_filter([1, 2, 3, 4], new Doubler())), "\n";
echo 'strfn=', json_encode(array_reduce([1, 2, 3], 'max', 0)), "\n";
echo 'any-key=', var_export(array_any(['a' => 1], fn ($v, $k) => $k === 'a'), true), "\n";
