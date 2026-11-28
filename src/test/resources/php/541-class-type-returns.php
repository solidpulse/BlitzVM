<?php
interface Shape { public function area(): int; }
interface Named {}
interface Tagged extends Named {}
abstract class Base implements Shape { public int $w = 2; }
class Square extends Base implements Tagged { public function area(): int { return $this->w * $this->w; } }
class Other {}
class Deep extends Square {}

function returnSquare(): Square { return new Square(); }
function returnBase(): Base { return new Square(); }
function returnShape(): Shape { return new Square(); }
function returnNamed(): Named { return new Square(); }
function returnTagged(): Tagged { return new Square(); }
function returnNullable(bool $give): ?Base { return $give ? new Deep() : null; }

echo get_class(returnSquare()), "\n";
echo get_class(returnBase()), "\n";
echo returnShape()->area(), "\n";
echo get_class(returnNamed()), "\n";
echo get_class(returnTagged()), "\n";
echo get_class(returnNullable(true)), "\n";
var_dump(returnNullable(false));

function badBase(): Base { return new Other(); }
function badShape(): Shape { return new Other(); }
function badSquare(): Square { return new Other(); }

try { badBase(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { badShape(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { badSquare(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
