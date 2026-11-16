<?php
$a = ['a', 'b', 'c', 'd'];
unset($a[1]);
$a[1] = 'again';
$a[] = 'tail';
var_dump($a, array_is_list($a), array_values($a), array_is_list(array_values($a)));
$b = [0 => 'zero', 2 => 'two', 1 => 'one', 's' => 'string'];
$b[2] = 'updated';
unset($b['s']);
$b['s'] = 'reinserted';
var_dump($b);
$c = [];
for ($i = 0; $i < 200; $i++) { $c['k' . $i] = $i; }
for ($i = 0; $i < 150; $i++) { unset($c['k' . $i]); }
for ($i = 200; $i < 400; $i++) { $c['k' . $i] = $i; }
$c['k1'] = 1000;
echo count($c), ' ', array_sum($c), "\n";
echo implode(',', array_slice(array_keys($c), -4)), "\n";
