<?php

$inc = fn($x) => $x + 1;
echo $inc(41), "\n";

$k = 10;
$nums = [1, 2];
$f = fn($x) => $x * $k + count($nums);
$k = 99;
$nums[] = 3;
echo $f(4), '|', count($nums), "\n";

$z = 5;
$nested = fn($a) => fn($b) => fn($c) => $a + $b + $c + $z;
$z = 100;
echo $nested(1)(2)(3), "\n";

$ops = [
    fn($x) => $x + 1,
    fn($x) => $x * 2,
    fn($x) => $x * $x,
];
echo $ops[0](3), '|', $ops[1](3), '|', $ops[2](3), "\n";

$outer = 7;
$localWrite = fn($x) => ($outer = $x + 1);
echo $localWrite(20), '|', $outer, "\n";

$checkUndef = fn($x) => (isset($neverDefined) ? $neverDefined : 0) + $x;
$neverDefined = 77;
echo $checkUndef(5), "\n";

class ArrowHost {
    public const KIND = 'host';
    private int $n;
    public function __construct(int $n) { $this->n = $n; }
    public function fnMaker(): Closure {
        return fn($x = 2) => self::KIND . ':' . ($this->n * $x);
    }
    public function staticFnMaker(): Closure {
        return static fn() => static::KIND;
    }
}
class SubArrowHost extends ArrowHost {
    public const KIND = 'subhost';
}
$h = new SubArrowHost(6);
$fm = $h->fnMaker();
$sfm = $h->staticFnMaker();
echo $fm(), '|', $fm(3), '|', $sfm(), "\n";
