<?php
// A __call/__callStatic declared in a trait runs for the using class, whether the call comes
// from a spread, a named-argument call or a callable value.
trait Magic
{
    public function __call($method, $parameters)
    {
        echo 'i:', $method, ':', json_encode($parameters), ':', __TRAIT__, ':', self::class, "\n";
    }

    public static function __callStatic($method, $parameters)
    {
        echo 's:', $method, ':', json_encode($parameters), ':', __TRAIT__, ':', self::class, "\n";
    }
}
class UsesMagic { use Magic; }

$o = new UsesMagic;
$name = 'foo';
$empty = [];
$o->{$name}(...$empty);
$o->{$name}(...['a', 'b']);
$o->{$name}(x: 1);
UsesMagic::{$name}(...$empty);
UsesMagic::{$name}(...['a']);
UsesMagic::{$name}(x: 2);

$f = [$o, 'foo']; $f(...[]); $f(1, 2);
$g = 'UsesMagic::foo'; $g(); $g(...[3]);
$h = Closure::fromCallable([$o, 'foo']); $h(...[]); $h(4);
$i = Closure::fromCallable('UsesMagic::foo'); $i(); $i(...[5]);
$j = $o->foo(...); $j(...[]); $j(6);
$k = UsesMagic::foo(...); $k(); $k(...[7]);
