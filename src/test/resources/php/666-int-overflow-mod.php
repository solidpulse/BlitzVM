<?php
// Integer overflow in *, %, and intdiv's error cases.
set_error_handler(function ($no, $msg) { echo "warning: $msg\n"; return true; });

$max = PHP_INT_MAX;
$min = PHP_INT_MIN;

var_dump($max * 2);
var_dump($max + 1);
var_dump($min - 1);
var_dump($min * -1);
var_dump(2 * $max);

var_dump($max % 2);
var_dump($min % 2);
var_dump(-7 % 3);
var_dump(7 % -3);
var_dump(5 % 2);

try {
    $x = 5 % 0;
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

try {
    intdiv(5, 0);
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

try {
    intdiv(PHP_INT_MIN, -1);
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

var_dump(intdiv(7, 2));
var_dump(intdiv(-7, 2));
var_dump(intdiv(7, -2));
var_dump(intdiv(PHP_INT_MAX, 1));

var_dump(PHP_INT_MAX + 1.0);
var_dump(PHP_INT_MAX * 1.0);
