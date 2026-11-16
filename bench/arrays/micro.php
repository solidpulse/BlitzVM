<?php
// All measured work runs in functions. Print all 15 rounds; compare the last round.
function list_sum() {
    $a = [];
    for ($i = 0; $i < 1000; $i++) { $a[] = $i; }
    $sum = 0;
    for ($r = 0; $r < 1000; $r++) {
        foreach ($a as $v) { $sum += $v; }
    }
    return $sum;
}
function hash_sum() {
    $a = [];
    for ($i = 0; $i < 1000; $i++) { $a['k' . $i] = $i; }
    $sum = 0;
    for ($r = 0; $r < 1000; $r++) {
        foreach ($a as $v) { $sum += $v; }
    }
    return $sum;
}
function append_million() {
    $a = [];
    for ($i = 0; $i < 1000000; $i++) { $a[] = $i; }
    return count($a);
}
function copy_write() {
    $a = [];
    for ($i = 0; $i < 10000; $i++) { $a[] = $i; }
    $sum = 0;
    for ($r = 0; $r < 1000; $r++) {
        $b = $a;
        $b[0] = $r;
        $sum += $b[0];
    }
    return $sum;
}
function run_micro() {
    for ($round = 1; $round <= 15; $round++) {
        $t = microtime(true); $sum = list_sum();
        echo $round, ' list_sum ', (microtime(true) - $t) * 1000, ' ', $sum, "\n";
        $t = microtime(true); $sum = hash_sum();
        echo $round, ' hash_sum ', (microtime(true) - $t) * 1000, ' ', $sum, "\n";
        $t = microtime(true); $sum = append_million();
        echo $round, ' append_million ', (microtime(true) - $t) * 1000, ' ', $sum, "\n";
        $t = microtime(true); $sum = copy_write();
        echo $round, ' copy_write ', (microtime(true) - $t) * 1000, ' ', $sum, "\n";
    }
}
run_micro();
