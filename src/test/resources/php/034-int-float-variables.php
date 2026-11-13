<?php
// Variables that hold ints, floats, or both must keep PHP's exact types, however
// the runtime stores them.
function mix($a, $b, $t) {
    return $a + ($b - $a) * $t / 8;
}

function kinds($n) {
    $out = [];
    for ($i = 0; $i < $n; $i++) {
        $v = mix($i, 10, $i % 9);   // int when the division is exact, float otherwise
        $out[] = gettype($v) . ':' . $v;
    }
    return implode(' ', $out);
}

echo kinds(12), "\n";

function alternate($n) {
    $x = 0;
    $types = '';
    for ($i = 0; $i < $n; $i++) {
        if ($i % 3 == 0) {
            $x = $i;          // int
        } else {
            $x = $i / 2;      // int or float
        }
        $types .= is_int($x) ? 'i' : (is_float($x) ? 'f' : '?');
        $x++;
        $types .= is_int($x) ? 'i' : 'f';
    }
    return $types;
}

echo alternate(12), "\n";

function acc($n) {
    $sum = 0;
    for ($i = 1; $i <= $n; $i++) {
        $sum += $i % 2 == 0 ? $i : $i / 4;
    }
    return $sum;
}

var_dump(acc(4));
var_dump(acc(8));

function param($v) {
    return $v * 2;
}

var_dump(param(3));
var_dump(param(1.5));
var_dump(param(3));

$f = 0.0;
var_dump($f);
$f += 1;
var_dump($f);
$k = 10;
$k /= 5;
var_dump($k);
$k /= 4;
var_dump($k);
$z = 7;
$z--;
var_dump($z);
$y = 2.5;
$y--;
var_dump($y);
var_dump(-7 % 3, 7 % -1, PHP_INT_MIN % -1);
