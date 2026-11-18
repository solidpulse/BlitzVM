<?php
// Member initializer expressions on bytecode: class constants, property defaults and
// static property defaults that are not compile-time constants (they read constants or
// other class constants, so they run lazily on first use).
// The entry script's top-level code is still an AST root, so the cases run in functions.

class Base {
    const GREETING = 'hi';
    public $base = self::GREETING;
}

class A extends Base {
    const X = 10;
    const Y = self::X + 1;
    const Z = A::X + 2;
    const P = parent::GREETING . '!';
    public $p = [self::X, A::Y, self::Y => self::X, self::X + self::Y];
    public static $s = self::X * 3;
    public static $t = [self::X => parent::GREETING];
}

class BadConst {
    const BAD = UNDEFINED_CONSTANT;
}

class BadProperty {
    public $bad = UNDEFINED_PROPERTY;
}

class BadStaticProperty {
    public static $staticBad = UNDEFINED_STATIC_PROPERTY;
}

trait T {
    const A = 5;
    const B = self::A + 1;
    public $p = self::A * 2;
    public $arr = [self::A, self::B];
    public static $s = self::B + 1;

    public function show() {
        return [$this->p, $this->arr, self::$s, static::B, static::A];
    }
}

class UsesTrait {
    use T;
}

function newDefault($x = new A) {
    return get_class($x);
}

function values() {
    $a = new A();
    var_dump(A::Y, A::Z, A::P, $a->p, A::$s, A::$t);
    $c = new UsesTrait();
    var_dump($c->show(), UsesTrait::B, UsesTrait::A);
}

function failures() {
    try {
        var_dump(BadConst::BAD);
    } catch (Error $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
    try {
        var_dump(BadConst::BAD);
    } catch (Error $e) {
        echo 'again: ', $e->getMessage(), "\n";
    }
    try {
        var_dump(new BadProperty());
    } catch (Error $e) {
        echo 'property: ', $e->getMessage(), "\n";
    }
    try {
        var_dump(BadStaticProperty::$staticBad);
    } catch (Error $e) {
        echo 'static: ', $e->getMessage(), "\n";
    }
    try {
        var_dump(BadStaticProperty::$staticBad);
    } catch (Error $e) {
        echo 'static again: ', $e->getMessage(), "\n";
    }
}

values();
echo newDefault(), "\n";
failures();
