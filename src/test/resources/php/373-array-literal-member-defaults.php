<?php
// Array-literal class constants and property defaults are built once and shared;
// every write must still copy.
class Cfg {
    const LIST = ['a' => [1, 2], 'b' => null, 'c' => true];
    public $items = array();
    public $nested = ['x' => ['y' => 1]];
    public static $cache = [];
    public static $seed = [1, 2, 3];
}
class Sub extends Cfg {}

$c = Cfg::LIST;
$c['a'][] = 3;
var_dump($c['a'], Cfg::LIST['a'], Sub::LIST === Cfg::LIST);

$o1 = new Cfg();
$o2 = new Cfg();
$o1->items[] = 'one';
$o1->nested['x']['y'] = 2;
var_dump($o1->items, $o2->items, $o1->nested, $o2->nested);

Cfg::$cache['k'] = 'v';
Sub::$seed[] = 4;
var_dump(Cfg::$cache, Sub::$cache, Cfg::$seed);

function touch_it(array $a) { $a[] = 'x'; return count($a); }
var_dump(touch_it(Cfg::LIST), count(Cfg::LIST));
$r = &$o2->items;
$r[] = 'ref';
var_dump($o2->items, (new Cfg())->items);
