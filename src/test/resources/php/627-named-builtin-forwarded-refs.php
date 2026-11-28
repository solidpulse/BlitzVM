<?php
// Named forwarding to a by-reference parameter: php warns and writes back to a temporary.
function bump(&$x) {
    $x++;
    return $x;
}

set_error_handler(function ($no, $str) {
    echo "warning: ", $str, "\n";
    return true;
});

$v = 1;
var_dump(call_user_func('bump', x: $v));
var_dump($v);

$v = 1;
var_dump(call_user_func_array('bump', ['x' => &$v]));
var_dump($v);

$v = 1;
var_dump(call_user_func('bump', x: $v));
var_dump($v);

$v = 1;
var_dump(call_user_func_array('bump', ['x' => $v]));
var_dump($v);

var_dump(call_user_func_array('sort', ['array' => [3, 1, 2]]));
var_dump(call_user_func_array('array_pop', ['array' => [1, 2]]));
var_dump(call_user_func_array('str_replace', ['search' => 'a', 'replace' => 'b', 'subject' => 'aaa', 'count' => 0]));

restore_error_handler();
