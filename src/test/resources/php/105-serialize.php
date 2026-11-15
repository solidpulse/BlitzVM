<?php
$values = [
    null, true, false, 0, -42, PHP_INT_MAX, 0.1, 1.0, -0.0, 1.5e100, 3.14159, -2.5e-10,
    "", "hello", "héllo wörld", "quote \" and ; and } inside", "😀",
    [], [1, 2, 3], ["a" => 1, "b" => [true, null, 1.25]], [5 => "five", "k" => ["nested" => ["deep" => "é"]]],
    ["siteurl" => "http://localhost", "widget_text" => [2 => ["title" => "Hi", "text" => "<p>Ünïcode</p>"], "_multiwidget" => 1]],
];
foreach ($values as $v) {
    $s = serialize($v);
    echo $s, "\n";
    $back = unserialize($s);
    var_dump($back === $v || (is_float($v) && is_nan($v)));
}
var_dump(serialize(INF), serialize(-INF), unserialize("d:INF;"), unserialize("d:-INF;"), is_nan(unserialize("d:NAN;")));

// data produced by real PHP elsewhere, with multi-byte strings counted in bytes
var_dump(unserialize('a:2:{s:5:"title";s:6:"Café!";i:7;a:1:{s:2:"ü";b:1;}}'));
var_dump(unserialize('s:3:"€";'));
var_dump(unserialize("b:0;"), unserialize("i:+7;"), unserialize("d:1.0E+25;"), unserialize('a:0:{}'));
