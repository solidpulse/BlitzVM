<?php
// clone, __clone and the PHP 8.5 clone() with updated properties.
class Point {
    public function __construct(public int $x = 0, public int $y = 0) {
    }
    public function withX(int $x): static {
        $copy = clone $this;
        $copy->x = $x;
        return $copy;
    }
}

$p = new Point(1, 2);
$q = $p->withX(9);
echo "$p->x,$p->y $q->x,$q->y\n";
var_dump($p === $q);

class Counted {
    public static int $clones = 0;
    public array $items = [1, 2, 3];
    public ?Counted $child = null;
    public function __clone() {
        self::$clones++;
        $this->items[] = 99;
        if ($this->child !== null) {
            $this->child = clone $this->child;
        }
    }
}
$c = new Counted();
$c->child = new Counted();
$d = clone $c;
echo Counted::$clones, "\n";
echo implode(',', $c->items), " ", implode(',', $d->items), "\n";
var_dump($c->child === $d->child);

$with = clone($p, ['x' => 5, 'y' => 6]);
echo "$with->x,$with->y $p->x,$p->y\n";

class PrivateBag {
    private int $secret = 1;
    public function secret(): int {
        return $this->secret;
    }
    public function __clone() {
        $this->secret++;
    }
}
$bag = new PrivateBag();
$bagCopy = clone $bag;
echo $bag->secret(), " ", $bagCopy->secret(), "\n";

enum Colour: string {
    case Red = 'r';
}
try {
    $bad = clone Colour::Red;
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    $bad = clone 5;
} catch (Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
