<?php
// Argument unpacking: positional, string keys as named arguments, Traversable, by-reference.
set_error_handler(function ($no, $msg) { echo "warning: $msg\n"; return true; });

function three($a, $b, $c) {
    return "$a-$b-$c";
}
echo three(...[1, 2, 3]), "\n";
echo three(...['a' => 'A', 'b' => 'B', 'c' => 'C']), "\n";
echo three(1, ...['b' => 2, 'c' => 3]), "\n";

function variadic(...$xs) {
    return implode(',', $xs);
}
echo variadic(...[4, 5, 6]), "\n";
echo variadic(...['x' => 1, 'y' => 2]), "\n";
echo variadic(0, ...[1, 2]), "\n";

$it = new ArrayIterator([7, 8, 9]);
echo three(...$it), "\n";

function bump(&$n) {
    $n++;
}
$value = 41;
bump(...['n' => &$value]);
echo $value, "\n";

$nums = [1, 2];
$nums[0] = &$other;
$other = 10;
function firstRef(&$first) {
    $first += 5;
}
firstRef(...$nums);
echo $nums[0], "\n";

class Box {
    public function join(...$parts) {
        return implode('+', $parts);
    }
    public static function stat($a, $b) {
        return "$a$b";
    }
}
$box = new Box();
echo $box->join(...['a', 'b', 'c']), "\n";
echo Box::stat(...[1, 2]), "\n";
$closure = fn($a, $b) => $a * $b;
echo $closure(...[6, 7]), "\n";

try {
    three(...['a' => 1, 'x' => 2, 'b' => 3, 'c' => 4]);
} catch (Throwable $e) {
    echo get_class($e), ': ', str_replace(__DIR__ . '/', '', $e->getMessage()), "\n";
}
try {
    three(...['a' => 1, 'a' => 2]);
} catch (Throwable $e) {
    echo get_class($e), ': ', str_replace(__DIR__ . '/', '', $e->getMessage()), "\n";
}
try {
    three(...1);
} catch (Throwable $e) {
    echo get_class($e), ': ', str_replace(__DIR__ . '/', '', $e->getMessage()), "\n";
}
try {
    three(...['a' => 1]);
} catch (Throwable $e) {
    echo get_class($e), ': ', str_replace(__DIR__ . '/', '', $e->getMessage()), "\n";
}
