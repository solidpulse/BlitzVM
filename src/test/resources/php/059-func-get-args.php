<?php

function sumAll() {
    $args = func_get_args();
    $total = 0;
    for ($i = 0; $i < func_num_args(); $i++) {
        $total += func_get_arg($i);
    }
    return $total . ':' . implode(',', $args);
}

function defaultsTest($a = 10, $b = 20) {
    return func_num_args() . ':' . count(func_get_args());
}

class VarArgObj {
    public function collect($first) {
        return $first . '/' . func_num_args() . '/' . implode('-', func_get_args());
    }
}

echo sumAll(), '|', sumAll(3, 4, 5), "\n";
echo defaultsTest(), '|', defaultsTest(1), '|', defaultsTest(1, 2, 3), "\n";
$v = new VarArgObj();
echo $v->collect('a', 'b', 'c'), "\n";

$c = function ($x) {
    return func_num_args() . ':' . func_get_arg(0) . ':' . implode(',', func_get_args());
};
echo $c(10, 20, 30), "\n";

$arrow = fn(...$x) => func_num_args() . ':' . implode(',', func_get_args());
echo $arrow('x', 'y'), "\n";
