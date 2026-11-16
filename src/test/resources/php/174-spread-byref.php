<?php
function bumpPair(&$a, &$b, $delta = 1) {
    $a += $delta;
    $b += $delta * 2;
}

$vals = [10, 20, 5];
bumpPair(...$vals);
echo "spread_ref=" . implode(',', $vals) . "
";

$x = 100;
$y = 200;
$refs = [&$x, &$y, 10];
bumpPair(...$refs);
echo "explicit_refs=$x,$y
";

function collectRefs(&...$items) {
    foreach ($items as $i => &$item) {
        $item += ($i + 1) * 10;
    }
    unset($item);
}
$p = 1;
$q = 2;
$r = 3;
collectRefs($p, $q, $r);
echo "var_refs=$p,$q,$r
";

$arr2 = [5, 6];
collectRefs(...$arr2);
echo "var_spread=" . implode(',', $arr2) . "
";
