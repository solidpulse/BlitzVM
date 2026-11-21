<?php
#[AllowDynamicProperties]
class SerializeBase {
    public $pub = 'base-pub';
    protected $prot = 'base-prot';
    private $priv = 'base-priv';
    private $same = 'base-same';
    public int $typed;
    public $untyped;
}
#[AllowDynamicProperties]
class SerializeChild extends SerializeBase {
    public $child = 'child-pub';
    private $same = 'child-same';
    protected $childProt = 'child-prot';
}

$child = new SerializeChild();
$child->untyped = 'set';
$child->typed = 7;
$child->dynamic = 'dyn';
$child->nested = ['a' => 1, 'b' => [2, 3]];
echo serialize($child), "\n";

$child2 = new SerializeChild();
$child2->typed = 1;
$child2->untyped = 'u';
$child2->dynamic = 'd2';
unset($child2->nested);
echo serialize($child2), "\n";

$plain = new SerializeChild();
echo serialize($plain), "\n";

$obj = new stdClass();
$obj->value = 11;
$root = new stdClass();
$root->first = $obj;
$root->list = [$obj, [$obj]];
echo serialize($root), "\n";

$self = new stdClass();
$self->me = $self;
$self->data = 'x';
echo serialize($self), "\n";

$array = unserialize('a:1:{i:0;a:1:{i:0;R:2;}}');
echo serialize($array), "\n";

$scalar = 5;
$refs = [&$scalar, &$scalar, 6];
echo serialize($refs), "\n";

$back = unserialize(serialize($root));
echo ($back->first === $back->list[0]) ? 'shared' : 'different', "\n";
echo ($back->first === $back->list[1][0]) ? 'shared' : 'different', "\n";
$back->list[1][0]->value = 99;
echo $back->first->value, "\n";

$selfBack = unserialize(serialize($self));
echo ($selfBack->me === $selfBack) ? 'recursive' : 'broken', "\n";
echo serialize($selfBack), "\n";

$refsBack = unserialize(serialize($refs));
$refsBack[0] = 'changed';
echo $refsBack[1], "\n";
echo serialize($refsBack), "\n";

$arrayBack = unserialize(serialize($array));
echo serialize($arrayBack), "\n";

$nestedArray = [1, [2, [3, ['deep' => true]]]];
$nestedBack = unserialize(serialize($nestedArray));
var_dump($nestedBack);
