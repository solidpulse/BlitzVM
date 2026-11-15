<?php

class Leaf {
    public int $n;
    public function __construct(int $n) {
        $this->n = $n;
    }
}

class Tree {
    public string $pub = 'pub';
    protected string $prot = 'prot';
    private string $priv = 'priv';
    public array $tags = ['a', 'b'];
    public Leaf $sharedLeaf;
    public Leaf $ownedLeaf;

    public function __construct() {
        $this->sharedLeaf = new Leaf(1);
        $this->ownedLeaf = new Leaf(2);
    }

    public function __clone(): void {
        $this->ownedLeaf = clone $this->ownedLeaf;
    }

    public function iterateSelf(): array {
        $out = [];
        foreach ($this as $k => $v) {
            if (!is_object($v) && !is_array($v)) {
                $out[$k] = $v;
            }
        }
        return $out;
    }
}

$t1 = new Tree();
$t2 = clone $t1;
$t2->pub = 'changed';
$t2->tags[] = 'c';
$t2->sharedLeaf->n = 100;
$t2->ownedLeaf->n = 200;

echo $t1->pub, '|', $t2->pub, "\n";
echo implode(',', $t1->tags), '|', implode(',', $t2->tags), "\n";
echo $t1->sharedLeaf->n, '|', $t1->ownedLeaf->n, '|', $t2->ownedLeaf->n, "\n";

$arr = (array) $t1;
var_dump(array_key_exists('pub', $arr), array_key_exists("\0*\0prot", $arr), array_key_exists("\0Tree\0priv", $arr));

$std = new stdClass();
$std->k = 'v';
$o1 = (object) ['x' => 1, 'y' => 'hi'];
$o2 = (object) [10, 'a' => 20];
$o3 = (object) 42;
$o4 = (object) null;
$o5 = (object) $std;

var_dump($o1->x, $o1->y, $o3->scalar, $o5 === $std, get_class($o4));
foreach ($o2 as $k => $v) {
    var_dump($k, $v);
}

echo "outside foreach:\n";
foreach ($t1 as $k => $v) {
    if (!is_object($v) && !is_array($v)) {
        echo "$k=$v\n";
    }
}
echo "inside foreach:\n";
print_r($t1->iterateSelf());

echo json_encode($std), "\n";
echo json_encode(new stdClass()), "\n";
echo json_encode($o2), "\n";
echo json_encode($t1), "\n";
echo json_encode($o1, JSON_PRETTY_PRINT), "\n";
