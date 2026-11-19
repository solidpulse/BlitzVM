<?php
// A local that changes between numbers and non-numbers must keep PHP's types and
// must not rely on a fixed unboxed frame slot.
function mixed($n) {
    $value = null;
    for ($i = 0; $i < 3; $i++) {
        ++$value;
    }
    $value = 1.5;
    $value += 2;
    $value = 'x';
    $value .= 'y';
    return $value . ':' . $n;
}

function numbered($n) {
    $value = $n;
    $value += 0.25;
    $value *= 2;
    return $value;
}

echo mixed(7), "\n";
echo numbered(3), "\n";

$value = null;
for ($i = 0; $i < 4; $i++) {
    $value = $i % 2 === 0 ? $i : "s$i";
    echo gettype($value), ':', $value, "\n";
}

$count = 0;
$count += 1;
echo gettype($count), ':', $count, "\n";
