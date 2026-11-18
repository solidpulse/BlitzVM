<?php
// Return by reference on bytecode: the callee returns the cell, and each reference context
// (=&, by-reference arguments, builtins, foreach, destructuring) keeps it.

function &store() {
    static $s = [3, 1, 2];
    return $s;
}

function addOne(&$arr) {
    $arr[] = 'one';
}

class Box {
    public $items = [];
    private static $all = ['k' => 0];

    public function &item($k) {
        return $this->items[$k];
    }

    public static function &all() {
        return self::$all;
    }

    public function &guarded() {
        try {
            return $this->items['g'];
        } finally {
            echo "finally ran\n";
        }
    }
}

function &literal() {
    return 5;
}

// The entry script's own top-level code runs on its AST root, so the cases run in a function.
function main() {
    // A plain call gets a copy.
    $copy = store();
    $copy[] = 'copy';
    echo count(store()), "\n";

    // By-reference user parameter and builtins.
    addOne(store());
    array_push(store(), 'pushed');
    sort(store());
    echo json_encode(store()), "\n";

    // foreach by reference over the returned reference.
    foreach (store() as &$v) {
        $v = is_int($v) ? $v * 10 : strtoupper($v);
    }
    unset($v);
    echo json_encode(store()), "\n";

    // Methods, static methods, and a missing element created by the reference.
    $b = new Box();
    $x = &$b->item('a');
    $x = 'set';
    echo json_encode($b->items), "\n";
    $all = &Box::all();
    $all['k']++;
    echo json_encode(Box::all()), "\n";
    $g = &$b->guarded();
    $g = 'via finally';
    echo $b->items['g'], "\n";

    // Destructuring with a reference.
    [, &$second] = store();
    $second = 'second';
    echo json_encode(store()), "\n";

    // A closure and a callable value.
    $n = 1;
    $c = function &() use (&$n) {
        return $n;
    };
    $r = &$c();
    $r = 42;
    $name = 'store';
    $s = &$name();
    $s = ['replaced'];
    echo $n, ' ', json_encode(store()), "\n";

    // Spread arguments keep the reference too.
    $args = ['b'];
    $y = &$b->item(...$args);
    $y = 'spread';
    echo $b->items['b'], "\n";

    // Returning a non-variable by reference.
    $l = &literal();
    $l++;
    echo $l, ' ', literal(), "\n";
}

main();
