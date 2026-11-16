<?php
$a = [];
for ($i = 0; $i < 64; $i++) { $a['k' . $i] = $i; }
for ($i = 0; $i < 60; $i++) { next($a); }
for ($i = 0; $i < 55; $i++) { unset($a['k' . $i]); }
for ($i = 64; $i < 100; $i++) { $a['k' . $i] = $i; }
var_dump(key($a), current($a));
prev($a); var_dump(key($a), current($a));
unset($a['k59']); var_dump(key($a), current($a));
array_shift($a); var_dump(key($a), current($a));
$a = [10, 20, 30]; end($a); $a['s'] = 40; var_dump(key($a), current($a));
unset($a[1]); $a[1] = 50; var_dump(key($a), current($a));
array_shift($a); var_dump($a, key($a), current($a));
