<?php
// Array destructuring: nested, keyed, by reference, missing keys, swap, in foreach.
set_error_handler(function ($no, $msg) { echo "warning: $msg\n"; return true; });

[$a, $b] = [1, 2];
echo "$a $b\n";

[$a, $b] = [$b, $a];
echo "$a $b\n";

[$x, [$y, $z]] = [10, [20, 30]];
echo "$x $y $z\n";

['first' => $f, 'second' => $s] = ['second' => 'S', 'first' => 'F'];
echo "$f $s\n";

[, $second] = ['skip', 'take'];
echo "$second\n";

[$p, $q] = [1];
var_dump($p, $q);

$src = [1, 2, 3];
[$r, &$ref] = $src;
$ref = 20;
echo implode(',', $src), "\n";

$rows = [[1, 'a'], [2, 'b'], [3, 'c']];
foreach ($rows as [$n, $label]) {
    echo "$n$label ";
}
echo "\n";

foreach ($rows as ['0' => $n, '1' => $label]) {
    echo "$label$n ";
}
echo "\n";

$keyed = ['id' => 7, 'name' => 'seven'];
['id' => $id, 'name' => $name] = $keyed;
echo "$id $name\n";

// Destructuring a string key that is missing falls back to null with a warning.
['absent' => $gone] = $keyed;
var_dump($gone);

// list() with a nested by-reference element copies the temporary array.
$tmp = [[1, 2], [3, 4]];
[[$u, $v], [$w, $t]] = $tmp;
echo "$u $v $w $t\n";
