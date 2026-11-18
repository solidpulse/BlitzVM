<?php
// Bytecode 17a: instanceof against a literal class, self/parent/static, a dynamic class name,
// a dynamic class object, a missing class, primitive values, and a trait's using class.

interface I {}
class A implements I {}
class B extends A {}
class C {}

$b = new B();
var_dump($b instanceof B, $b instanceof A, $b instanceof I, $b instanceof C);
$name = 'A';
var_dump($b instanceof $name);
$other = new A();
var_dump($b instanceof $other, $b instanceof MissingClass99);
var_dump(null instanceof A, "x" instanceof A, 5 instanceof A);

class Base {}
class Child extends Base {
    public function test($v) {
        return [$v instanceof self, $v instanceof parent, $v instanceof static];
    }
}

$c = new Child();
$base = new Base();
var_dump($c->test($c));
var_dump($c->test($base));

trait T {
    public function inTrait($v) {
        return $v instanceof self;
    }
}
class UsesT {
    use T;
}

$u = new UsesT();
var_dump($u->inTrait($u), $u->inTrait(new stdClass()));
