<?php

function show($label, $value) {
    echo "== ", $label, " ==\n";
    print_r($value);
    echo "\n";
}

class Box {
    public $items = [1, 2, 3];
    public static $shared = ["a", "b", "c"];
}

// A copy taken before the mutation must keep its own value for every mutator.
$a = [1, 2, 3];
$b = $a;
array_pop($a);
show("pop a", $a);
show("pop b", $b);

$a = [1, 2, 3];
$b = $a;
array_push($a, 4);
show("push a", $a);
show("push b", $b);

$a = [1, 2, 3];
$b = $a;
array_shift($a);
show("shift a", $a);
show("shift b", $b);

$a = [1, 2, 3];
$b = $a;
array_unshift($a, 0);
show("unshift a", $a);
show("unshift b", $b);

$a = [1, 2, 3, 4];
$b = $a;
$removed = array_splice($a, 1, 2, [9]);
show("splice a", $a);
show("splice b", $b);
show("splice removed", $removed);

// A by-reference alias is the same array, so mutations through it are visible.
$a = [1, 2, 3];
$r = &$a;
array_push($r, 4);
show("ref push a", $a);
show("ref push r", $r);

// An array stored in an object property and one in a static property.
$box = new Box();
array_push($box->items, 4);
show("object property", $box->items);
array_shift(Box::$shared);
show("static property", Box::$shared);

// A constant array is immutable: mutating a copy of it must leave the constant intact.
const C = [1, 2];
$x = C;
array_pop($x);
show("const x", $x);
show("const C", C);

// Passing by value gives the callee its own array; the caller's array is untouched.
function popIt($arr) {
    array_pop($arr);
    return $arr;
}
$a = [1, 2, 3];
$out = popIt($a);
show("by value out", $out);
show("by value a", $a);

// foreach by value iterates the original elements even when the loop body mutates the array.
$a = [1, 2, 3];
$seen = [];
foreach ($a as $v) {
    $seen[] = $v;
    array_pop($a);
}
show("foreach seen", $seen);
show("foreach a", $a);

// Nested arrays: the outer copy shares the inner one until it is mutated.
$a = ["k" => [1, 2]];
$b = $a;
array_push($a["k"], 3);
show("nested a", $a);
show("nested b", $b);
