<?php
$nums = [1, 2, 3];
array_walk($nums, function (&$v, $k) {
    $v = ($v * 10) + $k;
});
echo "walk=" . implode(',', $nums) . "
";

$withExtra = ['x' => 2, 'y' => 5];
array_walk($withExtra, fn(&$v, $k, $factor) => $v = "$k=" . ($v * $factor), 3);
echo "walk_extra=" . implode(',', $withExtra) . "
";

$nested = ['a' => 1, 'b' => ['c' => 2, 'd' => [3, 4]]];
array_walk_recursive($nested, function (&$v, $k) {
    $v = $v * 100;
});
echo "walk_rec={$nested['a']},{$nested['b']['c']},{$nested['b']['d'][0]},{$nested['b']['d'][1]}
";

function addRef(&$target, $amount) {
    $target += $amount;
    return $target;
}
$val = 15;
$res = call_user_func_array('addRef', [&$val, 25]);
echo "cufa=$val,$res
";

function do_action_sim($callbacks, $args) {
    foreach ($callbacks as $cb) {
        call_user_func_array($cb, $args);
    }
}
$state = new stdClass();
$state->n = 1;
$acc = 10;
do_action_sim([
    function ($obj, &$num) { $obj->n += 2; $num += 5; },
    function ($obj, &$num) { $obj->n *= 3; $num *= 2; },
], [&$state, &$acc]);
echo "action={$state->n},$acc
";

set_error_handler(function ($errno, $errstr) {
    echo "warn=" . (str_contains($errstr, 'must be passed by reference') ? 'ok' : $errstr) . "
";
    return true;
});
$sortArr = [2, 1];
usort($sortArr, function (&$a, &$b) {
    return $a <=> $b;
});
restore_error_handler();
echo "usort=" . implode(',', $sortArr) . "
";
