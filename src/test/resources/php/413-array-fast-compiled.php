<?php
// Loops large enough for the bytecode to be compiled, so the array fast paths run in compiled
// code as well as in the interpreter. Every result is an integer.

function packedLoop($n) {
    $a = [];
    for ($i = 0; $i < $n; $i++) {
        $a[] = $i;
    }
    $sum = 0;
    for ($i = 0; $i < $n; $i++) {
        $sum += $a[$i];
    }
    for ($i = 0; $i < $n; $i += 7) {
        $a[$i] = $i * 2;
    }
    return $sum + $a[0] + $a[$n - 1] + count($a);
}

function hashConstantKeys($n) {
    $a = [];
    $a["x"] = 0;
    $a["y"] = 0;
    $a["z"] = 0;
    for ($i = 0; $i < $n; $i++) {
        $a["x"] += 1;
        $a["y"] = $a["x"] + 2;
        if (isset($a["y"])) {
            $a["z"] = $a["y"];
        }
    }
    return $a["x"] + $a["y"] + $a["z"];
}

function hashVariableKeys($n) {
    $keys = ["aa", "bb", "cc", "dd", "ee"];
    $a = [];
    for ($i = 0; $i < $n; $i++) {
        $k = $keys[$i % 5];
        $a[$k] = $i;
    }
    $sum = 0;
    foreach ($keys as $k) {
        $sum += $a[$k];
    }
    return $sum + $a["ee"];
}

function unsetAndAppend($n) {
    $a = [];
    for ($i = 0; $i < 200; $i++) {
        $a[] = $i;
    }
    for ($j = 0; $j < $n; $j++) {
        $k = $j % 200;
        if (isset($a[$k])) {
            unset($a[$k]);
        }
        $a[] = $k;
    }
    $sum = 0;
    foreach ($a as $k => $v) {
        $sum += $v;
    }
    return $sum + count($a);
}

function foreachPacked($n) {
    $a = [];
    for ($i = 0; $i < 500; $i++) {
        $a[$i] = $i * 3;
    }
    $sum = 0;
    for ($j = 0; $j < $n; $j++) {
        foreach ($a as $k => $v) {
            $sum += $v;
        }
    }
    return $sum;
}

echo packedLoop(200000), "\n";
echo hashConstantKeys(200000), "\n";
echo hashVariableKeys(200000), "\n";
echo unsetAndAppend(20000), "\n";
echo foreachPacked(200), "\n";
