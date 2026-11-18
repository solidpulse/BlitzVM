<?php
// Bytecode 17c: $this properties in trait methods and closures (read, write, compound,
// increment, isset), index paths on them, and foreach by reference over them.

trait HasX {
    public function getX() {
        return $this->x;
    }
    public function setX($v) {
        $this->x = $v;
    }
    public function addX($v) {
        $this->x += $v;
    }
    public function incX() {
        $this->x++;
    }
    public function hasX() {
        return isset($this->x);
    }
    public function mapX() {
        $f = function () {
            return $this->x * 2;
        };
        return $f();
    }
    public function mapArrow() {
        $g = fn() => $this->x + 1;
        return $g();
    }
    public function itemAt($k) {
        return $this->items[$k];
    }
    public function setItem($k, $v) {
        $this->items[$k] = $v;
    }
}

class AX {
    use HasX;
    private $x = 1;
    public $items = ['a' => 1, 'b' => 2];
}

class BX {
    use HasX;
    protected $x = 10;
    public $items = [3, 4];
}

trait Bumps {
    public function bumpAll() {
        foreach ($this->items as &$v) {
            $v += 1;
        }
        unset($v);
        return implode(',', $this->items);
    }
}

class UX {
    use Bumps;
    private $items = [1, 2, 3];
}

class VX {
    use Bumps;
    protected $items = [4, 5];
}

class CX {
    private $p = 5;
    public function closureRead() {
        $f = function () {
            return $this->p;
        };
        return $f();
    }
    public function closureWrite() {
        $f = function ($v) {
            $this->p = $v;
        };
        $f(8);
        return $this->p;
    }
    public function closureCompound() {
        $f = function () {
            $this->p += 2;
        };
        $f();
        return $this->p;
    }
    public function closureForeach() {
        $f = function () {
            $vals = [1, 2];
            $this->p = 0;
            foreach ($vals as &$v) {
                $this->p += $v;
            }
            unset($v);
            return $this->p;
        };
        return $f();
    }
}

$a = new AX();
$b = new BX();
echo $a->getX(), ',', $b->getX(), "\n";
$a->setX(3);
$b->setX(11);
$a->addX(4);
$b->incX();
echo $a->getX(), ',', $b->getX(), ',', $a->hasX() ? 'y' : 'n', "\n";
echo $a->mapX(), ',', $a->mapArrow(), ',', $b->mapX(), ',', $b->mapArrow(), "\n";
echo $a->itemAt('b'), ',', $b->itemAt(0), "\n";
$a->setItem('a', 9);
$b->setItem(1, 8);
echo $a->itemAt('a'), ',', $b->itemAt(1), "\n";

echo (new UX())->bumpAll(), "\n";
echo (new VX())->bumpAll(), "\n";

$c = new CX();
echo $c->closureRead(), ',', $c->closureWrite(), ',', $c->closureCompound(), ',', $c->closureForeach(), "\n";
