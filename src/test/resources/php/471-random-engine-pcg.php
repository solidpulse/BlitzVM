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
    echo "pcg $seed ", seq(new Random\Engine\PcgOneseq128XslRr64($seed), 400), "\n";
}

foreach (["0123456789abcdef", str_repeat("\xff", 16), "abcdefghijklmnop"] as $seed) {
    echo "pcgstr ", bin2hex($seed), " ", seq(new Random\Engine\PcgOneseq128XslRr64($seed), 200), "\n";
}

foreach ([0, 1, 2, 17, 1000, 1234567890123] as $advance) {
    $engine = new Random\Engine\PcgOneseq128XslRr64(42);
    $engine->jump($advance);
    echo "jump $advance ", seq($engine, 8), "\n";
}

$engine = new Random\Engine\PcgOneseq128XslRr64(42);
$engine->jump(PHP_INT_MAX);
echo "jumpmax ", seq($engine, 4), "\n";

try {
    (new Random\Engine\PcgOneseq128XslRr64(42))->jump(-1);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
try {
    new Random\Engine\PcgOneseq128XslRr64("short");
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
try {
    new Random\Engine\PcgOneseq128XslRr64([]);
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}

$engine = new Random\Engine\PcgOneseq128XslRr64(42);
$debug = $engine->__debugInfo();
echo "debug ", count($debug["__states"]), " ", $debug["__states"][0], " ", $debug["__states"][1], "\n";

for ($i = 0; $i < 1000; $i++) {
    $engine->generate();
}
$copy = unserialize(serialize($engine));
echo "roundtrip ", serialize($copy) === serialize($engine) ? "same" : "different", " ",
    seq($copy, 4), "\n";

$engine = new Random\Engine\PcgOneseq128XslRr64(7);
for ($i = 0; $i < 10; $i++) {
    $engine->generate();
}
$clone = clone $engine;
$engine->jump(5);
echo "clone ", seq($engine, 3), " ", seq($clone, 3), "\n";

echo "length ", strlen((new Random\Engine\PcgOneseq128XslRr64(1))->generate()), "\n";
