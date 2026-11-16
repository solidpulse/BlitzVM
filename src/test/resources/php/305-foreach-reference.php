<?php
$a = [1, 2, 3]; $b = $a;
foreach ($a as $k => &$v) { $v *= 10; if ($k === 0) { unset($a[1]); } }
unset($v); print_r($a); print_r($b);
$a = ['x' => [1], 'y' => [2]];
foreach ($a as &$v) { $v[] = 9; }
unset($v); print_r($a);
$x = 1; $a = [&$x, 2]; $b = $a; $b[0] = 5; $b[1] = 6; var_dump($x, $a, $b);
$a = [1, 2, 3]; foreach ($a as &$v) { $v += 1; }
$v = 10; unset($v); print_r($a);
