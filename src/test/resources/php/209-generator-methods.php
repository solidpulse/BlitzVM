<?php
trait WalkTrait {
    public function walk() { yield self::NAME; yield static::NAME; yield $this->value; }
}
class BaseWalker {
    const NAME = 'base';
    public $value = 12;
    use WalkTrait;
    public function generatorClosure(&$x) {
        return function () use (&$x) { yield $this->value; yield ++$x; return self::NAME; };
    }
}
class ChildWalker extends BaseWalker { const NAME = 'child'; }
$o = new ChildWalker;
echo json_encode(iterator_to_array($o->walk())), "\n";
$x = 3;
$closure = $o->generatorClosure($x);
$g = $closure();
echo json_encode([...$g]), ':', $g->getReturn(), ':', $x, "\n";
function spreadGenerator() { yield 'b' => 2; yield 'a' => 1; }
function named($a, $b) { return "$a:$b"; }
echo named(...spreadGenerator()), "\n";
