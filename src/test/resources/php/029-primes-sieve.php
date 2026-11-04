<?php
function sieve($max) {
    $primes = [];
    $is_prime = [];
    for ($i = 2; $i <= $max; $i++) {
        $is_prime[$i] = true;
    }
    for ($p = 2; $p * $p <= $max; $p++) {
        if ($is_prime[$p]) {
            for ($i = $p * $p; $i <= $max; $i += $p) {
                $is_prime[$i] = false;
            }
        }
    }
    for ($i = 2; $i <= $max; $i++) {
        if ($is_prime[$i]) {
            $primes[] = $i;
        }
    }
    return $primes;
}

$result = sieve(30);
echo implode(", ", $result), "\n";
