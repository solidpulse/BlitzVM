<?php
$tests = [
    ["img12", "img2", "img10", "img1"],
    ["a1b2", "a1b10", "a10b1", "a2b1"],
    ["1.5", "1.10", "1.2", "1"],
    ["x10y", "x9y", "x09y"],
    ["A", "a", "B", "b"],
    ["10", "2", "1", "02"],
    ["abc", "ABC", "aBc"],
    ["", "a", "0", "00"],
    ["a-1", "a-2", "a-10"],
];
foreach ($tests as $t) {
    $a = $t;
    natsort($a);
    echo json_encode($a), "\n";
    $b = $t;
    natcasesort($b);
    echo json_encode($b), "\n";
}
$a = ["b" => "img10", "a" => "img2", "c" => "img1"];
var_dump(natsort($a));
var_export($a);
echo "\n";
$b = ["x" => "A", "y" => "a", "z" => "B"];
natcasesort($b);
var_export($b);
echo "\n";
$empty = [];
var_dump(natsort($empty));
