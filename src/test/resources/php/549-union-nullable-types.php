<?php
class A { public function __toString(): string { return 'a'; } }
class B {}
class C {}
class WithToString { public function __toString(): string { return 's'; } }

function takeNullable(?A $x): ?A { return $x; }
function takeUnion(A|B $x): A|B { return $x; }
function takeLooseUnion(A|int $x): A|int { return $x; }
function takeStringUnion(A|string $x): A|string { return $x; }
function takeNullUnion(A|null $x): A|null { return $x; }

$a = new A();
$b = new B();
$w = new WithToString();

var_dump(takeNullable($a) === $a);
var_dump(takeNullable(null));
var_dump(takeUnion($a) === $a, takeUnion($b) === $b);
var_dump(takeLooseUnion($a) === $a);
var_dump(takeLooseUnion(5));
var_dump(takeLooseUnion("42"));
var_dump(takeLooseUnion(3.0));
var_dump(takeStringUnion($w));
var_dump(takeStringUnion($a) === $a);
var_dump(takeNullUnion(null));

try { takeNullable($b); } catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
try { takeUnion(new C()); } catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
try { takeLooseUnion("nope"); } catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
try { takeStringUnion($b); } catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }

class Holder {
    public ?A $nullable = null;
    public A|B $union;
    public A|int $loose = 0;
}
$h = new Holder();
$h->nullable = $a;
$h->union = $b;
$h->loose = "13";
var_dump($h->nullable === $a, $h->union === $b, $h->loose);
try { $h->nullable = $b; } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { $h->union = new C(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { $h->loose = $w; } catch (TypeError $e) { echo $e->getMessage(), "\n"; }

function badReturn(): A|B { return new C(); }
try { badReturn(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
