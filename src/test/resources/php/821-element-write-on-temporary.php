<?php
// Element writes on temporary values: f()[] = v, $o->m()['k'] .= v, C::s()['n']++ ...
// ArrayAccess objects receive offsetGet/offsetSet; arrays and null are written into a dropped copy.
set_error_handler(function (int $no, string $msg) { echo "error $no: $msg\n"; return true; });

class Bag implements ArrayAccess {
    public array $items = ['s' => 'x', 'n' => 1];
    public function offsetExists(mixed $o): bool { echo "exists($o)\n"; return isset($this->items[$o]); }
    public function offsetGet(mixed $o): mixed { echo "get(" . var_export($o, true) . ")\n"; return $this->items[$o] ?? null; }
    public function offsetSet(mixed $o, mixed $v): void { echo "set(" . var_export($o, true) . ", " . var_export($v, true) . ")\n"; if ($o === null) { $this->items[] = $v; } else { $this->items[$o] = $v; } }
    public function offsetUnset(mixed $o): void { unset($this->items[$o]); }
}
class Holder {
    public function __construct(public Bag $bag) {}
    public function bag(): Bag { return $this->bag; }
    public static Bag $shared;
    public static function shared(): Bag { return self::$shared; }
}
$bag = new Bag();
function bag(): Bag { global $bag; return $bag; }
function arr(): array { return [1, 2]; }
function nothing() { return null; }
$calls = 0;
function counted(): Bag { global $bag, $calls; $calls++; return $bag; }

bag()[] = 'a';
bag()['k'] = 'b';
$h = new Holder($bag);
$h->bag()['m'] = 'c';
Holder::$shared = $bag;
Holder::shared()[] = 'd';
bag()['s'] .= 'y';
bag()['n'] += 5;
bag()['n']++;
--bag()['n'];
counted()['once'] = 1;
var_dump($calls);
$f = fn ($x) => bag()[] = $x;
$f('e');
(function () use ($bag) { return $bag; })()['z'] = 'f';
var_dump(bag()[] = 'g');
arr()[] = 3;
arr()[0] = 9;
var_dump(arr());
nothing()['k'] = 1;
var_dump(nothing());
var_dump($bag->items);
