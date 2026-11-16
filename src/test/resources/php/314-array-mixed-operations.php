<?php
function mixed_operations() {
    $a = []; $state = 14; $sum = 0;
    for ($i = 0; $i < 2000; $i++) {
        $state = ($state * 48271) % 2147483647;
        $key = $state % 41 - 20;
        if ($i % 3 === 0) { $key = 's' . $key; }
        $op = $state % 7;
        if ($op === 0) { unset($a[$key]); }
        elseif ($op === 1) { $a[] = $i; }
        elseif ($op === 2) { $sum += array_pop($a); }
        elseif ($op === 3) { $sum += array_shift($a); }
        else { $a[$key] = $i; }
        if ($i % 100 === 0) { echo $i, ' ', count($a), ' ', $sum, ' ', json_encode($a), "\n"; }
    }
    echo count($a), ' ', $sum, ' ', json_encode($a), "\n";
}
mixed_operations();
