<?php
// Bytecode 17c: =& on a call that returns by reference binds the returned reference.

function &store() {
    static $s = [];
    return $s;
}

class Registry {
    private $items = [];
    private static $shared = [];

    public function &items() {
        return $this->items;
    }

    public static function &shared() {
        return self::$shared;
    }

    public function count() {
        return count($this->items);
    }

    public static function sharedCount() {
        return count(self::$shared);
    }
}

function viaFunction() {
    $a = &store();
    $a[] = 1;
    $b = &store();
    return count($b);
}

function viaMethod() {
    $r = new Registry();
    $i = &$r->items();
    $i[] = 'x';
    $i[] = 'y';
    return $r->count();
}

function viaStatic() {
    $s = &Registry::shared();
    $s['k'] = 1;
    return Registry::sharedCount();
}

function viaValue() {
    $f = 'store';
    $a = &$f();
    $a[] = 2;
    return count(store());
}

echo viaFunction(), "\n";
echo viaMethod(), "\n";
echo viaStatic(), "\n";
echo viaValue(), "\n";
