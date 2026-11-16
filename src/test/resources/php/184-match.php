<?php
function classify($v) {
    return match ($v) {
        0 => 'int-zero',
        '0' => 'str-zero',
        false => 'bool-false',
        null => 'null',
        1, 2, => 'one-or-two',
        default => 'other',
    };
}

foreach ([0, '0', false, null, 1, 2, 3] as $x) {
    echo classify($x), "\n";
}

// match (true) with expressions and default placed early
$score = 85;
$grade = match (true) {
    default => 'F',
    $score >= 90 => 'A',
    $score >= 80 => 'B',
    $score >= 70 => 'C',
};
echo "grade=$grade\n";

// Chaining on match result and throw as arm value
echo (match ('a') {
    'a' => ['x', 'y'],
})[1], "\n";

try {
    $r = match (10) {
        1 => 'one',
        default => throw new InvalidArgumentException('bad-10'),
    };
} catch (InvalidArgumentException $e) {
    echo 'threw:', $e->getMessage(), "\n";
}

// UnhandledMatchError across types
$unhandled = [
    5,
    1.5,
    'hi',
    true,
    null,
    [1, 2],
    new stdClass(),
    fopen('php://memory', 'r'),
];
foreach ($unhandled as $u) {
    try {
        match ($u) {
            '__never__' => 0,
        };
    } catch (UnhandledMatchError $e) {
        echo $e->getMessage(), "\n";
    }
}
