<?php
$a = [1, 2, 3]; $b = $a; $c = $a;
foreach ($a as $k => $v) {
    foreach ($a as $j => $w) {
        echo $k, '/', $j, ':', $v, '/', $w, "\n";
        if ($k === 0 && $j === 0) { $a[] = 4; unset($a[1]); }
        if ($j === 2) { break; }
    }
}
$b[0] = 8;
var_dump($a, $b, $c);
function early_return($a) { foreach ($a as $v) { return $v; } }
echo early_return($c), "\n";
try { foreach ($c as $v) { throw new Exception('stop'); } } catch (Exception $e) { echo $e->getMessage(), "\n"; }
$d = $c; $c[0] = 7; var_dump($d);
