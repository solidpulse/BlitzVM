<?php
// The result type of an operator must not depend on what the same code saw earlier:
// a float, or an int overflow, at one call must not turn later int results into floats.
function add($a, $b) { return $a + $b; }
function sub($a, $b) { return $a - $b; }
function mul($a, $b) { return $a * $b; }
function dv($a, $b) { return $a / $b; }
function neg($a) { return -$a; }
function lt($a, $b) { return $a < $b; }
function eq($a, $b) { return $a == $b; }
var_dump(add(1.5, 2), add(1, 2), add(2, 0.5), add(3, 4));
var_dump(sub(1.5, 2), sub(5, 2), mul(1.5, 2), mul(3, 4));
var_dump(dv(1, 2), dv(2, 2), dv(6, -1), dv(1.5, 3), dv(9, 3), dv(PHP_INT_MIN, -1), dv(7, 2.0));
var_dump(neg(PHP_INT_MIN), neg(5), neg(2.5), neg(-3));
var_dump(lt(1, 1.5), lt(2, 1), lt(2.5, 3), eq(1, 1.0), eq(2, 3), eq(2.0, 2));
var_dump(1 <=> 2.5, 3 <=> 3);
