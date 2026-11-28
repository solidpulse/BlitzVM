<?php
// A for-condition may be a comma list: every expression runs in order and the last one decides.
$n = 0;
for ($i = 0; print("c$i "), $n += 10, $i < 3; $i++) { echo "[$n] "; }
echo "\n";
for ($x = 5; $x--, $x > 2;) echo $x, " ";
echo "\n";
function f() { for ($y = 2; printf("y=%d ", $y), $y--;) {} echo "\n"; }
f();
$k = 0; while ($k < 1) { for ($j = 0; $j++, $j < 3;) echo $j; $k++; }
echo "\n";
