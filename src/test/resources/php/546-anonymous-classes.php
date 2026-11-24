<?php
interface Shape { public function area(): int; }
interface Named {}
abstract class Base implements Shape { public int $w = 2; }
class Square extends Base implements Named { public function area(): int { return $this->w * $this->w; } }

$anon = new class extends Base implements Named {
    public function area(): int { return 9; }
    public function extra(): string { return 'extra'; }
};

function takeBase(Base $b): Base { return $b; }
function takeShape(Shape $s): Shape { return $s; }
function takeNamed(Named $n): Named { return $n; }
function takeSquare(Square $s): Square { return $s; }

var_dump(takeBase($anon) === $anon);
var_dump(takeShape($anon) === $anon);
var_dump(takeNamed($anon) === $anon);
echo $anon->area(), "\n";
echo $anon->extra(), "\n";

var_dump($anon instanceof Base);
var_dump($anon instanceof Shape);
var_dump($anon instanceof Named);
var_dump($anon instanceof Square);
var_dump($anon instanceof stdClass);

$plain = new stdClass();
var_dump($plain instanceof Base, $plain instanceof Shape, $plain instanceof Named);
