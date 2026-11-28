<?php
interface Shape { public function area(): int; }
interface Named {}
interface Tagged extends Named {}
abstract class Base implements Shape { public int $w = 2; }
class Square extends Base implements Tagged { public function area(): int { return $this->w * $this->w; } }
class Other {}
class Deep extends Square {}

$sq = new Square();
$deep = new Deep();
$other = new Other();

echo (int) ($sq instanceof Square), "\n";
echo (int) ($deep instanceof Square), "\n";
echo (int) ($sq instanceof Base), "\n";
echo (int) ($sq instanceof Shape), "\n";
echo (int) ($sq instanceof Named), "\n";
echo (int) ($sq instanceof Tagged), "\n";
echo (int) ($other instanceof Shape), "\n";
echo (int) ($other instanceof Base), "\n";
echo (int) ($other instanceof Square), "\n";
echo (int) ($sq instanceof Other), "\n";

var_dump($sq instanceof stdClass);
var_dump($sq instanceof NoSuchClass);
var_dump($other instanceof NoSuchClass);

$name = 'Base';
var_dump($sq instanceof $name);
$name = 'Tagged';
var_dump($sq instanceof $name);
$name = 'Other';
var_dump($sq instanceof $name);
$name = 'NoSuchClass';
var_dump($sq instanceof $name);

$obj = new Square();
var_dump($sq instanceof $obj);
$obj = new Other();
var_dump($sq instanceof $obj);
var_dump($other instanceof $obj);

var_dump(null instanceof stdClass, 42 instanceof stdClass, 'x' instanceof stdClass);
