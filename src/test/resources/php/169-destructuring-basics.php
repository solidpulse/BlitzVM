<?php
list($a, $b) = [10, 20];
[$c, $d] = [30, 40];
echo "basic=$a,$b,$c,$d
";

list(, $second, , $fourth) = [1, 2, 3, 4];
[, $s2, ] = ['x', 'y', 'z'];
echo "skip=$second,$fourth,$s2
";

$res = ([$m, $n] = [7, 8]);
echo "expr=" . implode(',', $res) . ":$m,$n
";

$x = 'first';
$y = 'second';
[$x, $y] = [$y, $x];
echo "swap=$x,$y
";

[$n1, $n2] = null;
var_dump($n1, $n2);
