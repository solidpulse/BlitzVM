<?php
$a = [10 => 'a', 20 => 'b'];
$b = ['c', 'd'];
$lit = [0 => 'start', ...$a, ...$b, 'end'];
echo "indexed=" . json_encode($lit) . "
";

$m1 = ['x' => 1, 'y' => 2, 0 => 'zero'];
$m2 = ['y' => 20, 'z' => 30, 0 => 'second_zero'];
$assoc = ['x' => 0, ...$m1, ...$m2, 'z' => 300];
echo "assoc=" . json_encode($assoc) . "
";

$empty = [];
$comb = [...$empty, ...['k' => 'v'], ...$empty];
echo "empty=" . json_encode($comb) . "
";

try {
    $bad = 42;
    $res = [...$bad];
} catch (Error $e) {
    echo "err=" . $e->getMessage() . "
";
}
