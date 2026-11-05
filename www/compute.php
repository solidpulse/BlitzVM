<?php
// Compute kernels for the benchmarks. php-truffle and php-fpm run this file per request
// (compute.php?k=mandelbrot&n=400); the Swoole bench server loads it once with
// $KERNELS_ONLY set and calls run_kernel() from its request handler.

function mandelbrot($n) {
    $count = 0;
    for ($y = 0; $y < $n; $y++) {
        $ci = 2.0 * $y / $n - 1.0;
        for ($x = 0; $x < $n; $x++) {
            $cr = 2.0 * $x / $n - 1.5;
            $zr = 0.0;
            $zi = 0.0;
            $i = 0;
            while ($i < 50 && $zr * $zr + $zi * $zi <= 4.0) {
                $t = $zr * $zr - $zi * $zi + $cr;
                $zi = 2.0 * $zr * $zi + $ci;
                $zr = $t;
                $i++;
            }
            if ($i == 50) {
                $count++;
            }
        }
    }
    return $count;
}

function spectral_a($i, $j) {
    return 1.0 / (($i + $j) * ($i + $j + 1) / 2 + $i + 1);
}

function spectral_av($n, $v) {
    $out = [];
    for ($i = 0; $i < $n; $i++) {
        $s = 0.0;
        for ($j = 0; $j < $n; $j++) {
            $s += spectral_a($i, $j) * $v[$j];
        }
        $out[] = $s;
    }
    return $out;
}

function spectral_atv($n, $v) {
    $out = [];
    for ($i = 0; $i < $n; $i++) {
        $s = 0.0;
        for ($j = 0; $j < $n; $j++) {
            $s += spectral_a($j, $i) * $v[$j];
        }
        $out[] = $s;
    }
    return $out;
}

function spectral_norm($n) {
    $u = array_fill(0, $n, 1.0);
    $v = [];
    for ($k = 0; $k < 10; $k++) {
        $v = spectral_atv($n, spectral_av($n, $u));
        $u = spectral_atv($n, spectral_av($n, $v));
    }
    $vbv = 0.0;
    $vv = 0.0;
    for ($i = 0; $i < $n; $i++) {
        $vbv += $u[$i] * $v[$i];
        $vv += $v[$i] * $v[$i];
    }
    return sprintf("%.9f", sqrt($vbv / $vv));
}

function sieve($n) {
    $flags = array_fill(0, $n + 1, true);
    $count = 0;
    for ($i = 2; $i <= $n; $i++) {
        if ($flags[$i]) {
            $count++;
            for ($j = $i * $i; $j <= $n; $j += $i) {
                $flags[$j] = false;
            }
        }
    }
    return $count;
}

function collatz($limit) {
    $best = 0;
    $bestStart = 0;
    for ($start = 1; $start < $limit; $start++) {
        $x = $start;
        $len = 1;
        while ($x != 1) {
            if ($x % 2 == 0) {
                $x = $x / 2;
            } else {
                $x = 3 * $x + 1;
            }
            $len++;
        }
        if ($len > $best) {
            $best = $len;
            $bestStart = $start;
        }
    }
    return $bestStart;
}

function run_kernel($k, $n) {
    $t = microtime(true);
    switch ($k) {
        case 'mandelbrot': $r = mandelbrot($n ?: 400); break;
        case 'spectral': $r = spectral_norm($n ?: 100); break;
        case 'sieve': $r = sieve($n ?: 1000000); break;
        case 'collatz': $r = collatz($n ?: 300000); break;
        default: return "unknown kernel\n";
    }
    return sprintf("%s = %s in %.1f ms\n", $k, $r, (microtime(true) - $t) * 1000);
}

if (!isset($KERNELS_ONLY)) {
    echo run_kernel($_GET['k'] ?? 'mandelbrot', (int)($_GET['n'] ?? 0));
}
