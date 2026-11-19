<?php
function add($a, $b) { return $a + $b; }
function sub($a, $b) { return $a - $b; }
function mul($a, $b) { return $a * $b; }
$M = PHP_INT_MAX; $m = PHP_INT_MIN;
// Overflow first so each site moves to its checked specialization, then probe the edges.
var_dump(add($M, 1), sub($m, 1), mul($M, 2));
$pairs = [[$M, 0], [$M, -1], [$m, 0], [$m, 1], [$M, $m], [$m, $m], [$M, $M], [-1, $m], [1, -1], [0, 0],
          [3037000499, 3037000499], [3037000500, 3037000500], [-3037000500, 3037000500], [$m, -1], [-1, $m], [$m, 2], [4611686018427387904, 2], [-4611686018427387904, 2]];
for ($round = 0; $round < 300; $round++) {
    foreach ($pairs as [$a, $b]) { $x = add($a, $b); $y = sub($a, $b); $z = mul($a, $b); }
}
foreach ($pairs as [$a, $b]) { var_dump(add($a, $b), sub($a, $b), mul($a, $b)); }
