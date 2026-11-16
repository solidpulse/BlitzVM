<?php
$a = ['x' => [1], 'hole' => [2], 'z' => [3]]; unset($a['hole']); $b = $a;
$b['x'][] = 4; $b['z'][0] = 5; $b['tail'] = [6]; var_dump($a, $b);
$x = 1; $a = ['ref' => &$x, 'value' => 2]; $b = $a;
$b['ref'] = 7; $b['value'] = 8; var_dump($x, $a, $b);
foreach ($a as $k => $v) { if ($k === 'ref') { $x = 9; $a['value'] = 10; } echo $k, ':', $v, "\n"; }
var_dump($a, $b);
