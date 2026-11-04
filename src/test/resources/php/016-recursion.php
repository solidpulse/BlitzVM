<?php
function factorial($n) {
    if ($n <= 1) {
        return 1;
    }
    return $n * factorial($n - 1);
}

function gcd($a, $b) {
    if ($b == 0) {
        return $a;
    }
    return gcd($b, $a % $b);
}

echo "Fact 5: ", factorial(5), "\n";
echo "Fact 6: ", factorial(6), "\n";
echo "GCD(48, 18): ", gcd(48, 18), "\n";
