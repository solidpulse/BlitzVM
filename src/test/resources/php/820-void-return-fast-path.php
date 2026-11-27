<?php
// void functions, methods and closures return null inline; their result reads as null.
function implicitVoid(int $x): void {
    $y = $x + 1;
}
function explicitVoid(int $x): void {
    if ($x > 0) {
        return;
    }
    echo "non-positive\n";
}
class Box {
    private int $n = 0;
    public function bump(): void { $this->n++; }
    public static function noop(): void {}
    public function get(): int { return $this->n; }
}
$box = new Box();
$sum = 0;
for ($i = 0; $i < 5000; $i++) {
    implicitVoid($i);
    explicitVoid($i);
    $box->bump();
    Box::noop();
    $sum += $i;
}
var_dump($box->get(), $sum);
var_dump(implicitVoid(1), explicitVoid(0), Box::noop());
$f = function (): void { echo "closure ran\n"; };
var_dump($f());
$g = static fn() => null;
var_dump($g());
