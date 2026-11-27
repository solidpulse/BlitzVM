<?php
// Element writes and compounds through by-reference parameters and global arrays.

function bumpInts(array &$ra): void {
    $ra[0] += 5;
    $ra[1] = 9;
    $ra[2] *= 2;
}

function bumpFloats(array &$ra): void {
    $ra[0] += 0.5;
    $ra[1] = $ra[0] * 2;
    $ra[2] -= 1.0;
}

function appendThroughRef(array &$ra): void {
    $ra[3] = 4;
    $ra[] = 5;
    $ra[0] += 1;
}

$a = [1, 2, 3];
bumpInts($a);
var_dump($a);

$f = [1.5, 2.5, 3.5];
bumpFloats($f);
var_dump($f);

$b = [1, 2, 3];
appendThroughRef($b);
var_dump($b);

$g = [1, 2, 3];
function bumpGlobal(): void {
    global $g;
    $g[0] += 10;
    $g[1] = 20;
    $g[2] *= 3;
}
bumpGlobal();
var_dump($g);

$gf = [1.5, 2.5];
function bumpGlobalFloat(): void {
    global $gf;
    $gf[0] += 1.0;
    $gf[1] = $gf[0] * 2;
}
bumpGlobalFloat();
var_dump($gf);

// Top-level variables are globals too: these take the global element operations.
$top = range(1, 5);
for ($i = 0; $i < 5; $i++) {
    $top[$i] += 10;
}
var_dump($top);

$topf = range(1.0, 5.0);
for ($i = 0; $i < 5; $i++) {
    $topf[$i] *= 2.0;
}
var_dump($topf);

// A few thousand compiled calls through reference cells.
function hotByRef(array &$x, array &$y, int $rounds): void {
    $n = count($x);
    for ($k = 0; $k < $rounds; $k++) {
        for ($i = $n - 1; $i >= 0; $i--) {
            $y[$i] += $x[$i];
        }
    }
}

$x = range(1, 50);
$y = array_fill(0, 50, 0);
hotByRef($x, $y, 1000);
var_dump($y[0], $y[49]);
