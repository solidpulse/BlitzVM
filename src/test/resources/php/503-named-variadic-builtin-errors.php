<?php
// A builtin whose variadic parameter php does not let collect names rejects a named argument
// with php's message, through every binding path.
class A { public static function f($a = 1, $b = 2) { return "$a-$b"; } }
class B extends A {
    public static function t() {
        try { var_dump(forward_static_call(['A', 'f'], b: 5)); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
        var_dump(forward_static_call(callback: ['A', 'f']));
    }
}
B::t();
foreach ([
    fn() => sprintf('%s', foo: 1),
    fn() => max(1, 2, foo: 3),
    fn() => register_shutdown_function('strlen', foo: 1),
    fn() => array_merge([1], foo: [2]),
    fn() => var_dump(1, foo: 2),
    fn() => printf('%s', foo: 1),
] as $f) {
    try { $f(); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
}
$f = 'sprintf';
try { $f('%s', foo: 1); } catch (Throwable $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
