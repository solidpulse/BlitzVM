<?php
$a = [[1, 2], 'named' => ['x' => [3, 4]]]; $b = $a;
$b[0][0] = 9; $b['named']['x'][] = 5; var_dump($a, $b);
$a = [[1], [2]];
foreach ($a as $k => $v) { $v[] = 7; $a[$k][] = 8; var_dump($v); }
var_dump($a);
$x = [1]; $a = [&$x, [2]]; $b = $a;
$b[0][] = 3; $b[1][] = 4; var_dump($x, $a, $b);
