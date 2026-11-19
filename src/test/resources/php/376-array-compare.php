<?php
// Array comparison: size first, then element by element in the left array's order.
var_dump([2.5, 1] <=> [1.5, 2], [1.5, 1] <=> [2.5, 2], [1, 2] <=> [1, 3], [1, 2] <=> [1, 2]);
var_dump([3] <=> [1, 2], [1, 2, 3] <=> [9, 9]);
var_dump(['a' => 1, 'b' => 2] <=> ['b' => 2, 'a' => 1], ['a' => 1, 'b' => 3] <=> ['b' => 2, 'a' => 1]);
var_dump(['a' => 1] <=> ['b' => 1], ['b' => 1] <=> ['a' => 1]);
var_dump([[1, 2], 'x'] <=> [[1, 3], 'a'], ['10', 2] <=> [9, 2], [null] <=> [false]);
var_dump([1, 2] < [1, 3], [1, 3] > [1, 2], [1, 2] <= [1, 2], [2] >= [1]);
$r = [['raw' => 1.5, 'id' => 1], ['raw' => 9.5, 'id' => 2], ['raw' => 4.0, 'id' => 3], ['raw' => 9.5, 'id' => 0]];
usort($r, fn($a, $b) => [$b['raw'], $a['id']] <=> [$a['raw'], $b['id']]);
echo implode(',', array_column($r, 'id')), "\n";
$m = [[2, 'b'], [1, 'z'], [2, 'a'], [1, 'a']];
sort($m);
echo json_encode($m), "\n";
var_dump(max([1, 5], [1, 7], [0, 9]), min([[3], [1, 1]]));
