<?php
$x = 10;
$x += 5;
echo $x, "\n";
$x -= 3;
echo $x, "\n";
$x *= 2;
echo $x, "\n";
$x /= 4;
echo $x, "\n";
$x %= 4;
echo $x, "\n";

$s = "hello";
$s .= " world";
echo $s, "\n";

$data = [];
$data["k"] ??= 99;
$data["k"] ??= 100;
echo $data["k"], "\n";

$i = 5;
echo $i++, "\n";
echo ++$i, "\n";
echo $i--, "\n";
echo --$i, "\n";
