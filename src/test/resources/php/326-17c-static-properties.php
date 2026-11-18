<?php
// Bytecode 17c: static properties as places: index writes and appends, isset, compound
// assign, unset of an element, private access, self/parent/static forwarding, a trait's
// per-using-class property, and a static property read inside a closure.

class S {
    public static $cache = [];
    public static $list = [];
    public static $x = ['a' => 1, 'b' => 2];
    public static $n = 0;
    private static $secret = 'p';

    public static function addToList($v) {
        static::$list[] = $v;
    }
    public static function bump() {
        self::$n += 1;
    }
    public static function secret() {
        return self::$secret;
    }
    public static function touch() {
        self::$cache['c'] = 1;
        static::$list[] = 4;
        self::$n += 1;
        return isset(self::$x['a']) ? 'yes' : 'no';
    }
}

function cache_set($key, $value) {
    S::$cache[$key] = $value;
}
function list_add($value) {
    S::$list[] = $value;
}
function has_a() {
    return isset(S::$x['a']);
}
function has_x() {
    return isset(S::$x) ? 'yes' : 'no';
}
function bump_global() {
    S::$n += 1;
}
function read_n() {
    return S::$n;
}
function drop_b() {
    unset(S::$x['b']);
}
function read_x() {
    return S::$x;
}

cache_set('k', 'v');
cache_set('j', [1, 2]);
echo S::$cache['k'], ',', implode(',', S::$cache['j']), "\n";
list_add(1);
list_add(2);
S::addToList(3);
echo implode(',', S::$list), "\n";
echo has_a() ? 'yes' : 'no', "\n";
echo has_x(), "\n";
bump_global();
bump_global();
S::bump();
echo read_n(), "\n";
drop_b();
echo implode(',', read_x()), "\n";
echo S::secret(), "\n";

trait HasV {
    public static $v = 'trait';
    public static function getV() {
        return self::$v;
    }
    public static function putV($x) {
        self::$v = $x;
    }
}

class A {
    use HasV;
}
class B {
    use HasV;
}

A::putV('a');
B::putV('b');
echo A::getV(), ',', B::getV(), ',', HasV::$v, "\n";

class Base {
    public static $count = 0;
    public static function inc() {
        static::$count++;
    }
}
class Child extends Base {
    public static function childInc() {
        parent::$count += 2;
    }
}

Base::inc();
Child::inc();
Child::childInc();
echo Base::$count, ',', Child::$count, "\n";

class C {
    private static $p = 4;
    public function viaClosure() {
        $f = function () {
            return self::$p;
        };
        return $f();
    }
    public static function viaArrow() {
        $g = fn() => self::$p;
        return $g();
    }
}

echo (new C())->viaClosure(), ',', C::viaArrow(), "\n";

echo S::touch(), ',', S::$cache['c'], ',', implode(',', S::$list), ',', S::$n, "\n";
