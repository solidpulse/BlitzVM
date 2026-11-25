<?php
namespace App;

class Box {
    public array $items = [1, 2];
    public int $count = 0;
    public ?Box $next = null;
    public $ref;
    public int $uninit;
    public function __construct() { $x = 5; $this->ref = &$x; }
}

final class Point {
    public function __construct(public readonly int $x, public readonly int $y, public array $tags = []) {}
    public function withX(int $x): static { return clone($this, ['x' => $x]); }
}

class Counted {
    public static int $clones = 0;
    public array $log = [];
    public function __clone() { self::$clones++; $this->log[] = 'cloned'; }
}

function copyOf(object $o): object { return clone $o; }

$a = new Box();
$b = clone $a;
$b->items[] = 3;
$a->items[] = 99;
var_dump($a->items, $b->items);
$shared = 1;
$a->ref = &$shared;
$d = clone $a;
$d->ref = 'changed through the clone';
var_dump($shared, $a->ref);
try { var_dump($b->uninit); } catch (\Error $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
$b->dyn = 'dynamic';
$c = clone $b;
var_dump(isset($c->dyn), $c->dyn, isset($a->dyn));
$c->items[0] = 'c';
var_dump($b->items[0], $c->items[0]);

$p = new Point(1, 2, ['t']);
$q = $p->withX(10);
var_dump($p->x, $q->x, $q->y, $q->tags);
try { $q->x = 3; } catch (\Error $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }

$k = new Counted();
$k2 = clone $k;
$k2->log[] = 'more';
var_dump(Counted::$clones, $k->log, $k2->log);

// One site, many shapes: the copier's generic path.
$objects = [new Box(), new Point(3, 4), new Counted(), new \stdClass(), new \ArrayObject([1]), (function () { $o = new \stdClass(); $o->a = [1]; $o->b = 'x'; return $o; })()];
for ($round = 0; $round < 2000; $round++) {
    foreach ($objects as $o) { $copy = copyOf($o); }
}
foreach ($objects as $o) {
    $copy = copyOf($o);
    echo get_class($copy), ' ', json_encode(get_object_vars($copy)), "\n";
}
$s = $objects[5];
$s2 = copyOf($s);
$s2->a[] = 2;
var_dump($s->a, $s2->a, Counted::$clones > 2000);
