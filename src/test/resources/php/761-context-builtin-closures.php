<?php
final class Counter {
    private int $next = 1;
    public function bump(): int { return $this->next++; }
}
$c = new Counter();
$mapped = array_map(function (int $x) use ($c): int { return $x + $c->bump(); }, [10, 20, 30]);
echo implode(',', $mapped), "\n";
$items = [[2, 'b'], [1, 'a'], [3, 'c']];
usort($items, function (array $a, array $b) use ($c): int { $c->bump(); return $a[0] <=> $b[0]; });
echo implode('', array_column($items, 1)), ":", $c->bump(), "\n";
$replaced = preg_replace_callback('/\d+/', function (array $m) use ($c): string {
    return $m[0] . '=' . $c->bump();
}, 'a1 b2');
echo $replaced, "\n";
$filtered = array_filter([1, 2, 3, 4], function (int $x) use ($c): bool {
    return ($x + $c->bump()) % 2 === 0;
});
echo implode(',', $filtered), "\n";
