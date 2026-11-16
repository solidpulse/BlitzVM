<?php
[$a, [$b, [$c, $d]]] = [1, [2, [3, 4]]];
echo "nested=$a,$b,$c,$d
";

['id' => $id, 'meta' => ['role' => $role, 'tags' => [, $secondTag]]] = [
    'id' => 99,
    'meta' => ['role' => 'admin', 'tags' => ['core', 'lead']],
];
echo "keyed=$id,$role,$secondTag
";

list('k' => $kv, 'j' => $jv) = ['j' => 'jay', 'k' => 'kay'];
echo "list_keyed=$kv,$jv
";

class DestTarget {
    public $first;
    public $second;
    public function unpack(array $src) {
        [$this->first, $this->second] = $src;
    }
}
$dt = new DestTarget();
$dt->unpack(['alpha', 'beta']);
echo "prop={$dt->first},{$dt->second}
";

$out = [];
$idx = 0;
[$out[$idx++], $out[$idx++]] = ['one', 'two'];
echo "arr_target={$out[0]},{$out[1]}
";
