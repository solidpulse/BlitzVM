<?php
// Bytecode 17a: clone copies properties and calls __clone; an array property is copied lazily,
// a reference property stays shared, and classes without __clone copy silently.

$clones = 0;

class Box {
    public $items;
    public $label;
    public $ref;
    public function __construct($label, $ref) {
        $this->label = $label;
        $this->items = [1, 2];
        $this->ref = $ref;
    }
    public function __clone() {
        global $clones;
        $clones++;
        $this->label .= ' (clone)';
    }
}

$original = new Box('box', 5);
$copy = clone $original;
echo $original->label, '|', $copy->label, "\n";
$copy->items[] = 3;
echo implode(',', $original->items), '|', implode(',', $copy->items), "\n";
echo $copy->ref, "\n";
var_dump($original !== $copy, $original == $copy);
echo $clones, "\n";

class NoClone { public $n = 1; }
$n = new NoClone();
$m = clone $n;
$m->n = 2;
echo $n->n, ':', $m->n, "\n";

class Priv {
    private $secret;
    public function __construct($s) { $this->secret = $s; }
    public function secret() { return $this->secret; }
}
$p = clone new Priv('s3');
echo $p->secret(), "\n";
