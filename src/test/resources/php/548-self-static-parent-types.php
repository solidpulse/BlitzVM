<?php
class Base {
    public self $selfProp;
    public function selfParam(self $x): self { return $x; }
    public function make(): static { return new static(); }
    public function isSelf($x): bool { return $x instanceof self; }
    public function isStatic($x): bool { return $x instanceof static; }
    public function isSelfClass($x): bool { return $x instanceof Base; }
}
class Child extends Base {
    public function parentParam(parent $x): parent { return $x; }
    public function isParent($x): bool { return $x instanceof parent; }
    public function badStatic(): static { return new Base(); }
}
class Other {}

$base = new Base();
$child = new Child();
$other = new Other();

var_dump($base->selfParam($base) === $base);
var_dump($child->selfParam($child) === $child);
var_dump($child->selfParam($base) === $base);
$base->selfProp = $base;
$child->selfProp = $child;
var_dump($base->selfProp === $base, $child->selfProp === $child);

var_dump($base->make() instanceof Base);
var_dump($child->make() instanceof Child);
var_dump($child->make() instanceof Base);

var_dump($child->parentParam($base) === $base);
var_dump($child->parentParam($child) === $child);

var_dump($child->isSelf($child), $child->isSelf($base), $child->isSelf($other));
var_dump($child->isStatic($child), $child->isStatic($base), $base->isStatic($base), $base->isStatic($child));
var_dump($child->isParent($child), $child->isParent($base), $child->isParent($other));
var_dump($child->isSelfClass($child), $child->isSelfClass($other));

try { $base->selfParam($other); } catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
try { $child->parentParam($other); } catch (TypeError $e) { echo preg_replace('/, called in .*$/', '', $e->getMessage()), "\n"; }
try { $child->badStatic(); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { $child->selfProp = $other; } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
