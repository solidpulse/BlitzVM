<?php
$a = ["a" => "green", "red", "blue", "red", "x" => 1, 2 => "1"];
$b = ["b" => "green", "yellow", "red"];
$c = [1, "blue"];
print_r(array_diff($a, $b));
print_r(array_diff($a, $b, $c));
print_r(array_diff($a));
print_r(array_intersect($a, $b));
print_r(array_intersect($a, $b, ["red"]));

$k1 = ["blue" => 1, "red" => 2, "green" => 3, "purple" => 4, 0 => "z"];
$k2 = ["green" => 5, "yellow" => 7, "cyan" => 8, "0" => "q"];
print_r(array_diff_key($k1, $k2));
print_r(array_intersect_key($k1, $k2));
print_r(array_diff_assoc(["a" => "green", "b" => "brown", "c" => "blue", "red"], ["a" => "green", "yellow", "red"]));
print_r(array_intersect_assoc(["a" => "green", "b" => "brown", "c" => "blue", "red"], ["a" => "green", "b" => "yellow", "blue", "red"]));

$base = ["citrus" => ["orange"], "berries" => ["blackberry", "raspberry"], "x" => 1];
$repl = ["citrus" => ["pineapple"], "berries" => [1 => "blueberry"], 5 => "new"];
print_r(array_replace($base, $repl));
print_r(array_replace_recursive($base, $repl));
print_r(array_replace([1, 2, 3], [1 => "b"], [3 => "d"]));
print_r($base);

print_r(array_merge_recursive(["color" => ["favorite" => "red"], 5], [10, "color" => ["favorite" => "green", "blue"]]));
print_r(array_merge_recursive(["a" => 1, "b" => [2]], ["a" => [3], "b" => 4], ["a" => 5]));

print_r(array_change_key_case(["FirSt" => 1, "SecOnd" => 4, 7 => "x"], CASE_UPPER));
print_r(array_change_key_case(["FirSt" => 1, "first" => 2]));

var_dump(array_first([]), array_first(["x" => 1, 2]), array_last([1, 2, 3]), array_last(["a" => ["n"]]));
var_dump(is_countable([]), is_countable("abc"), is_countable(3));

$input = ["red", "green", "blue", "yellow"];
$removed = array_splice($input, 2);
print_r($input);
print_r($removed);
$input = ["red", "green", "blue", "yellow"];
array_splice($input, 1, -1);
print_r($input);
$input = ["red", "green", "blue", "yellow"];
array_splice($input, 1, count($input), "orange");
print_r($input);
$input = ["red", "green", "blue", "yellow"];
array_splice($input, -1, 1, ["black", "maroon"]);
print_r($input);
$input = ["a" => "x", 5 => "y", "b" => "z", 9 => "w"];
print_r(array_splice($input, 1, 2, ["n1", "n2", "n3"]));
print_r($input);
$input = [1, 2];
array_splice($input, 5, 0, [3]);
print_r($input);
