<?php
$a = 1;
$b = &$a;
$b = 20;
echo "ab=$a,$b
";

$c = &$b;
$c += 5;
echo "abc=$a,$b,$c
";

unset($b);
$c = 99;
echo "after_unset_b=$a," . (isset($b) ? 'set' : 'unset') . ",$c
";

$b = 7;
$a = 50;
echo "reassigned_b=$a,$b,$c
";

$d = 500;
$c = &$d;
$c = 600;
echo "rebind=$a,$c,$d
";

function testLocalRefUnset() {
    $x = 10;
    $y = &$x;
    $y = 30;
    unset($x);
    $y += 5;
    $x = 1;
    return [$x, $y];
}
echo "local=" . implode(',', testLocalRefUnset()) . "
";
