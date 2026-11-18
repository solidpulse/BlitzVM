<?php
// Bytecode 17a: new with defaults, an inherited/variadic constructor, a by-reference
// constructor parameter (the argument's cell must be written back), a dynamic class name,
// a class without a constructor, and a built-in throwable initialized from its constructor.

class NDef {
    public $a;
    public $b;
    private $c = 5;
    public function __construct($a, $b = 10) {
        $this->a = $a;
        $this->b = $b;
    }
    public function sum() {
        return $this->a + $this->b + $this->c;
    }
}

class NChild extends NDef {
    public function __construct() {
        parent::__construct(1, 2);
        $this->a += 100;
    }
}

class NRef {
    public $value;
    public function __construct(&$value) {
        $value++;
        $this->value = $value;
    }
}

class NVar {
    public $args;
    public function __construct(...$args) {
        $this->args = $args;
    }
}

class NEmpty {
    public $x = 7;
}

$d = new NDef(1);
echo $d->sum(), "\n";
$d2 = new NDef(1, 2);
echo $d2->sum(), "\n";
$c = new NChild();
echo $c->sum(), "\n";

$n = 4;
$r = new NRef($n);
echo $r->value, ':', $n, "\n";

$v = new NVar(1, 2, 3);
echo implode(',', $v->args), "\n";

$e = new NEmpty();
echo $e->x, "\n";

$class = 'NEmpty';
$dyn = new $class();
echo $dyn->x, "\n";

try {
    throw new RuntimeException('boom');
} catch (RuntimeException $ex) {
    echo get_class($ex), ':', $ex->getMessage(), "\n";
}
