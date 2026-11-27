<?php
// Property magic methods declared in a trait run for the using class and receive the real
// property name, not the trait class.
trait Properties
{
    public function __get($key) { echo 'get:', $key, ':', __TRAIT__, ':', self::class, "\n"; return 'v'; }
    public function __set($key, $value) { echo 'set:', $key, '=', $value, ':', __TRAIT__, ':', self::class, "\n"; }
    public function __isset($key) { echo 'isset:', $key, ':', __TRAIT__, ':', self::class, "\n"; return true; }
    public function __unset($key) { echo 'unset:', $key, ':', __TRAIT__, ':', self::class, "\n"; }
}
class UsesProperties { use Properties; }

$o = new UsesProperties;
var_dump($o->foo);
$o->bar = 1;
var_dump(isset($o->baz));
unset($o->qux);
var_dump(empty($o->nope));

// A higher-order proxy reads a dynamic property and then calls the collection with it.
class Collection
{
    use Properties;
    public array $items = [];
    public function __call($method, $parameters) { echo 'call:', $method, "\n"; return 'called'; }
}
$c = new Collection;
echo $c->first, "\n";
echo $c->items[0] ?? 'empty', "\n";
