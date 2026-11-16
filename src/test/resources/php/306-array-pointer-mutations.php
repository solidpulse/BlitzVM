<?php
function point($a) { var_dump(key($a), current($a)); }
$a = ['a', 'b', 'c', 'd']; next($a); point($a);
unset($a[0]); point($a);
unset($a[1]); point($a);
$a[] = 'e'; point($a);
end($a); point($a); unset($a[4]); point($a);
prev($a); point($a); reset($a); point($a);
array_pop($a); point($a);
$a = [1, 2]; end($a); next($a); $a[] = 3; point($a);
reset($a); prev($a); $a[] = 4; point($a);
$a = []; $a[] = 1; point($a); unset($a[0]); $a[] = 2; point($a);
$a = []; next($a); $a[] = 1; point($a);
$a = ['x' => 1, 'y' => 2, 'z' => 3]; next($a); unset($a['x']); point($a);
unset($a['y']); point($a); $a['y'] = 4; point($a);
$a = [10, 20, 30]; end($a); $b = $a; reset($b); point($a); point($b);
