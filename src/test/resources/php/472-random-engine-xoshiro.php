<?php

function seq(Random\Engine $engine, int $count): string
{
    $out = '';
    for ($i = 0; $i < $count; $i++) {
        $out .= bin2hex($engine->generate());
    }
    return $out;
}

$seeds = [0, 1, 42, -1, PHP_INT_MAX, PHP_INT_MIN, 123456789];
foreach ($seeds as $seed) {
    echo "xs $seed ", seq(new Random\Engine\Xoshiro256StarStar($seed), 400), "\n";
}

foreach ([str_repeat("\x01", 32), str_repeat("\xff", 32), "0123456789abcdefghijklmnopqrstuv"] as $seed) {
    echo "xsstr ", md5($seed), " ", seq(new Random\Engine\Xoshiro256StarStar($seed), 200), "\n";
}

foreach ([1, 2, 123456789] as $times) {
    $engine = new Random\Engine\Xoshiro256StarStar(42);
    for ($i = 0; $i < $times; $i++) {
        $engine->jump();
    }
    echo "jump $times ", seq($engine, 8), "\n";
}

foreach ([1, 2, 3] as $times) {
    $engine = new Random\Engine\Xoshiro256StarStar(42);
    for ($i = 0; $i < $times; $i++) {
        $engine->jumpLong();
    }
    echo "jumplong $times ", seq($engine, 8), "\n";
}

try {
    new Random\Engine\Xoshiro256StarStar("short");
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
try {
    new Random\Engine\Xoshiro256StarStar(str_repeat("\0", 32));
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
try {
    (new Random\Engine\Xoshiro256StarStar(1))->jump(1);
} catch (ArgumentCountError $e) {
    echo $e->getMessage(), "\n";
}

$engine = new Random\Engine\Xoshiro256StarStar(42);
$debug = $engine->__debugInfo();
echo "debug ", count($debug["__states"]), " ";
foreach ($debug["__states"] as $state) {
    echo $state, " ";
}
echo "\n";

for ($i = 0; $i < 1000; $i++) {
    $engine->generate();
}
$copy = unserialize(serialize($engine));
echo "roundtrip ", serialize($copy) === serialize($engine) ? "same" : "different", " ",
    seq($copy, 4), "\n";

$engine = new Random\Engine\Xoshiro256StarStar(7);
for ($i = 0; $i < 10; $i++) {
    $engine->generate();
}
$clone = clone $engine;
$engine->jump();
echo "clone ", seq($engine, 3), " ", seq($clone, 3), "\n";

echo "length ", strlen((new Random\Engine\Xoshiro256StarStar(1))->generate()), "\n";
