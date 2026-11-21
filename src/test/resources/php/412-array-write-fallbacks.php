<?php
// Writes that exercise every fallback: growing packed arrays, converting to a hash,
// replacing reference elements, unset in the middle, copy-on-write and writing to literals.

function show($label, $a) {
    echo $label, "=", json_encode($a), "\n";
}

// grow a packed array past the first allocation and the doubling point
$a = [];
for ($i = 0; $i < 20; $i++) {
    $a[] = $i * $i;
}
show("grow", $a);
echo count($a), " ", $a[19], "\n";

// replace in place and append
$a[10] = "ten";
$a[] = "end";
show("replace", $a);

// a string key turns the packed array into a hash; appends then use nextIndex
$b = [1, 2, 3];
$b["x"] = 9;
$b[] = 10;
$b["y"] = 11;
$b[] = 12;
show("hashapp", $b);

// a negative key first: the auto index continues from the highest integer key
$c = [];
$c[-3] = "neg";
$c[] = "zero";
$c[] = "one";
show("neg", $c);

// PHP_INT_MAX as the next index appends at the saturated counter
$d = [];
$d[PHP_INT_MAX - 1] = "max-1";
$d[] = "max";
show("max", $d);

// reference elements are replaced through their cell, not overwritten
$g = ["a" => 1, "b" => 2];
$g["b"] = &$g["a"];
$g["b"] = 7;
show("refreplace", $g);
$g["a"] = 9;
show("refreplace2", $g);

$h = ["a" => 1];
$h["a"] = &$h["b"];
$h["a"] = 5;
show("refbind", $h);

// unset in the middle of a packed array, then foreach and append
$i = [0, 1, 2, 3, 4];
unset($i[2]);
show("unset-packed", $i);
$seen = [];
foreach ($i as $k => $v) {
    $seen[] = $k . ":" . $v;
}
echo implode(",", $seen), "\n";
$i[] = 5;
show("unset-append", $i);
unset($i[0]);
$i[] = 6;
show("unset-append2", $i);

// unset in the middle of a hash array, then foreach and writes
$j = ["a" => 1, "b" => 2, "c" => 3];
unset($j["b"]);
show("unset-hash", $j);
$seen = [];
foreach ($j as $k => $v) {
    $seen[] = $k . ":" . $v;
}
echo implode(",", $seen), "\n";
$j["d"] = 4;
$j["a"] = 10;
show("unset-hash-write", $j);

// copy-on-write through assignment and function arguments
$k = [1, 2, 3];
$l = $k;
$l[] = 4;
function addOne($arr) {
    $arr[] = 9;
    $arr[0] = 100;
    return $arr;
}
$m = addOne($k);
show("cow-k", $k);
show("cow-l", $l);
show("cow-m", $m);

function addRef(&$arr) {
    $arr[] = 8;
}
$n = [1, 2];
addRef($n);
show("byref", $n);

$o = ["x" => [1, 2]];
$p = $o;
$p["x"][] = 3;
show("cow-nested-o", $o);
show("cow-nested-p", $p);

// writes to literal arrays (the parser builds these immutable and they copy on write)
$q = [1, 2, 3];
$q[0] = 9;
$q["a"] = 1;
show("literal", $q);
$r = [["a" => 1], ["b" => 2]];
$r[0]["a"] = 5;
$r[1]["c"] = 3;
show("nested-literal", $r);

// constant and variable keys on the same sites
$s = [];
$key = "k";
$s["k"] = 1;
$s[$key] = 2;
$s["k"] += 3;
$s[$key] += 4;
echo $s["k"], "\n";

$t = [];
$idx = 0;
$t[0] = 1;
$t[$idx] = 2;
echo $t[0], "\n";

$u = [];
$u[] = 1;
$u["tail"] = 2;
$u[] = 3;
$u[] = 4;
show("append-into-hash", $u);

// the errors the write and append paths still raise
$max = [PHP_INT_MAX => "max"];
try {
    $max[] = "no";
} catch (Error $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
$scalar = 5;
try {
    $scalar[] = 1;
} catch (Error $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
$empty = "";
try {
    $empty[] = 1;
} catch (Error $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
