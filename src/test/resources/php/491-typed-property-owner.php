<?php
declare(strict_types=1);
set_error_handler(function ($n, $s) { echo "warning:$s\n"; return true; });

trait HasNext { public ?self $next = null; }
class Base {
    public ?self $self = null;
    public ?Countable $countable = null;
    public int|string $scalar = 0;
}
final class Child extends Base {
    use HasNext;
    public ?parent $up = null;
    public function __construct(public readonly ?Base $owner = null) {}
}

$b = new Base(); $c = new Child($b);
for ($i = 0; $i < 3; $i++) {          // repeat so the per-site owner cache is reused
    $c->self = $c; $c->up = $b; $c->next = $c; $c->countable = new ArrayObject([$i]); $c->scalar = "s$i";
    $b->self = $b; $b->countable = new ArrayIterator([]);
}
var_dump($c->self === $c, $c->up === $b, $c->next === $c, count($c->countable), $c->scalar, $c->owner === $b);
foreach ([fn () => $c->up = $c, fn () => $b->self = new stdClass, fn () => $c->next = $b,
          fn () => $c->countable = new stdClass, fn () => $c->scalar = 1.5, fn () => $c->owner = $b] as $f) {
    try { $f(); echo "ok\n"; } catch (Error $e) { echo get_class($e), ": ", $e->getMessage(), "\n"; }
}
