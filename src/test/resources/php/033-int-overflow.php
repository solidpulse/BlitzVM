<?php
// An int overflow turns that result into a float, but later int arithmetic at the
// same place must still produce ints.
function add($a, $b) { return $a + $b; }
function sub($a, $b) { return $a - $b; }
function mul($a, $b) { return $a * $b; }

var_dump(add(1, 2));
var_dump(add(PHP_INT_MAX, 1));
var_dump(add(1, 2));
var_dump(add(1.5, 2));

var_dump(sub(10, 4));
var_dump(sub(PHP_INT_MIN, 1));
var_dump(sub(10, 4));

var_dump(mul(3, 4));
var_dump(mul(PHP_INT_MAX, 2));
var_dump(mul(3, 4));

$x = PHP_INT_MAX - 2;
for ($i = 0; $i < 4; $i++) {
    $x += 1;
    var_dump($x);
}
$n = 5;
$n += 2;
$n *= 3;
$n -= 1;
$n /= 4;
var_dump($n);
$n = 7;
$n %= 4;
var_dump($n);
$f = 1.5;
$f++;
$f--;
$f--;
var_dump($f);
