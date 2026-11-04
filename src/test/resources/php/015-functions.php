<?php
function greet($name, $greeting = "Hello") {
    return $greeting . ", " . $name . "!";
}

function add3($a, $b = 10, $c = 20) {
    return $a + $b + $c;
}

echo greet("World"), "\n";
echo greet("Bob", "Hi"), "\n";
echo add3(5), "\n";
echo add3(5, 5), "\n";
echo add3(1, 2, 3), "\n";
