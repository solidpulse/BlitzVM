<?php
// By-reference generators on bytecode: a yield suspends with the cell of an lvalue, so a
// by-reference foreach writes through it. Non-variable yields get php's notice and a
// temporary cell; the entry script's top-level code is still an AST root, so the cases
// run in functions.

function &gen(array &$a) {
    foreach ($a as $k => &$v) {
        yield $k => $v;
    }
}

function &scalars() {
    $x = 'a';
    yield $x;
    $y = 'b';
    yield 'k' => $y;
}

function &nonVariables() {
    yield 1 + 1;
    yield 'n' => null;
}

function byValue() {
    yield 1;
}

class Bag {
    private $items = ['a' => 1, 'b' => 2];
    public static $all = [3, 4];

    public function &items() {
        foreach ($this->items as $k => &$v) {
            yield $k => $v;
        }
    }

    public static function &all() {
        foreach (self::$all as $k => &$v) {
            yield $k => $v;
        }
    }
}

function arrays() {
    $arr = [1, 2];
    foreach (gen($arr) as &$x) {
        $x *= 10;
    }
    unset($x);
    echo json_encode($arr), "\n";

    // A read from current() is a copy: only a by-reference foreach writes through.
    $g = gen($arr);
    var_dump($g->key(), $g->current());
    $copy = $g->current();
    $copy = 0;
    echo json_encode($arr), "\n";

    // Keys and values of a by-reference generator.
    $other = ['p' => 7, 'q' => 8];
    foreach (gen($other) as $k => &$v) {
        echo "$k=$v ";
        $v += 1;
    }
    unset($v);
    echo json_encode($other), "\n";
}

function scalarsAndNonVariables() {
    $g = scalars();
    var_dump($g->key(), $g->current());
    $g->next();
    var_dump($g->key(), $g->current());

    foreach (nonVariables() as $k => &$v) {
        echo $k, '=';
        var_dump($v);
        $v = 'written';
    }
}

function objects() {
    $b = new Bag();
    foreach ($b->items() as $k => &$x) {
        $x += 10;
    }
    unset($x);
    foreach ((new Bag())->items() as $k => $v) {
        echo "$k=$v ";
    }
    echo "\n";

    foreach (Bag::all() as &$x) {
        $x *= 2;
    }
    unset($x);
    echo json_encode(Bag::$all), "\n";
}

function notByReference() {
    try {
        foreach (byValue() as &$v) {
            $v = 0;
        }
    } catch (Exception $e) {
        echo get_class($e), ': ', $e->getMessage(), ':', $e->getLine(), "\n";
    }
}

function closures() {
    $n = 5;
    $gen = function &() use (&$n) {
        yield $n;
    };
    foreach ($gen() as &$x) {
        $x += 3;
    }
    unset($x);
    echo $n, "\n";
}

arrays();
scalarsAndNonVariables();
objects();
notByReference();
closures();
