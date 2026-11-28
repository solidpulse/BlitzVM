<?php

function seq(Random\Engine $engine, int $count): string
{
    $out = '';
    for ($i = 0; $i < $count; $i++) {
        $out .= bin2hex($engine->generate());
    }
    return $out;
}

foreach ([0, 1, 42, -1, PHP_INT_MAX, PHP_INT_MIN, 2147483647] as $seed) {
    foreach ([MT_RAND_MT19937, MT_RAND_PHP] as $mode) {
        echo "mt $seed $mode ", seq(new Random\Engine\Mt19937($seed, $mode), 400), "\n";
    }
}

try {
    new Random\Engine\Mt19937(42, 2);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
try {
    new Random\Engine\Mt19937("abc");
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
try {
    new Random\Engine\Mt19937(42, 1, 3);
} catch (ArgumentCountError $e) {
    echo $e->getMessage(), "\n";
}

$engine = new Random\Engine\Mt19937(42);
$debug = $engine->__debugInfo();
echo "debug ", count($debug["__states"]), " ", $debug["__states"][0], " ",
    $debug["__states"][623], " ", $debug["__states"][624], " ", $debug["__states"][625], "\n";

for ($i = 0; $i < 700; $i++) {
    $engine->generate();
}
$debug = $engine->__debugInfo();
echo "debug2 ", count($debug["__states"]), " ", $debug["__states"][624], " ",
    $debug["__states"][625], "\n";

foreach ([0, 623, 624, 1300] as $count) {
    $engine = new Random\Engine\Mt19937(7, MT_RAND_PHP);
    for ($i = 0; $i < $count; $i++) {
        $engine->generate();
    }
    $copy = unserialize(serialize($engine));
    echo "roundtrip $count ", serialize($copy) === serialize($engine) ? "same" : "different",
        " ", seq($copy, 4), "\n";
}

$engine = new Random\Engine\Mt19937(123);
for ($i = 0; $i < 10; $i++) {
    $engine->generate();
}
$clone = clone $engine;
echo "clone ", seq($engine, 4), " ", seq($clone, 4), "\n";

$engine = new Random\Engine\Mt19937(42);
$raw = $engine->generate();
echo "length ", strlen($raw), " ", bin2hex($raw), "\n";
