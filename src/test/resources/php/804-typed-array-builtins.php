<?php
// Builders and mutating array builtins over typed packed storage.

function dump(string $label, $value): void {
    echo $label, ":\n";
    var_dump($value);
}

dump('range ints', range(1, 5));
dump('range floats', range(1.0, 3.0));
dump('range strings', range('a', 'e'));
dump('fill ints', array_fill(0, 4, 7));
dump('fill floats', array_fill(0, 3, 1.5));
dump('fill offset', array_fill(2, 3, 0));
dump('slice', array_slice(range(1, 6), 1, 3));
dump('reverse', array_reverse(range(1, 4)));
dump('values', array_values([10, 20, 30]));
dump('merge', array_merge([1, 2], [3, 4]));
dump('pad', array_pad([1, 2], 4, 9));

$p = [1, 2, 3];
dump('push', [$p, array_push($p, 4, 5), $p]);
dump('pop', [$p, array_pop($p), $p]);
$q = [1, 2, 3];
dump('shift', [$q, array_shift($q), $q]);
$u = [2, 3];
dump('unshift', [$u, array_unshift($u, 0, 1), $u]);
$s = [1, 2, 3, 4, 5];
dump('splice', [$s, array_splice($s, 1, 2, [8, 9]), $s]);
$spliceTail = [1, 2, 3];
dump('splice tail', [$spliceTail, array_splice($spliceTail, 2), $spliceTail]);
$splicePast = [1, 2];
dump('splice past end', [$splicePast, array_splice($splicePast, 9, 1, [7]), $splicePast]);

$r = [3, 1, 2];
sort($r);
dump('sort', $r);
$rs = [3, 1, 2];
rsort($rs);
dump('rsort', $rs);
$us = [3, 1, 2];
usort($us, fn ($x, $y) => $x <=> $y);
dump('usort', $us);
$mixed = [3, 1.5, 2];
sort($mixed);
dump('sort mixed', $mixed);

$popped = [1, 2];
array_pop($popped);
array_pop($popped);
$popped[] = 'empty typed then string';
dump('pop all then append', $popped);
$popped[] = 5;
dump('append int after string', $popped);

$co = [1, 2, 3];
$copy = $co;
array_push($copy, 4);
dump('push copy original', $co);
dump('push copy changed', $copy);

$walk = [1, 2, 3];
array_walk($walk, function (&$v) {
    $v += 10;
});
echo 'array_walk by reference: ', implode(',', $walk), "\n";
