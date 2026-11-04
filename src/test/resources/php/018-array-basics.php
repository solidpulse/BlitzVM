<?php
$a = [10, 20, 30];
$b = array("x" => 1, "y" => 2);

$a[] = 40;
$b["z"] = 3;

echo count($a), "\n";
echo count($b), "\n";

echo $a[0], " ", $a[3], "\n";
echo $b["x"], " ", $b["z"], "\n";
