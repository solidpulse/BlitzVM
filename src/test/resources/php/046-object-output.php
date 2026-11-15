<?php

class Inner {
    public int $n = 7;
    protected string $note = 'hi';
}

#[AllowDynamicProperties]
class OuterParent {
    public $pub = 'p';
    protected $prot = 'r';
    private $priv = 's1';
}

class OuterChild extends OuterParent {
    private $priv = 's2';
    public $nested;
    public array $arr = ['a' => 1];

    public function __construct() {
        $this->nested = new Inner();
    }
}

class Blank {}

$e = new Blank();
var_dump($e);
print_r($e);
var_export($e);
echo "\n";

$o = new OuterChild();
$o->extra = true;
var_dump($o);
print_r($o);
var_export($o);
echo "\n";

$r = print_r(['item' => new Inner()], true);
$x = var_export(['item' => new Inner()], true);
echo $r, $x, "\n";
