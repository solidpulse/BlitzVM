<?php
interface IA { public function a(): string; }
interface IB { public function b(): string; }
class A { public function a(): string { return 'a'; } }
class B {}
class C {}
class Both implements IA, IB {
    public function a(): string { return 'a'; }
    public function b(): string { return 'b'; }
}

function classParam(A $x): void {}
function ifaceParam(IA $x): void {}
function unionParam(A|B $x): void {}
function intersectionParam(IA&IB $x): void {}

function classReturn(): A { return new B(); }
function ifaceReturn(): IA { return new B(); }
function unionReturn(): A|B { return new C(); }

class Holder {
    public A $item;
    public IA $iface;
    public static A $staticItem;
}

function message(TypeError $e): string {
    return preg_replace('/, called in .*$/', '', $e->getMessage());
}

$b = new B();
$h = new Holder();

try { classParam($b); } catch (TypeError $e) { echo message($e), "\n"; }
try { ifaceParam($b); } catch (TypeError $e) { echo message($e), "\n"; }
try { unionParam(new C()); } catch (TypeError $e) { echo message($e), "\n"; }
try { intersectionParam(new A()); } catch (TypeError $e) { echo message($e), "\n"; }
try { classParam(42); } catch (TypeError $e) { echo message($e), "\n"; }
try { classParam(null); } catch (TypeError $e) { echo message($e), "\n"; }

try { classReturn(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { ifaceReturn(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { unionReturn(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }

try { $h->item = $b; } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { $h->iface = new C(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { Holder::$staticItem = new C(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
