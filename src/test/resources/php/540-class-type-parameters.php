<?php
interface Shape { public function area(): int; }
interface Named {}
interface Tagged extends Named {}
abstract class Base implements Shape { public int $w = 2; }
class Square extends Base implements Tagged { public function area(): int { return $this->w * $this->w; } }
class Other {}

function describe(Shape $s, Base $b, Named $n, Tagged $t, Square $q): string {
    return get_class($s) . ':' . $s->area() . ':' . get_class($b) . ':' . get_class($n) . ':' . get_class($t) . ':' . get_class($q);
}

$sq = new Square();
echo describe($sq, $sq, $sq, $sq, $sq), "\n";

try { describe(new Other(), $sq, $sq, $sq, $sq); }
catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
try { describe($sq, new Other(), $sq, $sq, $sq); }
catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
try { describe($sq, $sq, new Other(), $sq, $sq); }
catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
try { describe($sq, $sq, $sq, new Other(), $sq); }
catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
try { describe($sq, $sq, $sq, $sq, new Other()); }
catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }

function takesAbstract(Base $b): string { return get_class($b); }
class Deep extends Square {}
echo takesAbstract(new Deep()), "\n";
echo takesAbstract(new Square()), "\n";
