<?php
$a = ['x' => 1, 'y' => 2, 'z' => 3];
var_dump(current($a), key($a));
var_dump(next($a), key($a), next($a), key($a), next($a), key($a), current($a));
var_dump(next($a), prev($a), key($a));
var_dump(reset($a), key($a), end($a), key($a), prev($a), key($a));
var_dump(prev($a), prev($a), key($a), current($a));

// loop with next()
$b = [10, 20, 30];
$out = [];
reset($b);
while (($v = current($b)) !== false) {
    $out[] = key($b) . '=' . $v;
    next($b);
}
var_dump(implode(',', $out));

// a copy keeps its own pointer
$c = [1, 2, 3];
next($c);
$d = $c;
next($c);
var_dump(current($c), current($d));
next($d); next($d);
var_dump(current($c), current($d));

// empty arrays
$e = [];
var_dump(reset($e), end($e), current($e), key($e), next($e), prev($e));

// common idioms
$list = ['first' => 'a', 'last' => 'b'];
var_dump(reset($list), end($list), key($list));
function first_key($arr) { reset($arr); return key($arr); }
var_dump(first_key(['k1' => 1, 'k2' => 2]));
$parts = explode('/', 'a/b/c');
var_dump(end($parts));

// array_pop and array_shift reset the pointer
$p = [1, 2, 3, 4];
end($p);
array_pop($p);
var_dump(current($p));
next($p);
array_shift($p);
var_dump(current($p), key($p));

// pointer survives appends
$q = [1];
end($q);
$q[] = 2;
var_dump(current($q), pos($q));
