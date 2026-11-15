<?php

$add = function ($a, $b = 10) {
    return $a + $b;
};
echo $add(5), '|', $add(5, 20), "\n";

$base = 100;
$arr = [1, 2];
$cap = function ($x) use ($base, $arr) {
    $base += 50;
    $arr[] = 99;
    return $x + $base + count($arr);
};
$base = 999;
$arr[] = 3;
echo $cap(1), '|', $cap(1), '|', $base, '|', count($arr), "\n";

echo (function ($n) { return $n * $n; })(7), "\n";

function makeAdder($n) {
    return function ($x) use ($n) {
        return $n + $x;
    };
}
$add5 = makeAdder(5);
echo $add5(12), "\n";

class Box {
    public const TAG = 'BOX';
    public int $val;
    public function __construct(int $v) { $this->val = $v; }
    public function label(): string { return static::TAG; }
    public function makeReader(): Closure {
        return function ($delta) {
            return $this->label() . ':' . ($this->val + $delta);
        };
    }
    public static function makeStaticChecker(): Closure {
        return static function () {
            return self::TAG . ':' . static::TAG;
        };
    }
    public function makeMethodStatic(): Closure {
        return static function () {
            return self::TAG;
        };
    }
}
class SubBox extends Box {
    public const TAG = 'SUB';
}
$b = new SubBox(20);
$reader = $b->makeReader();
echo $reader(5), "\n";
$b->val = 30;
echo $reader(5), "\n";
$st1 = SubBox::makeStaticChecker();
$st2 = $b->makeMethodStatic();
echo $st1(), '|', $st2(), "\n";

$nested = function ($a) {
    return function ($b) use ($a) {
        return function ($c) use ($a, $b) {
            return $a * 100 + $b * 10 + $c;
        };
    };
};
echo $nested(1)(2)(3), "\n";

var_dump(
    is_object($add),
    get_class($add),
    $add instanceof Closure,
    gettype($add),
    method_exists($add, '__invoke'),
    $add->__invoke(3, 4)
);
$cloned = clone $add5;
echo $cloned(8), "\n";
