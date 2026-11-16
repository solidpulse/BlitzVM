<?php
function large() {
    $a = [];
    for ($i = 0; $i < 100000; $i++) { $a[] = $i; }
    $sum = 0; foreach ($a as $v) { $sum += $v; }
    echo count($a), ' ', $sum, ' ', array_sum($a), ' ', array_is_list($a), "\n";
    $b = array_map(function($v) { return $v * 2; }, $a);
    echo count($b), ' ', array_sum($b), ' ', $b[99999], "\n";
    $c = array_filter($a, function($v) { return $v % 2 === 0; });
    echo count($c), ' ', array_sum($c), ' ', array_key_last($c), "\n";
    $d = array_values($c); echo array_is_list($d), ' ', $d[49999], "\n";
    $hash = []; for ($i = 0; $i < 100000; $i++) { $hash['k' . $i] = $i; }
    for ($i = 0; $i < 70000; $i++) { unset($hash['k' . $i]); }
    for ($i = 100000; $i < 180000; $i++) { $hash['k' . $i] = $i; }
    echo count($hash), ' ', array_sum($hash), ' ', $hash['k179999'], "\n";
}
large();
