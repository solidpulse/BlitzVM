<?php
function fib($n) {
    return $n < 2 ? $n : fib($n - 1) + fib($n - 2);
}
$n = (int)($_GET['n'] ?? 25);
$t = microtime(true);
$r = fib($n);
printf("fib(%d) = %d in %.1f ms\n", $n, $r, (microtime(true) - $t) * 1000);
