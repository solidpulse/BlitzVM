<?php
interface IA { public function a(): string; }
interface IB { public function b(): string; }
interface IC extends IA, IB {}
abstract class Base {}
class Both extends Base implements IA, IB {
    public function a(): string { return 'a'; }
    public function b(): string { return 'b'; }
}
class ThroughInterface implements IC {
    public function a(): string { return 'ia'; }
    public function b(): string { return 'ib'; }
}
class OnlyA implements IA {
    public function a(): string { return 'only'; }
}
class Other {}

function takeAB(IA&IB $x): IA&IB { return $x; }
function takeBaseA(Base&IA $x): Base&IA { return $x; }
function takeNullableAB((IA&IB)|null $x): (IA&IB)|null { return $x; }

$both = new Both();
$through = new ThroughInterface();
var_dump(takeAB($both) === $both);
var_dump(takeAB($through) === $through);
var_dump(takeBaseA($both) === $both);
var_dump(takeNullableAB($both) === $both);
var_dump(takeNullableAB(null));

var_dump($both instanceof IA, $both instanceof IB, $both instanceof IC);
var_dump($through instanceof IA, $through instanceof IB, $through instanceof IC);
var_dump($through instanceof Both);

class Holder {
    public IA&IB $both;
    public Base&IA $base;
}
$h = new Holder();
$h->both = $both;
$h->base = $both;
var_dump($h->both === $both, $h->base === $both);

try { takeAB(new OnlyA()); } catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
try { takeBaseA(new ThroughInterface()); } catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
try { $h->both = new OnlyA(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { $h->base = new Other(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }

function badReturn(): IA&IB { return new OnlyA(); }
try { badReturn(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
