<?php
// Bytecode 17c: destructuring: keyed and nested patterns, skipped entries, by-reference
// targets, array-element/property/static-property targets, and target keys evaluated in order.

class DestStatic {
    public static $a = 0;
    public static $b = 0;
    public $x = 0;
    public $y = 0;
}

function keyed_nested() {
    ['a' => $x, 'b' => [$y, $z]] = ['a' => 1, 'b' => [2, 3]];
    return "$x,$y,$z";
}

function by_reference() {
    $arr = [10, 20, 30];
    [&$p, $q] = $arr;
    $p = 99;
    $q = 77;
    return "$p,$q," . implode(',', $arr);
}

function skipped() {
    [, $second] = [5, 6, 7];
    list(, $third) = ['a', 'b', 'c'];
    return "$second,$third";
}

function nested_value() {
    [$a, [$b, $c]] = [1, [2, 3]];
    return "$a,$b,$c";
}

function element_targets() {
    $out = [];
    [$out['x'], $out['y']] = [8, 9];
    return implode(',', $out);
}

function from_call() {
    [$x, $y] = explode(',', 'a,b');
    return "$x-$y";
}

function deep_keyed() {
    $arr = ['k' => ['a' => 1, 'b' => 2]];
    ['k' => ['a' => $a, 'b' => $b]] = $arr;
    return "$a,$b";
}

function ordered_targets() {
    $y = [];
    [$x, $y[$x]] = [1, 2];
    return "$x," . implode(',', $y);
}

function static_and_property() {
    [$a, $b] = [10, 20];
    [DestStatic::$a, DestStatic::$b] = [$a + 1, $b + 2];
    $o = new DestStatic();
    [$o->x, $o->y] = [5, 6];
    return DestStatic::$a . ',' . DestStatic::$b . ',' . $o->x . ',' . $o->y;
}

function expressions_advance() {
    $i = 0;
    $a = [];
    [$a[$i++], $a[$i++]] = [7, 8];
    return $i . ':' . implode(',', $a);
}

echo keyed_nested(), "\n";
echo by_reference(), "\n";
echo skipped(), "\n";
echo nested_value(), "\n";
echo element_targets(), "\n";
echo from_call(), "\n";
echo deep_keyed(), "\n";
echo ordered_targets(), "\n";
echo static_and_property(), "\n";
echo expressions_advance(), "\n";
