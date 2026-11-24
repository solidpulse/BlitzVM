<?php
interface Shape { public function area(): int; }
interface Named {}
abstract class Base implements Shape { public int $w = 2; }
class Square extends Base implements Named { public function area(): int { return $this->w * $this->w; } }
class Other {}

class Box {
    public Base $base;
    public Shape $shape;
    public ?Square $square = null;
    public Base|int $union = 0;
    public static Base $staticBase;
}

$box = new Box();
$box->base = new Square();
$box->shape = new Square();
$box->square = new Square();
$box->union = new Square();
$box->union = 41;
$box->union = "42";
Box::$staticBase = new Square();

echo get_class($box->base), "\n";
echo get_class($box->shape), "\n";
echo get_class($box->square), "\n";
var_dump($box->union);
echo get_class(Box::$staticBase), "\n";

try { $box->base = new Other(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { $box->shape = new Other(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { $box->square = new Other(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { $box->union = new Other(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { Box::$staticBase = new Other(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }

class ParentBox { public Base $item; }
class ChildBox extends ParentBox {}
$child = new ChildBox();
$child->item = new Square();
echo get_class($child->item), "\n";
try { $child->item = new Other(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
