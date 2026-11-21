<?php
// Reads and writes through every key shape normalizeKey handles: packed, hash, integer,
// numeric string, negative, huge, bool, float and null keys, with constant and variable keys.

function describe($label, $a) {
    echo $label, "=";
    $first = true;
    foreach ($a as $k => $v) {
        if (!$first) {
            echo ",";
        }
        $first = false;
        echo var_export($k, true), ":", var_export($v, true);
    }
    echo "\n";
}

$a = ["zero", "one", "two"];

// packed reads in and out of range, constant and variable keys
echo $a[0], $a[2], "\n";
$i = 1;
echo $a[$i], "\n";
var_dump(isset($a[0]), isset($a[3]), isset($a[-1]), isset($a[$i]));
var_dump(isset($a["1"]), isset($a["01"]), isset($a["1.0"]));

// integer keys: negative, huge, and the append counter after them
$b = [];
$b[-1] = "minus one";
$b[-5] = "minus five";
$b[PHP_INT_MAX] = "max";
$b[9223372036854775807] = "max literal";
$b[PHP_INT_MAX - 1] = "max minus one";
describe("ints", $b);

$c = [];
$c[0] = "a";
$c[2] = "c";
$c[1] = "b";
describe("order", $c);

// numeric strings: "5" becomes 5, "05" and "-0" stay strings
$d = [];
$d["5"] = "five";
$d["05"] = "zero five";
$d["-0"] = "minus zero";
$d["-5"] = "minus five";
$d["9223372036854775808"] = "too big";
$d[""] = "empty";
describe("strings", $d);

$key = "5";
$key2 = "05";
$key3 = "-0";
$key4 = "-5";
$key5 = "9223372036854775808";
describe("strings-variable", [$d[$key], $d[$key2], $d[$key3], $d[$key4], $d[$key5]]);
echo $d["5"], $d["05"], $d["-0"], $d["-5"], "\n";

// bool, float and null keys
$e = [];
$e[true] = "true";
$e[false] = "false";
$e[1.7] = "float";
$e[-2.9] = "negative float";
$e[null] = "null";
describe("other", $e);
$t = true;
$f = false;
$n = null;
$df = 1.7;
echo $e[$t], $e[$f], $e[$n], $e[$df], "\n";

// a hash array read by a constant key and by a variable key
$g = ["alpha" => 1, "beta" => 2, "gamma" => 3];
echo $g["alpha"], $g["beta"], $g["gamma"], "\n";
$k = "beta";
echo $g[$k], "\n";
var_dump(isset($g["alpha"]), isset($g["delta"]), isset($g[$k]));

// packed array read through numeric strings and bools
var_dump($a["2"], $a[true], $a[false]);
