<?php
// Top-level function is hoisted
echo hoisted_top(), "\n";

// Conditional function in if(false) must not exist
if (false) {
    function never_declared() {
        return 'nope';
    }
}
var_dump(function_exists('never_declared'));

// Conditional function is NOT hoisted before execution reaches it
var_dump(function_exists('cond_fn'));
if (true) {
    function cond_fn() {
        return 'cond_ok';
    }
}
var_dump(function_exists('cond_fn'), cond_fn());

// Polyfill guard included multiple times via non-_once include
var_dump(function_exists('poly_fn'));
include 'inc/polyfill.php';
include 'inc/polyfill.php';
var_dump(function_exists('poly_fn'), poly_fn('hi'));

// Conditional function inside else, loop, block, and another function
$flag = false;
if ($flag) {
    function branch_fn() { return 'then'; }
} else {
    function branch_fn() { return 'else'; }
}
echo branch_fn(), "\n";

for ($i = 0; $i < 2; $i++) {
    if ($i === 1) {
        function loop_fn() { return 'from_loop'; }
    }
}
echo loop_fn(), "\n";

{
    function block_fn() { return 'from_block'; }
    var_dump(function_exists('block_fn'), block_fn());
}

function outer_maker() {
    function inner_made() {
        return 'made:' . __FUNCTION__;
    }
    return 'outer_done';
}
var_dump(function_exists('inner_made'));
echo outer_maker(), "\n";
var_dump(function_exists('inner_made'), inner_made());

function hoisted_top() {
    return 'hoisted_ok';
}
