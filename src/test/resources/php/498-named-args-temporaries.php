<?php
// Named arguments: a call result bound to a builtin's by-reference parameter gets php's notice
// and a temporary (only a plain expression is an error), and a callable to a Java-implemented
// method binds its arguments by name.
set_error_handler(function (int $no, string $msg, string $file, int $line) {
    echo "notice: $msg (line $line)\n";
    return true;
});

var_dump(array_shift(array: explode(',', 'a,b')));
var_dump(end(array: explode(',', 'x,y,z')));
var_dump(array_pop(array: array_reverse([1, 2, 3])));
try {
    array_shift(array: [1, 2]);
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

class P { public $v = 42; }
$f = function () { return $this->v; };
$cb = [$f, 'bindTo'];
$g = $cb(newThis: new P);
var_dump($g());
try {
    $cb(nope: 1);
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
