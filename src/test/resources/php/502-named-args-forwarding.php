<?php
function pair($a, $b = 2) { return "$a-$b"; }
function collect(...$args) { return $args; }
function bump(&$x) { $x++; return $x; }
class Obj {}
class Svc {
    static function join($a, $b = 2) { return "s:$a-$b"; }
}
var_dump(call_user_func('pair', b: 5, a: 1));
var_dump(call_user_func('collect', x: 1, y: 2));
var_dump(call_user_func_array('pair', ['b' => 5, 'a' => 1]));
var_dump(call_user_func_array('collect', ['x' => 1, 'y' => 2]));
var_dump(call_user_func('strlen', string: 'abcdef'));
var_dump(call_user_func('Svc::join', b: 5, a: 1));
var_dump(forward_static_call_array(callback: ['Svc', 'join'], args: ['b' => 5, 'a' => 1]));
var_dump((new ReflectionFunction('pair'))->invoke(b: 5, a: 1));
var_dump((new ReflectionFunction('pair'))->invokeArgs(['b' => 5, 'a' => 1]));
$rm = new ReflectionMethod('Svc', 'join');
var_dump($rm->invoke(null, b: 5, a: 1));
var_dump($rm->invokeArgs(null, ['b' => 5, 'a' => 1]));
$closure = function ($a, $b = 2) { return "$a-$b"; };
var_dump($closure(b: 5, a: 1));
var_dump($closure->call(new Obj(), b: 5, a: 1));
$fc = pair(...);
var_dump($fc(b: 5, a: 1));
var_dump(call_user_func($closure, b: 5, a: 1));
var_dump(call_user_func_array($closure, ['b' => 5, 'a' => 1]));
var_dump($closure->__invoke(b: 5, a: 1));
class Invokable { function __invoke($a, $b = 2) { return "i:$a-$b"; } }
var_dump(call_user_func(new Invokable, b: 5, a: 1));
set_error_handler(function ($no, $str) { echo "warning: $str\n"; return true; });
$v = 1;
var_dump(call_user_func('bump', x: $v));
var_dump($v);
$v = 1;
var_dump(call_user_func_array('bump', ['x' => &$v]));
var_dump($v);
$v = 1;
var_dump((new ReflectionFunction('bump'))->invokeArgs(['x' => &$v]));
var_dump($v);
restore_error_handler();
foreach ([
    fn() => call_user_func('pair', z: 1),
    fn() => call_user_func('strlen', s: 'abc'),
    fn() => call_user_func('pair', 1, a: 2),
    fn() => call_user_func_array('collect', [1, 'x' => 2, 3]),
    fn() => (new ReflectionFunction('pair'))->invoke(z: 1),
    fn() => (new ReflectionMethod('Svc', 'join'))->invoke(null, z: 1),
    fn() => $closure->call(new Obj(), z: 1),
] as $call) {
    try { $call(); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
}
