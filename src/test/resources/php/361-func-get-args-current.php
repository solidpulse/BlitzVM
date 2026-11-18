<?php
function currentValue($a) { $a = 9; var_dump(func_get_args()); }
currentValue(1);

function byRef(&$a) { $a = 9; var_dump(func_get_args()); }
$x = 1;
byRef($x);
var_dump($x);

function unsetOne($a, $b, $c) { unset($b); var_dump(func_get_args()); var_dump(func_num_args()); }
unsetOne(1, 2, 3);

function extra($a) { var_dump(func_get_args()); var_dump(func_get_arg(1)); }
extra(1, 2);

function defaults($a = 5, $b = 6) { var_dump(func_get_args()); }
defaults();
defaults(1);
defaults(b: 2);
defaults(a: 1);

function variadic($a, ...$rest) { $rest[0] = 9; var_dump(func_get_args()); var_dump(func_num_args()); }
variadic(1, 2, 3);
variadic(1, named: 2);

function dynamicScope($a) { $name = 'a'; $$name = 8; var_dump(func_get_args()); }
dynamicScope(1);

function byRefVariadic(&...$rest) { $rest[0] = 9; var_dump(func_get_args()); }
$p = 1;
$q = 2;
byRefVariadic($p, $q);
var_dump($p);

class Counter {
    public function add($by) { $by = 10; var_dump(func_get_args()); }
}
(new Counter)->add(1);
$closure = function ($value) { $value = 7; return func_get_args(); };
var_dump($closure(1));
