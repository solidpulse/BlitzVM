<?php
function mutations() {
    $a = [10, 20, 30];
    foreach ($a as $k => $v) {
        echo $k, ':', $v, "\n";
        if ($k === 0) { $a[] = 40; unset($a[1]); $a[2] = 99; }
    }
    var_dump($a);
    $a = ['a' => 1, 'b' => 2, 'c' => 3];
    foreach ($a as $k => $v) { echo $k, ':', $v, "\n"; $a = ['new' => 9]; }
    var_dump($a);
    $a = [1, 2, 3];
    foreach ($a as $v) { unset($a); echo $v, "\n"; }
    $a = [1, 2, 3];
    foreach ($a as $v) { if ($v === 2) { continue; } echo $v, "\n"; }
    foreach ($a as $v) { break; }
    $b = $a; $a[0] = 9; var_dump($b);
}
mutations();
