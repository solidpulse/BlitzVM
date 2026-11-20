<?php
class P { public ?int $a; public ?int $b = null; public int $c; public $d; public static ?int $s; public static ?int $t = null;
  public function __construct(public readonly ?int $r = null) {} }
$o = new P(); $o->a = null;
foreach (['a','b','c','d','r'] as $n) { $rp = new ReflectionProperty(P::class, $n); echo $n, ' init=', var_export($rp->isInitialized($o), true), ' default=', var_export($rp->hasDefaultValue(), true), "\n"; }
foreach (['s','t'] as $n) { $rp = new ReflectionProperty(P::class, $n); echo $n, ' init=', var_export($rp->isInitialized(), true), "\n"; }
P::$s = null; echo 's after null init=', var_export((new ReflectionProperty(P::class, 's'))->isInitialized(), true), "\n";
try { (new ReflectionProperty(P::class, 'c'))->getValue($o); } catch (Error $e) { echo $e->getMessage(), "\n"; }
var_dump((new ReflectionProperty(P::class, 'a'))->getValue($o));
