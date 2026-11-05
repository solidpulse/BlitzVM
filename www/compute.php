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

function tak($x, $y, $z) {
    if ($y < $x) {
        return tak(tak($x - 1, $y, $z), tak($y - 1, $z, $x), tak($z - 1, $x, $y));
    }
    return $z;
}

function count_primes($limit) {
    $count = $limit >= 2 ? 1 : 0;
    for ($i = 3; $i < $limit; $i += 2) {
        $prime = true;
        for ($d = 3; $d * $d <= $i; $d += 2) {
            if ($i % $d == 0) {
                $prime = false;
                break;
            }
        }
        if ($prime) {
            $count++;
        }
    }
    return $count;
}

function gcd($a, $b) {
    while ($b != 0) {
        $t = $a % $b;
        $a = $b;
        $b = $t;
    }
    return $a;
}

function gcd_sum($n) {
    $sum = 0;
    for ($i = 1; $i <= $n; $i++) {
        for ($j = 1; $j <= $n; $j++) {
            $sum += gcd($i, $j);
        }
    }
    return $sum;
}

function clamp($v, $lo, $hi) {
    return $v < $lo ? $lo : ($v > $hi ? $hi : $v);
}

function mix($a, $b, $t) {
    return $a + ($b - $a) * $t / 8;
}

function shade($x, $y) {
    $r = clamp(mix($x % 256, $y % 256, ($x + $y) % 9), 16, 240);
    $g = clamp(mix($y % 256, 255 - $x % 256, $x % 9), 16, 240);
    return $r * 3 + $g;
}

function render_pixels($n) {
    $acc = 0;
    for ($y = 0; $y < $n; $y++) {
        for ($x = 0; $x < $n; $x++) {
            $acc += shade($x, $y);
        }
    }
    return (int)$acc;
}

function run_kernel($k, $n) {
    $t = microtime(true);
    switch ($k) {
        case 'mandelbrot': $r = mandelbrot($n ?: 400); break;
        case 'spectral': $r = spectral_norm($n ?: 100); break;
        case 'sieve': $r = sieve($n ?: 1000000); break;
        case 'collatz': $r = collatz($n ?: 300000); break;
        case 'tak': $r = tak($n ?: 24, 16, 8); break;
        case 'primes': $r = count_primes($n ?: 2000000); break;
        case 'gcd': $r = gcd_sum($n ?: 600); break;
        case 'pixels': $r = render_pixels($n ?: 800); break;
        default: return "unknown kernel\n";
    }
    return sprintf("%s = %s in %.1f ms\n", $k, $r, (microtime(true) - $t) * 1000);
}

if (!isset($KERNELS_ONLY)) {
    echo run_kernel($_GET['k'] ?? 'mandelbrot', (int)($_GET['n'] ?? 0));
}
